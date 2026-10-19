package com.offlinegguf.chat.inference

/**
 * Turns a token firehose into readable UI updates.
 *
 * A model emitting 20 tokens/second produces ~20 recompositions a second if each
 * one hits state directly. That is wasteful and, worse, it makes text appear to
 * twitch. This coalescer holds pieces back and releases them:
 *
 * - immediately when a newline arrives (paragraph/code structure should not lag),
 * - when [flushChars] have accumulated,
 * - or when [flushMillis] have elapsed since the last release.
 *
 * The clock is injectable so the behaviour is unit testable without sleeping.
 */
class StreamCoalescer(
    private val flushMillis: Long = 256L,
    private val flushChars: Int = 48,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val buffer = StringBuilder()
    private var lastRelease: Long = clock()

    /** Number of characters currently held back. */
    val pending: Int get() = buffer.length

    /** Add a piece; returns text that should be rendered now, or null to wait. */
    fun offer(text: String): String? {
        if (text.isEmpty()) return null
        buffer.append(text)
        return if (shouldFlush()) release() else null
    }

    private fun shouldFlush(): Boolean {
        if (buffer.isEmpty()) return false
        // Newlines carry structure: a list item, a code line, a paragraph break.
        // Waiting 250 ms for those makes the answer feel laggy.
        if (buffer.indexOf('\n') >= 0) return true
        if (buffer.length >= flushChars) return true
        return clock() - lastRelease >= flushMillis
    }

    /** Force everything out (end of generation, pause, screen change). */
    fun release(): String? {
        if (buffer.isEmpty()) return null
        val out = buffer.toString()
        buffer.setLength(0)
        lastRelease = clock()
        return out
    }

    fun reset() {
        buffer.setLength(0)
        lastRelease = clock()
    }
}
