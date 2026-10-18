package com.offlinegguf.chat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffTest {

    private fun lines(count: Int, prefix: String) =
        (1..count).joinToString("\n") { "$prefix$it" }

    // ------------------------------------------------------------- LineDiffer

    @Test
    fun `identical text reports no changes`() {
        val result = LineDiffer.diff("a\nb\nc", "a\nb\nc")
        assertEquals(0, result.added)
        assertEquals(0, result.removed)
        assertEquals(3, result.unchanged)
        assertEquals("no changes", result.summary)
        assertTrue(result.isEmpty)
        assertTrue(result.exact)
        assertEquals(3, result.oldLineCount)
        assertEquals(3, result.newLineCount)
    }

    @Test
    fun `a trailing newline is not a phantom line`() {
        val result = LineDiffer.diff("a\n", "a")
        assertTrue(result.isEmpty)
        assertEquals(1, result.oldLineCount)
        assertEquals(1, result.newLineCount)
    }

    @Test
    fun `a changed line is one removal and one addition`() {
        val result = LineDiffer.diff("a\nb\nc", "a\nB\nc")
        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        assertEquals("+1 −1", result.summary)
        assertFalse(result.isEmpty)

        val changed = result.rows.filterIsInstance<LineDiffer.Row.Line>()
            .filter { it.kind != LineDiffer.Kind.CONTEXT }
        assertEquals(2, changed.size)
        val removed = changed.first { it.kind == LineDiffer.Kind.REMOVED }
        val added = changed.first { it.kind == LineDiffer.Kind.ADDED }
        assertEquals("b", removed.text)
        assertEquals(2, removed.oldNumber)
        assertEquals(0, removed.newNumber)
        assertEquals("B", added.text)
        assertEquals(0, added.oldNumber)
        assertEquals(2, added.newNumber)
    }

    @Test
    fun `summaries cover the one-sided cases`() {
        assertEquals("+1", LineDiffer.diff("a", "a\nb").summary)
        assertEquals("−1", LineDiffer.diff("a\nb", "a").summary)
        assertEquals("+1", LineDiffer.diff("", "a").summary)
        assertEquals("−1", LineDiffer.diff("a", "").summary)
        assertEquals("no changes", LineDiffer.diff("", "").summary)
    }

    @Test
    fun `long unchanged runs collapse into gaps but the ends stay visible`() {
        val old = lines(10, "L")
        val new = lines(10, "L").replace("L5", "L5x")
        val result = LineDiffer.diff(old, new, context = 2)

        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        assertEquals(10, result.unchanged)

        val gaps = result.rows.filterIsInstance<LineDiffer.Row.Gap>()
        assertEquals(2, gaps.size)
        assertEquals(1, gaps[0].count)
        assertEquals(2, gaps[1].count)

        // Head and tail always survive, so the reader can see the block bounds.
        val first = result.rows.first() as LineDiffer.Row.Line
        val last = result.rows.last() as LineDiffer.Row.Line
        assertEquals("L1", first.text)
        assertEquals(LineDiffer.Kind.CONTEXT, first.kind)
        assertEquals("L10", last.text)
    }

    @Test
    fun `context zero keeps only the changes and the two ends`() {
        val result = LineDiffer.diff(lines(10, "L"), lines(10, "L").replace("L5", "L5x"), context = 0)
        val kinds = result.rows.map {
            when (it) {
                is LineDiffer.Row.Gap -> "gap(${it.count})"
                is LineDiffer.Row.Line -> when (it.kind) {
                    LineDiffer.Kind.ADDED -> "+"
                    LineDiffer.Kind.REMOVED -> "-"
                    LineDiffer.Kind.CONTEXT -> "="
                }
            }
        }
        assertEquals(listOf("=", "gap(3)", "-", "+", "gap(4)", "="), kinds)
    }

    @Test
    fun `an oversized middle is reported as a block replace and flagged inexact`() {
        val old = lines(1100, "a")
        val new = lines(1100, "b")
        val result = LineDiffer.diff(old, new)
        assertFalse(result.exact)
        assertEquals(1100, result.added)
        assertEquals(1100, result.removed)
        assertEquals(0, result.unchanged)
    }

    @Test
    fun `counts agrees with diff without building rows`() {
        assertEquals(1 to 1, LineDiffer.counts("a\nb\nc", "a\nB\nc"))
        assertEquals(2 to 0, LineDiffer.counts("a", "a\nb\nc"))
        assertEquals(0 to 0, LineDiffer.counts("a\nb", "a\nb"))
    }

    // ------------------------------------------------------------ UnifiedDiff

    private val patch = """
        --- a/app/src/Main.kt
        +++ b/app/src/Main.kt
        @@ -1,4 +1,5 @@
         fun main() {
        -    println("hello")
        +    println("hello, world")
        +    println("second line")
         }
    """.trimIndent()

    @Test
    fun `a unified diff from a model is parsed into the same row shapes`() {
        val parsed = UnifiedDiff.parse(patch)
        assertTrue(parsed.valid)
        assertEquals("app/src/Main.kt", parsed.oldFile)
        assertEquals("app/src/Main.kt", parsed.newFile)
        assertEquals(1, parsed.hunks)
        assertEquals(2, parsed.added)
        assertEquals(1, parsed.removed)
        assertEquals("+2 −1", parsed.summary)

        assertEquals(5, parsed.entries.size)
        assertEquals(LineDiffer.Kind.CONTEXT, parsed.entries[0].kind)
        assertEquals("fun main() {", parsed.entries[0].text)
        assertEquals(1, parsed.entries[0].oldNumber)
        assertEquals(1, parsed.entries[0].newNumber)

        val removed = parsed.entries[1]
        assertEquals(LineDiffer.Kind.REMOVED, removed.kind)
        assertEquals("    println(\"hello\")", removed.text)
        assertEquals(2, removed.oldNumber)
        assertEquals(0, removed.newNumber)

        assertEquals(LineDiffer.Kind.ADDED, parsed.entries[2].kind)
        assertEquals(2, parsed.entries[2].newNumber)
        assertEquals(LineDiffer.Kind.ADDED, parsed.entries[3].kind)
        assertEquals(3, parsed.entries[3].newNumber)

        val closing = parsed.entries[4]
        assertEquals(LineDiffer.Kind.CONTEXT, closing.kind)
        assertEquals("}", closing.text)
        assertEquals(3, closing.oldNumber)
        assertEquals(4, closing.newNumber)
    }

    @Test
    fun `a blank line inside a hunk counts as context`() {
        val parsed = UnifiedDiff.parse("@@ -1,3 +1,3 @@\n a\n\n-b\n+B")
        assertEquals(4, parsed.entries.size)
        assertEquals(LineDiffer.Kind.CONTEXT, parsed.entries[1].kind)
        assertEquals("", parsed.entries[1].text)
        assertEquals(1, parsed.added)
        assertEquals(1, parsed.removed)
    }

    @Test
    fun `windows line endings and the no-newline marker are tolerated`() {
        val parsed = UnifiedDiff.parse("--- a/f\r\n+++ b/f\r\n@@ -1,2 +1,2 @@\r\n-x\r\n+X\r\n\\ No newline at end of file")
        assertEquals("f", parsed.oldFile)
        assertEquals("f", parsed.newFile)
        assertEquals(2, parsed.entries.size)
        assertEquals("x", parsed.entries[0].text)
        assertEquals("X", parsed.entries[1].text)
    }

    @Test
    fun `a diff with no changes is not valid`() {
        val parsed = UnifiedDiff.parse("--- a/f\n+++ b/f\n@@ -1,1 +1,1 @@\n same")
        assertFalse(parsed.valid)
        assertEquals("no changes", parsed.summary)
        assertEquals(0, parsed.added)
    }

    @Test
    fun `looksLikeDiff separates patches from source`() {
        assertTrue(UnifiedDiff.looksLikeDiff(patch))
        assertTrue(UnifiedDiff.looksLikeDiff("@@ -1,2 +1,2 @@\n-x\n+y"))
        assertFalse(UnifiedDiff.looksLikeDiff("val x = 1\nval y = 2"))
        assertFalse(UnifiedDiff.looksLikeDiff("- just a bullet\n- another"))
        assertFalse(UnifiedDiff.looksLikeDiff(""))
        assertFalse(UnifiedDiff.looksLikeDiff("@@ -1,2 +1,2 @@"))
    }

    @Test
    fun `multiple hunks are counted`() {
        val parsed = UnifiedDiff.parse(
            """
            --- a/f
            +++ b/f
            @@ -1,2 +1,2 @@
            -one
            +ONE
            @@ -40,2 +40,2 @@
            -forty
            +FORTY
            """.trimIndent(),
        )
        assertEquals(2, parsed.hunks)
        assertEquals(2, parsed.added)
        assertEquals(2, parsed.removed)
        assertEquals(40, parsed.entries.last().oldNumber)
    }

    @Test
    fun `a file header without the a and b prefixes is kept as written`() {
        val parsed = UnifiedDiff.parse("--- old.txt\n+++ new.txt\n@@ -1 +1 @@\n-a\n+b")
        assertEquals("old.txt", parsed.oldFile)
        assertEquals("new.txt", parsed.newFile)
        assertEquals(1, parsed.added)
    }
}
