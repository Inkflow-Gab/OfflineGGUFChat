package com.offlinegguf.chat.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript file name comes from a conversation title, which is arbitrary
 * user text — including titles a file manager handed us, or one someone typed as
 * "../../etc/passwd". These run on the JVM: only the pure name logic, not the
 * Context-touching share builder.
 */
class TranscriptShareTest {

    @Test
    fun `a title becomes a readable file name`() {
        assertEquals("Weekly-plans.md", transcriptFileName("Weekly plans", "md"))
        assertEquals("Q4-report.json", transcriptFileName("  Q4   report ", "json"))
    }

    @Test
    fun `an empty or unusable title falls back to a real name`() {
        assertEquals("transcript.json", transcriptFileName("", "json"))
        assertEquals("transcript.md", transcriptFileName("   ", "md"))
        assertEquals("transcript.md", transcriptFileName("...", "md"))
        assertEquals("transcript.md", transcriptFileName("///", "md"))
    }

    @Test
    fun `a title cannot escape the cache directory`() {
        val name = transcriptFileName("../../etc/passwd", "md")
        assertEquals("etc-passwd.md", name)
        assertFalse(name, name.contains("/"))
        assertFalse(name, name.contains("\\"))
        assertFalse(name, name.startsWith("."))
    }

    @Test
    fun `punctuation and emoji are replaced, not carried through`() {
        assertEquals("Bug-1234-crash-on-open.md", transcriptFileName("Bug #1234: crash on open!", "md"))
        assertEquals("Notes.md", transcriptFileName("Notes \uD83D\uDD25", "md"))
    }

    @Test
    fun `a very long title is capped`() {
        val name = transcriptFileName("x".repeat(400), "md")
        assertTrue("was ${name.length} chars", name.length <= 84)
        assertTrue(name, name.endsWith(".md"))
    }
}
