package com.offlinegguf.chat.util

/**
 * A small, deliberate Markdown parser.
 *
 * It is not CommonMark. It handles exactly what a local chat model emits —
 * paragraphs, headings, fenced code, lists, quotes, rules, simple tables and the
 * four inline forms — and it is streaming-aware: an unterminated code fence at
 * the end of the buffer still renders as code (with [MarkdownBlock.Code.terminated]
 * false) instead of dumping half a fence into a paragraph.
 *
 * Doing this by hand rather than pulling a Markdown library keeps the APK small
 * and, more importantly, keeps code blocks identifiable so the diff view can
 * offer "show changes" on them.
 */
object MarkdownBlocks {

    sealed class Block {
        data class Paragraph(val text: String) : Block()
        data class Heading(val level: Int, val text: String) : Block()
        data class Code(
            val language: String,
            val content: String,
            /** False while the closing fence has not arrived yet. */
            val terminated: Boolean,
        ) : Block() {
            val lineCount: Int get() = if (content.isEmpty()) 0 else content.split('\n').size
        }

        data class BulletList(val items: List<String>, val ordered: Boolean) : Block()
        data class Quote(val text: String) : Block()
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block()
        object Rule : Block()
    }

    sealed class Span {
        data class Text(val value: String) : Span()
        data class Bold(val value: String) : Span()
        data class Italic(val value: String) : Span()
        data class InlineCode(val value: String) : Span()
        data class Link(val text: String, val url: String) : Span()
    }

    fun parse(input: String): List<Block> {
        if (input.isEmpty()) return emptyList()
        val lines = input.split('\n')
        val out = ArrayList<Block>()
        var index = 0
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                out += Block.Paragraph(paragraph.toString().trim())
                paragraph.setLength(0)
            }
        }

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()

            // ---- fenced code ----
            val fence = fenceOf(trimmed)
            if (fence != null) {
                flushParagraph()
                val language = trimmed.substring(fence.length).trim()
                val body = StringBuilder()
                var terminated = false
                index++
                while (index < lines.size) {
                    val candidate = lines[index].trim()
                    if (fenceOf(candidate) != null && candidate.length == fence.length) {
                        terminated = true
                        index++
                        break
                    }
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[index])
                    index++
                }
                out += Block.Code(normalizeLanguage(language), body.toString(), terminated)
                continue
            }

            // ---- horizontal rule ----
            if (isRule(trimmed)) {
                flushParagraph()
                out += Block.Rule
                index++
                continue
            }

            // ---- heading ----
            if (trimmed.startsWith("#")) {
                val level = trimmed.takeWhile { it == '#' }.length
                if (level in 1..6 && (trimmed.length == level || trimmed[level] == ' ')) {
                    flushParagraph()
                    out += Block.Heading(level, trimmed.substring(level).trim())
                    index++
                    continue
                }
            }

            // ---- table: header row followed by a separator row ----
            if (trimmed.startsWith("|") && index + 1 < lines.size && isTableSeparator(lines[index + 1].trim())) {
                flushParagraph()
                val header = tableCells(trimmed)
                val rows = ArrayList<List<String>>()
                index += 2
                while (index < lines.size && lines[index].trim().startsWith("|")) {
                    rows += tableCells(lines[index].trim())
                    index++
                }
                out += Block.Table(header, rows)
                continue
            }

            // ---- quote ----
            if (trimmed.startsWith(">")) {
                flushParagraph()
                val quote = StringBuilder()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(lines[index].trim().removePrefix(">").trim())
                    index++
                }
                out += Block.Quote(quote.toString())
                continue
            }

            // ---- list ----
            if (isListItem(trimmed)) {
                flushParagraph()
                val ordered = isOrderedItem(trimmed)
                val items = ArrayList<String>()
                while (index < lines.size) {
                    val candidate = lines[index].trim()
                    if (candidate.isEmpty()) {
                        // A blank line ends the list unless the next line continues it.
                        if (index + 1 < lines.size && isListItem(lines[index + 1].trim())) {
                            index++
                            continue
                        }
                        break
                    }
                    if (!isListItem(candidate)) break
                    items += stripListMarker(candidate)
                    index++
                }
                if (items.isNotEmpty()) out += Block.BulletList(items, ordered)
                continue
            }

            // ---- blank line ends a paragraph ----
            if (trimmed.isEmpty()) {
                flushParagraph()
                index++
                continue
            }

            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(line.trimEnd())
            index++
        }
        flushParagraph()
        return out
    }

    /** Code blocks only — used by the "show changes" diff feature. */
    fun codeBlocks(input: String): List<Block.Code> =
        parse(input).filterIsInstance<Block.Code>()

    private fun fenceOf(trimmed: String): String? {
        if (trimmed.startsWith("```")) return trimmed.takeWhile { it == '`' }
        if (trimmed.startsWith("~~~")) return trimmed.takeWhile { it == '~' }
        return null
    }

    private fun normalizeLanguage(raw: String): String {
        val lower = raw.lowercase().trim()
        return when {
            lower.isEmpty() -> ""
            lower.startsWith("kotlin") || lower == "kt" -> "kotlin"
            lower == "js" || lower == "javascript" -> "javascript"
            lower == "ts" || lower == "typescript" -> "typescript"
            lower == "py" -> "python"
            lower == "sh" || lower == "shell" || lower == "bash" || lower == "zsh" -> "bash"
            lower == "md" -> "markdown"
            lower == "yml" -> "yaml"
            lower == "c++" || lower == "cc" || lower == "cxx" -> "cpp"
            lower == "cs" -> "csharp"
            lower == "rs" -> "rust"
            lower == "golang" -> "go"
            lower == "json5" -> "json"
            lower == "diff" || lower == "patch" -> "diff"
            else -> lower
        }
    }

    private fun isRule(trimmed: String): Boolean {
        if (trimmed.length < 3) return false
        val first = trimmed[0]
        if (first != '-' && first != '*' && first != '_') return false
        return trimmed.all { it == first || it == ' ' } && trimmed.count { it == first } >= 3
    }

    private fun isListItem(trimmed: String): Boolean =
        isOrderedItem(trimmed) || trimmed.startsWith("- ") || trimmed.startsWith("* ") ||
            trimmed.startsWith("+ ") || trimmed == "-" || trimmed == "*"

    private fun isOrderedItem(trimmed: String): Boolean {
        val digits = trimmed.takeWhile { it.isDigit() }
        if (digits.isEmpty() || digits.length > 3) return false
        val rest = trimmed.substring(digits.length)
        return rest.startsWith(". ") || rest.startsWith(") ")
    }

    private fun stripListMarker(trimmed: String): String {
        if (isOrderedItem(trimmed)) {
            val digits = trimmed.takeWhile { it.isDigit() }
            return trimmed.substring(digits.length).removePrefix(".").removePrefix(")").trim()
        }
        return when {
            trimmed.startsWith("- ") -> trimmed.substring(2).trim()
            trimmed.startsWith("* ") -> trimmed.substring(2).trim()
            trimmed.startsWith("+ ") -> trimmed.substring(2).trim()
            else -> trimmed
        }
    }

    private fun isTableSeparator(trimmed: String): Boolean {
        if (!trimmed.startsWith("|") || trimmed.length < 3) return false
        val body = trimmed.trim('|')
        if (body.isBlank()) return false
        return body.split('|').all { cell ->
            val cleaned = cell.trim().removePrefix(":").removeSuffix(":")
            cleaned.isNotEmpty() && cleaned.all { it == '-' }
        }
    }

    private fun tableCells(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /**
     * Inline formatting, as a flat span list ready for an AnnotatedString.
     *
     * One left-to-right scan, first marker that matches wins, and an unmatched
     * marker is emitted as literal text. Deliberately no nesting: models rarely
     * produce it, and a nested parser is where this kind of code goes wrong.
     */
    fun inline(text: String): List<Span> {
        if (text.isEmpty()) return emptyList()
        val spans = ArrayList<Span>()
        val plain = StringBuilder()
        var i = 0

        fun flush() {
            if (plain.isNotEmpty()) {
                spans += Span.Text(plain.toString())
                plain.setLength(0)
            }
        }

        while (i < text.length) {
            // `code`
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flush()
                    spans += Span.InlineCode(text.substring(i + 1, end))
                    i = end + 1
                    continue
                }
            }

            // **bold** / __bold__
            if (text.length - i >= 4 && (text.startsWith("**", i) || text.startsWith("__", i))) {
                val marker = text.substring(i, i + 2)
                val end = text.indexOf(marker, i + 2)
                if (end > i + 2) {
                    flush()
                    spans += Span.Bold(text.substring(i + 2, end))
                    i = end + 2
                    continue
                }
            }

            // [text](url)
            if (text[i] == '[') {
                val close = text.indexOf(']', i + 1)
                if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                    val urlEnd = text.indexOf(')', close + 2)
                    if (urlEnd > close) {
                        flush()
                        spans += Span.Link(text.substring(i + 1, close), text.substring(close + 2, urlEnd))
                        i = urlEnd + 1
                        continue
                    }
                }
            }

            // *italic* / _italic_ (single marker only; doubles were handled above)
            val ch = text[i]
            if ((ch == '*' || ch == '_') && !text.startsWith("$ch$ch", i)) {
                var end = -1
                var j = i + 1
                while (j < text.length) {
                    if (text[j] == '\\' && j + 1 < text.length) {
                        j += 2
                        continue
                    }
                    if (text[j] == ch) {
                        end = j
                        break
                    }
                    j++
                }
                if (end > i) {
                    flush()
                    spans += Span.Italic(text.substring(i + 1, end))
                    i = end + 1
                    continue
                }
            }

            plain.append(text[i])
            i++
        }
        flush()
        return spans
    }
}
