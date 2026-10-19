package com.offlinegguf.chat.inference

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The regression test asked for by the bug report: a short prompt must never
 * silently spend minutes before the first visible character.
 *
 * [InferenceTrace.audit] is the pure rule used both in the in-app
 * "Generation details" sheet and in the `InferenceTrace` logcat line. These
 * tests replay the shapes of real runs (a healthy reply, a hidden reasoning
 * block, a stalled engine) and pin the verdicts.
 */
class InferenceTraceAuditTest {

    /** The reported failure: "hello", ~3 minutes, ~6 answer tokens at the end. */
    private val reportedBug = InferenceTrace(
        promptBuildMs = 1,
        queueMs = 0,
        tokenizeMs = 2,
        prefillMs = 120,
        firstTokenMs = 300,
        firstRenderMs = 182_000,
        totalMs = 184_000,
        tokensGenerated = 512,
        prefillTokens = 28,
        contextSize = 4096,
        threads = 1,
        batch = 128,
        promptChars = 96,
        estimatedPromptTokens = 28,
        reasoningChars = 9_800,
        answerChars = 26,
        modelLoads = 1,
    )

    @Test
    fun `minutes before the first answer token is reported as hidden reasoning`() {
        val verdict = InferenceTrace.audit(reportedBug)
        assertNotNull(verdict)
        assertTrue(verdict!!.contains("reasoning", ignoreCase = true))
        assertTrue(verdict.contains("turn Reasoning off", ignoreCase = true))
    }

    @Test
    fun `reasoning share is computed from characters, not from the label`() {
        // ~tokens via chars/4; the model produced much more thinking than answer.
        assertTrue(reportedBug.reasoningShare > 0.99f)
        assertTrue(reportedBug.reasoningTokens > reportedBug.tokensGenerated / 2)
    }

    @Test
    fun `a healthy short reply is not flagged`() {
        val healthy = reportedBug.copy(
            firstRenderMs = 900,
            totalMs = 1_400,
            tokensGenerated = 12,
            reasoningChars = 0,
            answerChars = 48,
            threads = 4,
        )
        assertNull(InferenceTrace.audit(healthy))
    }

    @Test
    fun `a generation that produced nothing blames the engine, not the model`() {
        val stalled = reportedBug.copy(
            tokensGenerated = 0,
            reasoningChars = 0,
            answerChars = 0,
            firstTokenMs = 0,
            totalMs = 150_000,
        )
        val verdict = InferenceTrace.audit(stalled)
        assertNotNull(verdict)
        assertTrue(verdict!!.contains("No token was generated"))
    }

    @Test
    fun `slow decode is distinguished from a slow prompt`() {
        val slowPhone = reportedBug.copy(
            firstRenderMs = 40_000,
            totalMs = 41_000,
            tokensGenerated = 30,
            reasoningChars = 0,
            answerChars = 120,
            prefillMs = 120,
            threads = 2,
        )
        val verdict = InferenceTrace.audit(slowPhone)
        assertNotNull(verdict)
        assertTrue(verdict!!.contains("tok/s"))
    }

    @Test
    fun `log line carries the numbers needed in a bug report`() {
        val line = reportedBug.line()
        listOf(
            "prompt=28tok", "prefill=120ms", "ttft=300ms", "firstRender=182000ms",
            "reasoning=", "threads=1", "loads=1", "backend=llama.cpp",
        ).forEach { assertTrue("missing $it in: $line", line.contains(it)) }
    }
}
