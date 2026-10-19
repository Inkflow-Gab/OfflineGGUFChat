package com.offlinegguf.chat.inference

/**
 * Separates a model's reasoning from its answer while the text is still streaming.
 *
 * Reasoning models wrap their scratch work in markers such as `<think>`,
 * `<thinking>`, `<reasoning>` and `<|reasoning|>`; a few builds instead use one
 * triple-asterisk marker that flips the channel every time it appears. Showing
 * that inline makes an answer unreadable, and hiding it throws away the
 * interesting part. So the transcript keeps both, in two channels, and the UI
 * renders reasoning as a collapsible block above the answer.
 *
 * The parser is incremental and holds back any trailing text that could be the
 * start of a marker, so a tag split across two token pieces never leaks into the
 * answer.
 */
class ThinkingParser {

    enum class Channel { THINKING, ANSWER }

    data class Output(
        val thinking: String,
        val answer: String,
        val channel: Channel,
        val sawThinking: Boolean,
    ) {
        val isEmpty: Boolean get() = thinking.isEmpty() && answer.isEmpty()
    }

    private data class Marker(val open: String, val close: String)

    private val markers = listOf(
        Marker("<think>", "</think>"),
        // Qwen3 opens its block with "<think" and a newline; there is no ">".
        // Matching the newline keeps the tag fully consumed, which a bare
        // "<think" prefix does not: it would leave the ">" in the answer.
        Marker("<think\n", "</think>"),
        Marker("<thinking>", "</thinking>"),
        Marker("<|thinking|>", "<|/thinking|>"),
        Marker("<reasoning>", "</reasoning>"),
        Marker("<|reasoning|>", "<|/reasoning|>"),
        Marker("<|channel|>analysis<|message|>", "<|end|>"),
    )

    /** Some builds use a single marker that flips the channel each time. */
    private val toggle = "***/"

    private val buffer = StringBuilder()
    private var channel = Channel.ANSWER
    private var active: Marker? = null
    private var sawThinking = false

    val currentChannel: Channel get() = channel
    val hasSeenThinking: Boolean get() = sawThinking
    val pending: Int get() = buffer.length

    fun reset() {
        buffer.setLength(0)
        channel = Channel.ANSWER
        active = null
        sawThinking = false
    }

    fun offer(text: String): Output {
        if (text.isEmpty()) return Output("", "", channel, sawThinking)
        buffer.append(text)

        val thinking = StringBuilder()
        val answer = StringBuilder()

        while (buffer.isNotEmpty()) {
            val candidates = candidatesFor(channel)
            val match = earliestMatch(buffer, candidates)
            if (match == null) {
                val held = partialTail(buffer, candidates)
                val emitUntil = buffer.length - held
                if (emitUntil > 0) {
                    append(thinking, answer, buffer.substring(0, emitUntil))
                    buffer.delete(0, emitUntil)
                }
                break
            }
            if (match.index > 0) {
                append(thinking, answer, buffer.substring(0, match.index))
                buffer.delete(0, match.index)
            }
            buffer.delete(0, match.text.length)
            switch(match.text)
        }

        return Output(thinking.toString(), answer.toString(), channel, sawThinking)
    }

    /**
     * End of generation: whatever is still held back belongs to the current
     * channel (a marker prefix that never completed was just ordinary text).
     */
    fun finish(): Output {
        val thinking = StringBuilder()
        val answer = StringBuilder()
        if (buffer.isNotEmpty()) {
            append(thinking, answer, buffer.toString())
            buffer.setLength(0)
        }
        return Output(thinking.toString(), answer.toString(), channel, sawThinking)
    }

    /** True when reasoning was opened and never closed. */
    val unclosed: Boolean get() = channel == Channel.THINKING

    private fun append(thinking: StringBuilder, answer: StringBuilder, text: String) {
        if (channel == Channel.THINKING) thinking.append(text) else answer.append(text)
    }

    private fun candidatesFor(channel: Channel): List<String> = when (channel) {
        Channel.ANSWER -> markers.map { it.open } + toggle
        // Prefer the marker that actually opened this block, but accept any close
        // tag: models mix them up, and getting stuck in THINKING is worse.
        Channel.THINKING -> {
            val specific = active?.close
            if (specific != null) listOf(specific) + markers.map { it.close } + toggle
            else markers.map { it.close } + toggle
        }
    }

    private data class Match(val index: Int, val text: String)

    private fun earliestMatch(haystack: CharSequence, candidates: List<String>): Match? {
        var best: Match? = null
        for (candidate in candidates) {
            if (candidate.isEmpty()) continue
            val index = haystack.indexOfLiteral(candidate)
            val current = best
            if (index < 0) continue
            if (current == null || index < current.index ||
                (index == current.index && candidate.length > current.text.length)
            ) {
                best = Match(index, candidate)
            }
        }
        return best
    }

    private fun switch(matched: String) {
        if (matched == toggle) {
            channel = if (channel == Channel.ANSWER) Channel.THINKING else Channel.ANSWER
            if (channel == Channel.THINKING) sawThinking = true
            active = null
            return
        }
        if (channel == Channel.ANSWER) {
            active = markers.firstOrNull { it.open == matched }
            channel = Channel.THINKING
            sawThinking = true
        } else {
            channel = Channel.ANSWER
            active = null
        }
    }

    /**
     * Length of the trailing run in [text] that is a proper prefix of some
     * candidate marker. Held back rather than emitted, because the next piece may
     * complete the tag.
     */
    private fun partialTail(text: CharSequence, candidates: List<String>): Int {
        var best = 0
        for (candidate in candidates) {
            val maxLen = minOf(candidate.length - 1, text.length)
            for (len in maxLen downTo 1) {
                if (len <= best) break
                if (regionMatches(text, text.length - len, candidate, 0, len)) {
                    if (len > best) best = len
                    break
                }
            }
        }
        return best
    }

    private fun regionMatches(
        source: CharSequence,
        sourceStart: Int,
        target: String,
        targetStart: Int,
        length: Int,
    ): Boolean {
        if (sourceStart < 0 || sourceStart + length > source.length) return false
        for (i in 0 until length) {
            if (source[sourceStart + i] != target[targetStart + i]) return false
        }
        return true
    }

    companion object {
        /**
         * One-shot split for text that is already complete (rendering history,
         * re-parsing a saved message). Returns (reasoning, answer); reasoning is
         * null when the message had none.
         */
        fun split(full: String): Pair<String?, String> {
            val parser = ThinkingParser()
            val out = parser.offer(full)
            val tail = parser.finish()
            val thinking = (out.thinking + tail.thinking).trim()
            val answer = out.answer + tail.answer
            return (if (thinking.isEmpty()) null else thinking) to answer.trim()
        }

        /** True when a block of text contains any known reasoning marker. */
        fun looksLikeReasoning(text: String): Boolean {
            val lower = text.lowercase()
            return lower.contains("<think") ||
                lower.contains("<reasoning>") || lower.contains("<|reasoning|>") ||
                text.contains("***/")
        }
    }
}

private fun CharSequence.indexOfLiteral(value: String): Int {
    for (i in 0..length - value.length) {
        var matched = true
        for (j in value.indices) {
            if (this[i + j] != value[j]) {
                matched = false
                break
            }
        }
        if (matched) return i
    }
    return -1
}
