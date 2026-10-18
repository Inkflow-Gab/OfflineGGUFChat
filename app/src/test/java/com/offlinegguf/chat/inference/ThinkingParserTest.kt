package com.offlinegguf.chat.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingParserTest {

    @Test
    fun `plain text goes straight to the answer`() {
        val parser = ThinkingParser()
        val out = parser.offer("just an answer")
        assertEquals("", out.thinking)
        assertEquals("just an answer", out.answer)
        assertEquals(ThinkingParser.Channel.ANSWER, out.channel)
        assertFalse(out.sawThinking)
        assertFalse(parser.hasSeenThinking)
        assertEquals(0, parser.pending)
    }

    @Test
    fun `an empty offer changes nothing`() {
        val parser = ThinkingParser()
        val out = parser.offer("")
        assertTrue(out.isEmpty)
        assertEquals(ThinkingParser.Channel.ANSWER, out.channel)
    }

    @Test
    fun `a whole reasoning block is split from the answer`() {
        val parser = ThinkingParser()
        val out = parser.offer("<think>plan</think>answer")
        assertEquals("plan", out.thinking)
        assertEquals("answer", out.answer)
        assertEquals(ThinkingParser.Channel.ANSWER, out.channel)
        assertTrue(out.sawThinking)
        assertFalse(parser.unclosed)
    }

    @Test
    fun `a marker split across pieces is held back, not shown`() {
        val parser = ThinkingParser()
        val first = parser.offer("answer so far<thi")
        assertEquals("answer so far", first.answer)
        assertEquals(3, parser.pending)

        val second = parser.offer("nk>the plan</think>done")
        assertEquals("the plan", second.thinking)
        assertEquals("done", second.answer)
        assertEquals(0, parser.pending)
    }

    @Test
    fun `a marker prefix that never completes is ordinary text`() {
        val parser = ThinkingParser()
        assertEquals("answer", parser.offer("answer<th").answer)
        val tail = parser.finish()
        assertEquals("<th", tail.answer)
        assertEquals("", tail.thinking)
        assertEquals(0, parser.pending)
        assertEquals("", parser.finish().answer)
    }

    @Test
    fun `an unclosed block leaves the parser in reasoning`() {
        val parser = ThinkingParser()
        val out = parser.offer("<think>still going")
        assertEquals("still going", out.thinking)
        assertEquals("", out.answer)
        assertEquals(ThinkingParser.Channel.THINKING, out.channel)
        assertTrue(parser.unclosed)
        // Text keeps going to reasoning until something closes the block.
        val more = parser.offer(" and more")
        assertEquals(" and more", more.thinking)
        assertEquals("", more.answer)
    }

    @Test
    fun `a mismatched close tag still ends the block`() {
        val parser = ThinkingParser()
        val out = parser.offer("<thinking>plan</think>answer")
        assertEquals("plan", out.thinking)
        assertEquals("answer", out.answer)
        assertFalse(parser.unclosed)
    }

    @Test
    fun `every supported marker is understood`() {
        val cases = listOf(
            "<think>" to "</think>",
            "<thinking>" to "</thinking>",
            "<|thinking|>" to "<|/thinking|>",
            "<reasoning>" to "</reasoning>",
            "<|reasoning|>" to "<|/reasoning|>",
            "<|channel|>analysis<|message|>" to "<|end|>",
        )
        for ((open, close) in cases) {
            val parser = ThinkingParser()
            val out = parser.offer("${open}reasoning${close}answer")
            assertEquals("for $open", "reasoning", out.thinking)
            assertEquals("for $open", "answer", out.answer)
            assertTrue("for $open", out.sawThinking)
        }
    }

    @Test
    fun `the toggle marker flips the channel both ways`() {
        val parser = ThinkingParser()
        val out = parser.offer("***/reasoning***/answer")
        assertEquals("reasoning", out.thinking)
        assertEquals("answer", out.answer)
        assertEquals(ThinkingParser.Channel.ANSWER, out.channel)
    }

    @Test
    fun `multiple reasoning blocks accumulate`() {
        val parser = ThinkingParser()
        val first = parser.offer("<think>one</think>first")
        val second = parser.offer("<think>two</think>second")
        assertEquals("one", first.thinking)
        assertEquals("first", first.answer)
        assertEquals("two", second.thinking)
        assertEquals("second", second.answer)
    }

    @Test
    fun `reset starts over`() {
        val parser = ThinkingParser()
        parser.offer("<think>half")
        assertTrue(parser.unclosed)
        parser.reset()
        assertFalse(parser.unclosed)
        assertFalse(parser.hasSeenThinking)
        assertEquals(0, parser.pending)
        assertEquals(ThinkingParser.Channel.ANSWER, parser.currentChannel)
        assertEquals("plain", parser.offer("plain").answer)
    }

    @Test
    fun `the one-shot splitter trims and reports absent reasoning as null`() {
        assertEquals("plan" to "answer", ThinkingParser.split("<think>plan</think>answer"))
        val (reasoning, answer) = ThinkingParser.split("no reasoning here")
        assertNull(reasoning)
        assertEquals("no reasoning here", answer)
        val (trimmedReasoning, trimmedAnswer) = ThinkingParser.split("<think>  plan </think>  answer  ")
        assertEquals("plan", trimmedReasoning)
        assertEquals("answer", trimmedAnswer)
    }

    @Test
    fun `looksLikeReasoning only matches real markers`() {
        assertTrue(ThinkingParser.looksLikeReasoning("<think>x</think>"))
        assertTrue(ThinkingParser.looksLikeReasoning("<THINKING>x"))
        assertTrue(ThinkingParser.looksLikeReasoning("<reasoning>x"))
        assertTrue(ThinkingParser.looksLikeReasoning("a***/b"))
        assertFalse(ThinkingParser.looksLikeReasoning("a plain answer"))
        assertFalse(ThinkingParser.looksLikeReasoning("code with < and > signs"))
    }
}
