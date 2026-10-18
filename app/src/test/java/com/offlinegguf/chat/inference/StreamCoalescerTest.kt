package com.offlinegguf.chat.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamCoalescerTest {

    /** A clock we move by hand: no sleeping in unit tests. */
    private class FakeClock {
        var now = 0L
        val read: () -> Long get() = { now }
    }

    @Test
    fun `a newline flushes immediately because it carries structure`() {
        val clock = FakeClock()
        val coalescer = StreamCoalescer(flushMillis = 5000, flushChars = 500, clock = clock.read)
        assertEquals("line one\n", coalescer.offer("line one\n"))
        assertEquals(0, coalescer.pending)
    }

    @Test
    fun `small pieces are held until they are worth a repaint`() {
        val clock = FakeClock()
        val coalescer = StreamCoalescer(flushMillis = 5000, flushChars = 10, clock = clock.read)
        assertNull(coalescer.offer("abc"))
        assertEquals(3, coalescer.pending)
        assertNull(coalescer.offer("def"))
        assertEquals(6, coalescer.pending)
        assertEquals("abcdefghij", coalescer.offer("ghij"))
        assertEquals(0, coalescer.pending)
    }

    @Test
    fun `elapsed time alone flushes`() {
        val clock = FakeClock()
        val coalescer = StreamCoalescer(flushMillis = 100, flushChars = 1000, clock = clock.read)
        assertNull(coalescer.offer("abc"))
        clock.now = 99
        assertNull("not yet", coalescer.offer("d"))
        clock.now = 150
        assertEquals("abcde", coalescer.offer("e"))
        // The release reset the timer, so the next piece waits again.
        assertNull(coalescer.offer("f"))
    }

    @Test
    fun `release forces the tail out and is idempotent when empty`() {
        val clock = FakeClock()
        val coalescer = StreamCoalescer(flushMillis = 5000, flushChars = 500, clock = clock.read)
        assertNull(coalescer.release())
        coalescer.offer("partial")
        assertEquals("partial", coalescer.release())
        assertNull(coalescer.release())
    }

    @Test
    fun `an empty offer never flushes`() {
        val clock = FakeClock()
        val coalescer = StreamCoalescer(flushMillis = 0, flushChars = 1, clock = clock.read)
        assertNull(coalescer.offer(""))
        assertEquals(0, coalescer.pending)
    }

    @Test
    fun `reset drops what was held and restarts the clock`() {
        val clock = FakeClock()
        val coalescer = StreamCoalescer(flushMillis = 100, flushChars = 500, clock = clock.read)
        coalescer.offer("held")
        assertEquals(4, coalescer.pending)
        coalescer.reset()
        assertEquals(0, coalescer.pending)
        assertNull(coalescer.release())
        // The clock restarted, so 99 ms later nothing is due yet.
        clock.now = 99
        assertNull(coalescer.offer("again"))
        clock.now = 100
        assertEquals("againx", coalescer.offer("x"))
    }

    @Test
    fun `everything offered eventually comes out, in order`() {
        val clock = FakeClock()
        val coalescer = StreamCoalescer(flushMillis = 250, flushChars = 32, clock = clock.read)
        val pieces = listOf("The ", "answer ", "is ", "forty-", "two.\n", "Done ", "now.")
        val rendered = StringBuilder()
        pieces.forEachIndexed { index, piece ->
            clock.now = index * 40L
            coalescer.offer(piece)?.let { rendered.append(it) }
        }
        coalescer.release()?.let { rendered.append(it) }
        assertEquals(pieces.joinToString(""), rendered.toString())
    }
}
