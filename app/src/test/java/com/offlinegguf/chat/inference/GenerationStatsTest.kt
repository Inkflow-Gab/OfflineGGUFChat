package com.offlinegguf.chat.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationStatsTest {

    private fun stats(vararg tail: Long): GenerationStats = GenerationStats.from(
        longArrayOf(10, 20, 30, 1_000_000, 0, 5_000, 7_000, 9_000, 11_000, 20, 4, 10, 2048) + tail,
    )

    @Test
    fun `the prefill thread count comes from the slot native appends last`() {
        // Slots 13..15: decode threads, batch, prefill threads. They differ on
        // purpose — decode stays on the big cores, prefill borrows the rest.
        val stats = stats(2, 128, 7)
        assertEquals(2, stats.threads)
        assertEquals(128, stats.batch)
        assertEquals(7, stats.prefillThreads)
        assertEquals(20, stats.prefillTokens)
        assertEquals(4, stats.reusedTokens)
    }

    @Test
    fun `an older native build leaves the new slot at zero instead of guessing`() {
        val stats = stats(2, 128)
        assertEquals(0, stats.prefillThreads)
        assertEquals(2, stats.threads)
        assertEquals(128, stats.batch)
    }

    @Test
    fun `a five slot array still parses`() {
        val legacy = GenerationStats.from(longArrayOf(10, 20, 30, 2_000_000, 1))
        assertEquals(10, legacy.tokensGenerated)
        assertEquals(20, legacy.promptTokens)
        assertTrue(legacy.truncated)
        assertEquals(0, legacy.tokenizeMs)
        assertEquals(0, legacy.prefillThreads)
    }
}
