package com.offlinegguf.chat.util

/**
 * Line-level diff, used to show *what changed* between two revisions of a code
 * block in a conversation ("show changes" on an edited snippet).
 *
 * Approach: common prefix/suffix trimming first (which disposes of the bulk of
 * real edits for free), then a classic LCS dynamic program over what remains.
 * The DP is bounded — if the remaining block is so large that the table would
 * not fit comfortably in a phone's heap, the diff degrades to a whole-block
 * replace instead of risking an OOM. Correctness of the *content* is unaffected;
 * only the granularity of the answer changes, and [DiffResult.exact] says so.
 */
object LineDiffer {

    /** Rows are either a line or a collapsed run of unchanged lines. */
    sealed class Row {
        data class Line(
            val kind: Kind,
            /** 1-based line number in the old text, 0 when the line is new. */
            val oldNumber: Int,
            /** 1-based line number in the new text, 0 when the line was removed. */
            val newNumber: Int,
            val text: String,
        ) : Row()

        /** `count` unchanged lines were collapsed to keep the view short. */
        data class Gap(val count: Int) : Row()
    }

    enum class Kind { CONTEXT, ADDED, REMOVED }

    data class Result(
        val rows: List<Row>,
        val added: Int,
        val removed: Int,
        val unchanged: Int,
        val oldLineCount: Int,
        val newLineCount: Int,
        /** False when the inputs were too large for an exact LCS. */
        val exact: Boolean,
    ) {
        val summary: String
            get() = when {
                added == 0 && removed == 0 -> "no changes"
                removed == 0 -> "+$added"
                added == 0 -> "−$removed"
                else -> "+$added −$removed"
            }

        /** True when nothing at all differs (including trailing newline state). */
        val isEmpty: Boolean get() = added == 0 && removed == 0
    }

    /** Cells the LCS table may occupy before we bail out to a coarse diff. */
    private const val MAX_DP_CELLS = 1_000_000

    /** Unchanged lines kept visible around a change. */
    const val DEFAULT_CONTEXT = 2

    fun diff(
        oldText: String,
        newText: String,
        context: Int = DEFAULT_CONTEXT,
    ): Result {
        val a = splitLines(oldText)
        val b = splitLines(newText)
        val plan = computeOps(a, b)
        val ops = plan.ops

        var added = 0
        var removed = 0
        var unchanged = 0
        var oldLine = 0
        var newLine = 0
        val flat = ArrayList<Row>(ops.size)
        for (op in ops) {
            when (op.kind) {
                Kind.ADDED -> {
                    newLine++
                    added++
                    flat += Row.Line(Kind.ADDED, 0, newLine, op.text)
                }
                Kind.REMOVED -> {
                    oldLine++
                    removed++
                    flat += Row.Line(Kind.REMOVED, oldLine, 0, op.text)
                }
                Kind.CONTEXT -> {
                    oldLine++
                    newLine++
                    unchanged++
                    flat += Row.Line(Kind.CONTEXT, oldLine, newLine, op.text)
                }
            }
        }
        return Result(
            rows = collapse(flat, context),
            added = added,
            removed = removed,
            unchanged = unchanged,
            oldLineCount = a.size,
            newLineCount = b.size,
            exact = plan.exact,
        )
    }

    private data class Op(val kind: Kind, val text: String)

    /** [computeOps] output plus whether the middle fit in an exact LCS table. */
    private data class Plan(val ops: List<Op>, val exact: Boolean)

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        // Drop a single trailing newline so "a\n" and "a" do not show a phantom
        // empty added/removed line.
        val trimmed = if (text.endsWith("\n")) text.dropLast(1) else text
        return trimmed.split("\n")
    }

    private fun computeOps(a: List<String>, b: List<String>): Plan {
        if (a.isEmpty() && b.isEmpty()) return Plan(emptyList(), true)
        if (a.isEmpty()) return Plan(b.map { Op(Kind.ADDED, it) }, true)
        if (b.isEmpty()) return Plan(a.map { Op(Kind.REMOVED, it) }, true)

        // 1) common prefix / suffix
        var start = 0
        val maxPrefix = minOf(a.size, b.size)
        while (start < maxPrefix && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
            endA--
            endB--
        }

        val ops = ArrayList<Op>(a.size + b.size)
        for (i in 0 until start) ops += Op(Kind.CONTEXT, a[i])

        val midA = a.subList(start, endA)
        val midB = b.subList(start, endB)
        var exact = true
        if (midA.size.toLong() * midB.size.toLong() > MAX_DP_CELLS) {
            // Too big for an exact table: report it as a block replace and say so.
            exact = false
            midA.forEach { ops += Op(Kind.REMOVED, it) }
            midB.forEach { ops += Op(Kind.ADDED, it) }
        } else {
            ops += lcsOps(midA, midB)
        }

        for (i in endA until a.size) ops += Op(Kind.CONTEXT, a[i])
        return Plan(ops, exact)
    }

    /** LCS over the trimmed middle, backtracked into ordered ops. */
    private fun lcsOps(a: List<String>, b: List<String>): List<Op> {
        val n = a.size
        val m = b.size
        if (n == 0) return b.map { Op(Kind.ADDED, it) }
        if (m == 0) return a.map { Op(Kind.REMOVED, it) }

        val stride = m + 1
        val dp = IntArray((n + 1) * stride)
        for (i in n - 1 downTo 0) {
            val rowBase = i * stride
            val nextRowBase = (i + 1) * stride
            val ai = a[i]
            for (j in m - 1 downTo 0) {
                dp[rowBase + j] = if (ai == b[j]) {
                    dp[nextRowBase + j + 1] + 1
                } else {
                    val down = dp[nextRowBase + j]
                    val right = dp[rowBase + j + 1]
                    if (down >= right) down else right
                }
            }
        }

        val ops = ArrayList<Op>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    ops += Op(Kind.CONTEXT, a[i]); i++; j++
                }
                dp[(i + 1) * stride + j] >= dp[i * stride + j + 1] -> {
                    ops += Op(Kind.REMOVED, a[i]); i++
                }
                else -> {
                    ops += Op(Kind.ADDED, b[j]); j++
                }
            }
        }
        while (i < n) { ops += Op(Kind.REMOVED, a[i]); i++ }
        while (j < m) { ops += Op(Kind.ADDED, b[j]); j++ }
        return ops
    }

    /**
     * Collapses runs of unchanged lines longer than `2 * context + 1` into a
     * [Row.Gap], always keeping the file's first and last lines visible so the
     * reader knows where the block starts and ends.
     */
    private fun collapse(rows: List<Row>, context: Int): List<Row> {
        if (context < 0 || rows.isEmpty()) return rows
        val keep = BooleanArray(rows.size)
        var index = 0
        for (row in rows) {
            if (row is Row.Line && row.kind != Kind.CONTEXT) {
                val from = (index - context).coerceAtLeast(0)
                val to = (index + context).coerceAtMost(rows.size - 1)
                for (k in from..to) keep[k] = true
            }
            index++
        }
        // Head and tail always survive a collapse.
        if (rows.isNotEmpty()) {
            keep[0] = true
            keep[rows.size - 1] = true
        }

        val out = ArrayList<Row>(rows.size)
        var gap = 0
        for (i in rows.indices) {
            if (keep[i]) {
                if (gap > 0) {
                    out += Row.Gap(gap)
                    gap = 0
                }
                out += rows[i]
            } else {
                gap++
            }
        }
        if (gap > 0) out += Row.Gap(gap)
        return out
    }

    /** Counts changes without building rows — used by list previews. */
    fun counts(oldText: String, newText: String): Pair<Int, Int> {
        var added = 0
        var removed = 0
        for (op in computeOps(splitLines(oldText), splitLines(newText)).ops) {
            when (op.kind) {
                Kind.ADDED -> added++
                Kind.REMOVED -> removed++
                Kind.CONTEXT -> Unit
            }
        }
        return added to removed
    }
}

/**
 * Parser for a unified diff emitted *by a model* (the "Patch mode" skill).
 *
 * This is the other half of the added/removed-lines feature: [LineDiffer]
 * computes a diff between two revisions the app already has, while this reads a
 * diff the model wrote. Both produce the same row shapes so one renderer handles
 * either.
 */
object UnifiedDiff {

    data class Entry(
        val kind: LineDiffer.Kind,
        val text: String,
        val oldNumber: Int,
        val newNumber: Int,
    )

    data class Parsed(
        val oldFile: String?,
        val newFile: String?,
        val entries: List<Entry>,
        val hunks: Int,
        val added: Int,
        val removed: Int,
        val valid: Boolean,
    ) {
        val summary: String
            get() = when {
                added == 0 && removed == 0 -> "no changes"
                removed == 0 -> "+$added"
                added == 0 -> "−$removed"
                else -> "+$added −$removed"
            }
    }

    private val HUNK = Regex("^@@\\s*-(\\d+)(?:,(\\d+))?\\s*\\+(\\d+)(?:,(\\d+))?\\s*@@")

    fun parse(text: String): Parsed {
        val entries = ArrayList<Entry>()
        var oldFile: String? = null
        var newFile: String? = null
        var hunks = 0
        var added = 0
        var removed = 0
        var oldLine = 0
        var newLine = 0

        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            when {
                line.startsWith("--- ") -> oldFile = line.removePrefix("--- ").removePrefix("a/").trim()
                line.startsWith("+++ ") -> newFile = line.removePrefix("+++ ").removePrefix("b/").trim()
                line.startsWith("@@") -> {
                    val match = HUNK.find(line)
                    hunks++
                    oldLine = (match?.groupValues?.get(1)?.toIntOrNull() ?: (oldLine + 1)) - 1
                    newLine = (match?.groupValues?.get(3)?.toIntOrNull() ?: (newLine + 1)) - 1
                }
                line.startsWith("\\") -> Unit // "\ No newline at end of file"
                line.startsWith("+") -> {
                    newLine++
                    added++
                    entries += Entry(LineDiffer.Kind.ADDED, line.substring(1), 0, newLine)
                }
                line.startsWith("-") -> {
                    oldLine++
                    removed++
                    entries += Entry(LineDiffer.Kind.REMOVED, line.substring(1), oldLine, 0)
                }
                line.startsWith(" ") -> {
                    oldLine++
                    newLine++
                    entries += Entry(LineDiffer.Kind.CONTEXT, line.substring(1), oldLine, newLine)
                }
                line.isEmpty() -> {
                    // A blank line inside a hunk is context with no leading space;
                    // models drop it constantly.
                    oldLine++
                    newLine++
                    entries += Entry(LineDiffer.Kind.CONTEXT, "", oldLine, newLine)
                }
                else -> {
                    oldLine++
                    newLine++
                    entries += Entry(LineDiffer.Kind.CONTEXT, line, oldLine, newLine)
                }
            }
        }
        return Parsed(
            oldFile = oldFile,
            newFile = newFile,
            entries = entries,
            hunks = hunks,
            added = added,
            removed = removed,
            valid = entries.any { it.kind != LineDiffer.Kind.CONTEXT },
        )
    }

    /** True when a block of text looks like a unified diff rather than source code. */
    fun looksLikeDiff(text: String): Boolean {
        if (text.isBlank()) return false
        val lines = text.split('\n')
        if (lines.size < 2) return false
        val hasHeader = lines.any { it.startsWith("@@") && it.trimEnd().endsWith("@@") } ||
            (lines.any { it.startsWith("--- ") } && lines.any { it.startsWith("+++ ") })
        val hasChanges = lines.count { it.startsWith("+") || it.startsWith("-") } >= 1
        return hasHeader && hasChanges
    }
}
