package com.offlinegguf.chat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure-Kotlin twin of `ogg::CompleteUtf8Length` in `app/cpp/util/utf8.h`.
 *
 * `app/cpp/tests/utf8_test.cpp` asserts the same table on the C++ side, so the two
 * framers cannot drift apart without a build failing somewhere.
 */
class StreamingUtf8DecoderTest {

    private fun bytes(vararg values: Int) =
        ByteArray(values.size) { values[it].toByte() }

    private fun complete(vararg values: Int) =
        StreamingUtf8Decoder.completeUtf8Length(bytes(*values), 0, values.size)

    @Test
    fun `ascii is always complete`() {
        assertEquals(0, StreamingUtf8Decoder.completeUtf8Length(ByteArray(0)))
        val hello = "hello".toByteArray()
        assertEquals(5, StreamingUtf8Decoder.completeUtf8Length(hello))
        assertEquals(3, StreamingUtf8Decoder.completeUtf8Length(hello, 0, 3))
    }

    @Test
    fun `multi-byte sequences are held until whole`() {
        assertEquals(2, complete(0xC3, 0xA9))            // é
        assertEquals(0, complete(0xC3))                  // half of é
        assertEquals(3, complete(0xE2, 0x82, 0xAC))      // €
        assertEquals(0, complete(0xE2))
        assertEquals(0, complete(0xE2, 0x82))
        assertEquals(4, complete(0xF0, 0x9F, 0x98, 0x80)) // 😀
        assertEquals(0, complete(0xF0))
        assertEquals(0, complete(0xF0, 0x9F))
        assertEquals(0, complete(0xF0, 0x9F, 0x98))
    }

    @Test
    fun `text before a split sequence is released immediately`() {
        val emoji = bytes(0xF0, 0x9F, 0x98, 0x80)
        val whole = "hi ".toByteArray() + emoji + " there".toByteArray()
        assertEquals(whole.size, StreamingUtf8Decoder.completeUtf8Length(whole))
        // "hi " plus the first byte of the emoji: only the three ASCII bytes go.
        assertEquals(3, StreamingUtf8Decoder.completeUtf8Length(whole, 0, 4))
        assertEquals(8, StreamingUtf8Decoder.completeUtf8Length(whole, 0, 8))
    }

    @Test
    fun `damaged bytes are consumed so the stream cannot stall`() {
        assertEquals(2, complete(0x80, 0x41))   // stray continuation, then 'A'
        assertEquals(2, complete(0xFF, 0x41))   // invalid lead byte
        assertEquals(2, complete(0xC3, 0x41))   // 2-byte lead with an ASCII tail
        assertEquals(0, complete(0xE2, 0x82))   // genuinely truncated: keep it
    }

    @Test
    fun `a window with an offset is framed correctly`() {
        val text = "xéy".toByteArray()          // 78 C3 A9 79
        val decoder = StreamingUtf8Decoder()
        assertEquals("é", decoder.feed(text, 1, 2))
        assertFalse(decoder.hasPending)
    }

    @Test
    fun `feed reassembles a stream delivered in odd-sized pieces`() {
        val original = "The answer is 😀 — done.\nLine two: é€"
        val bytes = original.toByteArray()
        val rebuilt = StringBuilder()
        for (size in intArrayOf(1, 2, 3, 5, 7, 11, 13)) {
            val decoder = StreamingUtf8Decoder()
            rebuilt.setLength(0)
            var offset = 0
            while (offset < bytes.size) {
                val take = minOf(size, bytes.size - offset)
                rebuilt.append(decoder.feed(bytes, offset, take))
                offset += take
            }
            rebuilt.append(decoder.flush())
            assertEquals("chunk size $size", original, rebuilt.toString())
            assertFalse(decoder.hasPending)
        }
    }

    @Test
    fun `flush turns a truncated tail into a replacement character`() {
        val decoder = StreamingUtf8Decoder()
        assertEquals("", decoder.feed(bytes(0xF0, 0x9F)))
        assertTrue(decoder.hasPending)
        val flushed = decoder.flush()
        assertTrue(flushed.isNotEmpty())
        assertFalse(decoder.hasPending)
        assertEquals("", decoder.flush())
    }

    @Test
    fun `reset drops what was pending`() {
        val decoder = StreamingUtf8Decoder()
        decoder.feed(bytes(0xC3))
        assertTrue(decoder.hasPending)
        decoder.reset()
        assertFalse(decoder.hasPending)
        assertEquals("", decoder.flush())
        // After a reset the decoder still works normally.
        assertEquals("ok", decoder.feed("ok".toByteArray()))
    }

    @Test
    fun `the string overload matches the byte overload`() {
        assertEquals("héllo", StreamingUtf8Decoder().feed("héllo"))
    }

    @Test
    fun `invalid bytes surface as replacement characters rather than vanishing`() {
        val out = StreamingUtf8Decoder().feed(bytes(0x80, 0x41))
        assertEquals(2, out.length)
        assertEquals('\uFFFD', out[0])
        assertEquals('A', out[1])
    }
}
