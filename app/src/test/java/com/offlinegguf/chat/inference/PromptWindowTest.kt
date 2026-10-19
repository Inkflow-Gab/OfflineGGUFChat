package com.offlinegguf.chat.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptWindowTest {
    @Test
    fun `short conversations keep all turns`() {
        val turns = listOf(
            ChatTurn("user", "Hi"),
            ChatTurn("assistant", "Hello"),
            ChatTurn("user", "How are you?"),
        )
        val result = PromptWindow.fit("Be helpful", turns, contextTokens = 4096, outputTokens = 512)
        assertEquals(turns, result.turns)
        assertEquals(0, result.droppedTurns)
        assertFalse(result.clippedLatestTurn)
    }

    @Test
    fun `long transcripts keep the recent tail and report omitted turns`() {
        val turns = (0 until 40).flatMap { index ->
            listOf(ChatTurn("user", "Question $index " + "old ".repeat(150)), ChatTurn("assistant", "Answer $index " + "text ".repeat(150)))
        }
        val result = PromptWindow.fit("Be concise", turns, contextTokens = 1024, outputTokens = 256)
        assertTrue(result.droppedTurns > 0)
        assertTrue(result.turns.size < turns.size)
        assertEquals("user", result.turns.first().role)
        assertTrue(result.turns.last().content.contains("Answer 39"))
        assertFalse(result.clippedLatestTurn)
    }

    @Test
    fun `an oversized newest turn is clipped while preserving its beginning and ending`() {
        val newest = "Question and start " + "middle ".repeat(5_000) + "important ending"
        val result = PromptWindow.fit(
            system = "Be careful",
            turns = listOf(ChatTurn("user", newest)),
            contextTokens = 512,
            outputTokens = 512,
        )
        assertTrue(result.clippedLatestTurn)
        assertTrue(result.turns.single().content.startsWith("Question and start"))
        assertTrue(result.turns.single().content.endsWith("important ending"))
        assertTrue(result.turns.single().content.contains("omitted to fit"))
    }
}
