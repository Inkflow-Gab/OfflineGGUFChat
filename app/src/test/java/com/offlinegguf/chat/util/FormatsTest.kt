package com.offlinegguf.chat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatsTest {

    @Test
    fun `bytes uses binary units and drops the decimal above 100`() {
        assertEquals("—", Formats.bytes(-1))
        assertEquals("0 B", Formats.bytes(0))
        assertEquals("1023 B", Formats.bytes(1023))
        assertEquals("1.0 KB", Formats.bytes(1024))
        assertEquals("1.5 KB", Formats.bytes(1536))
        assertEquals("1.0 MB", Formats.bytes(1024L * 1024))
        assertEquals("5.0 GB", Formats.bytes(5L * 1024 * 1024 * 1024))
        // 150 GB has no room for a decimal; the fixed width keeps columns aligned.
        assertEquals("150 GB", Formats.bytes(150L * 1024 * 1024 * 1024))
        assertEquals("3.00 GB", Formats.gigabytes(3L * 1024 * 1024 * 1024))
        assertEquals("500 MB", Formats.gigabytesOrBytes(500L * 1024 * 1024))
        assertEquals("2.00 GB", Formats.gigabytesOrBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `parameter counts read like model cards`() {
        assertEquals("—", Formats.paramCount(0))
        assertEquals("—", Formats.paramCount(-5))
        assertEquals("8.03B", Formats.paramCount(8_030_000_000))
        assertEquals("1.50B", Formats.paramCount(1_500_000_000))
        assertEquals("723.0M", Formats.paramCount(723_000_000))
        assertEquals("999999", Formats.paramCount(999_999))
    }

    @Test
    fun `context sizes collapse to k`() {
        assertEquals("—", Formats.contextTokens(0))
        assertEquals("512", Formats.contextTokens(512))
        assertEquals("999", Formats.contextTokens(999))
        assertEquals("8k", Formats.contextTokens(8000))
        assertEquals("4.1k", Formats.contextTokens(4096))
        assertEquals("131.1k", Formats.contextTokens(131072))
    }

    @Test
    fun `speed and duration`() {
        assertEquals("—", Formats.tokensPerSecond(0, 1000))
        assertEquals("—", Formats.tokensPerSecond(10, 0))
        assertEquals("5.0 tok/s", Formats.tokensPerSecond(10, 2_000_000))
        assertEquals("1.5s", Formats.seconds(1_500_000))
        assertEquals("2m 05s", Formats.seconds(125_000_000))
        assertEquals("—", Formats.seconds(0))
        assertEquals("50%", Formats.percent(0.5f))
        assertEquals("4.00 bpw", Formats.bitsPerWeight(4_000_000_000, 8_000_000_000))
        assertEquals("—", Formats.bitsPerWeight(0, 8_000_000_000))
    }

    @Test
    fun `fixed honours the digit count and clamps it`() {
        assertEquals("1.23", Formats.fixed(1.2345, 2))
        assertEquals("1.2", Formats.fixed(1.2345, 1))
        assertEquals("1", Formats.fixed(1.2345, 0))
        assertEquals("1.234500", Formats.fixed(1.2345, 9))
        assertEquals("1", Formats.fixed(1.2345, -3))
    }

    @Test
    fun `relative time is coarse on purpose`() {
        val now = 1_700_000_000_000L
        assertEquals("just now", Formats.relativeTime(now + 60_000, now))
        assertEquals("just now", Formats.relativeTime(now - 30_000, now))
        assertEquals("1m ago", Formats.relativeTime(now - 90_000, now))
        assertEquals("59m ago", Formats.relativeTime(now - 59 * 60_000, now))
        assertEquals("3h ago", Formats.relativeTime(now - 3 * 3_600_000, now))
        assertEquals("2d ago", Formats.relativeTime(now - 2 * 86_400_000, now))
        assertEquals("1w ago", Formats.relativeTime(now - 10 * 86_400_000, now))
        assertEquals("1mo ago", Formats.relativeTime(now - 40 * 86_400_000, now))
        assertEquals("1y ago", Formats.relativeTime(now - 400 * 86_400_000, now))
    }

    @Test
    fun `preview flattens whitespace and ellipsises`() {
        assertEquals("a b", Formats.preview("a\n\n  b  "))
        assertEquals("short", Formats.preview("short"))
        val long = "x".repeat(200)
        val preview = Formats.preview(long, limit = 20)
        assertEquals(20, preview.length)
        assertTrue(preview.endsWith("…"))
    }
}
