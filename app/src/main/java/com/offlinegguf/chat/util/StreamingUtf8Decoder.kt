package com.offlinegguf.chat.util

/**
 * Reassembles UTF-8 code points that arrive split across chunks.
 *
 * llama.cpp streams one tokenizer piece at a time and a piece may end in the
 * middle of a multi-byte sequence (the first two bytes of an emoji, say). The
 * native layer already reframes with `ogg::CompleteUtf8Length`; this decoder is
 * the pure-Kotlin twin of that function so the same rule can be unit tested on
 * a JVM and so text arriving from other producers behaves identically.
 *
 * Usage:
 * ```
 * val decoder = StreamingUtf8Decoder()
 * decoder.feed(bytes)   // -> the part that is safe to render
 * decoder.flush()       // -> "" normally, replacement chars after a hard stop
 * ```
 */
class StreamingUtf8Decoder {

    private var pending = ByteArray(4)
    private var pendingCount = 0

    /** True while at least one byte is being held back for the next chunk. */
    val hasPending: Boolean get() = pendingCount > 0

    fun reset() {
        pendingCount = 0
    }

    /**
     * Returns the text that is complete up to the last whole code point. Any
     * trailing partial sequence is buffered until the next [feed] call.
     */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): String {
        if (length <= 0) return ""

        // Work on a window that starts with whatever was left over last time.
        val combined: ByteArray
        val combinedLength: Int
        if (pendingCount == 0) {
            combined = bytes
            combinedLength = length
        } else {
            combined = ByteArray(pendingCount + length)
            pending.copyInto(combined, 0, 0, pendingCount)
            bytes.copyInto(combined, pendingCount, offset, offset + length)
            combinedLength = combined.size
        }
        val start = if (pendingCount == 0) offset else 0
        val end = start + combinedLength

        // `complete` is an index into `combined`, so all arithmetic below is
        // absolute; that keeps a non-zero offset honest.
        val complete = completeUtf8Length(combined, start, combinedLength)
        val usable = complete - start
        if (usable <= 0) {
            keepTail(combined, start, end)
            return ""
        }

        val out = String(combined, start, usable, Charsets.UTF_8)
        val rest = end - complete
        if (rest > 0) {
            pendingCount = rest.coerceAtMost(pending.size)
            combined.copyInto(pending, 0, complete, complete + pendingCount)
        } else {
            pendingCount = 0
        }
        return out
    }

    fun feed(text: String): String = feed(text.toByteArray(Charsets.UTF_8))

    /**
     * Called when the stream ends. A truncated tail is not a valid code point,
     * so it becomes replacement characters rather than silently vanishing —
     * that matches what the platform decoder would have done anyway.
     */
    fun flush(): String {
        if (pendingCount == 0) return ""
        val out = String(pending, 0, pendingCount, Charsets.UTF_8)
        pendingCount = 0
        return out
    }

    /** Nothing in this window was usable, so hold the whole tail for next time. */
    private fun keepTail(bytes: ByteArray, start: Int, end: Int) {
        val tail = end - start
        pendingCount = tail.coerceAtMost(pending.size)
        if (pendingCount > 0) bytes.copyInto(pending, 0, end - pendingCount, end)
    }

    companion object {

        /**
         * Number of leading bytes of `data[start until start + length]` that form
         * complete UTF-8 code points. Byte-for-byte the same walk as
         * `ogg::CompleteUtf8Length` in `app/cpp/util/utf8.h`.
         */
        fun completeUtf8Length(data: ByteArray, start: Int = 0, length: Int = data.size): Int {
            if (length <= 0) return start
            val end = start + length
            var i = start
            var lastComplete = start
            while (i < end) {
                val c = data[i].toInt() and 0xFF
                val len = when {
                    c and 0x80 == 0 -> 1
                    c and 0xE0 == 0xC0 -> 2
                    c and 0xF0 == 0xE0 -> 3
                    c and 0xF8 == 0xF0 -> 4
                    else -> {
                        // Stray continuation byte or invalid lead: consume it so a
                        // single bad piece cannot stall the stream forever.
                        i++
                        lastComplete = i
                        continue
                    }
                }
                if (i + len > end) break // truncated tail -> keep for next chunk
                var valid = true
                for (k in 1 until len) {
                    if (data[i + k].toInt() and 0xC0 != 0x80) {
                        valid = false
                        break
                    }
                }
                if (!valid) {
                    i++
                    lastComplete = i
                    continue
                }
                i += len
                lastComplete = i
            }
            return lastComplete
        }
    }
}
