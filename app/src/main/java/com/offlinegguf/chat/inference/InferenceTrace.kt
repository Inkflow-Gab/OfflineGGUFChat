package com.offlinegguf.chat.inference

/**
 * One generation's latency budget, in the order the time is actually spent.
 *
 * This exists because "it took three minutes" is not a diagnosis. These fields
 * separate the four things that can each cost minutes:
 *
 *  1. queue — waiting for the engine thread (another job, or a model load)
 *  2. prompt — windowing + template rendering in Kotlin
 *  3. prefill — tokenize + prompt evaluation inside llama.cpp
 *  4. firstToken → firstRender — generated text that the UI has not shown yet
 *
 * The last one is where a reasoning model hides: the tokens are real, they are
 * just not the answer. [reasoningChars] makes that visible instead of guessing.
 *
 * Pure data with no Android imports so the audit logic is unit-tested on the JVM.
 */
data class InferenceTrace(
    val promptBuildMs: Long = 0,
    val queueMs: Long = 0,
    val tokenizeMs: Long = 0,
    val prefillMs: Long = 0,
    /** Engine start → the first text piece reached the collector. */
    val firstTokenMs: Long = 0,
    /** Click → the first characters were actually put into UI state. */
    val firstRenderMs: Long = 0,
    val totalMs: Long = 0,
    val tokensGenerated: Int = 0,
    val prefillTokens: Int = 0,
    val reusedTokens: Int = 0,
    val decodeCalls: Int = 0,
    val contextSize: Int = 0,
    val threads: Int = 0,
    val batch: Int = 0,
    /** Decode threads / prefill threads, e.g. "2/7" — the split is deliberate. */
    val prefillThreads: Int = 0,
    val promptChars: Int = 0,
    val estimatedPromptTokens: Int = 0,
    val reasoningChars: Int = 0,
    val answerChars: Int = 0,
    val modelLoads: Long = 0,
    val reasoningEnabled: Boolean = false,
    val backend: String = "llama.cpp",
) {
    /** Rough token count for a character span (ASCII-ish English). */
    private fun tokens(chars: Int): Int = if (chars <= 0) 0 else (chars / 4).coerceAtLeast(1)

    val reasoningTokens: Int get() = tokens(reasoningChars)

    /** Share of everything generated that the user did not see as an answer. */
    val reasoningShare: Float
        get() {
            val total = reasoningChars + answerChars
            return if (total <= 0) 0f else reasoningChars.toFloat() / total
        }

    val decodeTokensPerSecond: Double
        get() {
            val decodeMs = totalMs - prefillMs - tokenizeMs - firstTokenMs
            return if (decodeMs > 0 && tokensGenerated > 0) tokensGenerated * 1000.0 / decodeMs else 0.0
        }

    /** One logcat line, stable enough to grep in a bug report. */
    fun line(): String = buildString {
        append("prompt=").append(estimatedPromptTokens).append("tok/").append(promptChars).append("ch")
        append(" build=").append(promptBuildMs).append("ms")
        append(" queue=").append(queueMs).append("ms")
        append(" tokenize=").append(tokenizeMs).append("ms")
        append(" prefill=").append(prefillMs).append("ms")
        append(" (").append(prefillTokens).append(" new, ").append(reusedTokens).append(" cached)")
        append(" ttft=").append(firstTokenMs).append("ms")
        append(" firstRender=").append(firstRenderMs).append("ms")
        append(" total=").append(totalMs).append("ms")
        append(" gen=").append(tokensGenerated).append("tok")
        append(" reasoning=").append(reasoningTokens).append("tok(").append(reasoningChars).append("ch)")
        append(" answer=").append(tokens(answerChars)).append("tok(").append(answerChars).append("ch)")
        append(" calls=").append(decodeCalls)
        append(" ctx=").append(contextSize)
        append(" threads=").append(threads)
        append(" prefillThreads=").append(prefillThreads)
        append(" batch=").append(batch)
        append(" loads=").append(modelLoads)
        append(" backend=").append(backend)
    }

    fun summary(): String = buildString {
        append("Prompt ").append(prefillTokens).append(" tok in ").append(prefillMs).append(" ms")
        if (reusedTokens > 0) append(" (").append(reusedTokens).append(" reused)")
        append(" · first token ").append(firstTokenMs).append(" ms")
        append(" · ").append(tokensGenerated).append(" tok in ").append(totalMs).append(" ms")
        if (reasoningChars > 0) {
            append(" · reasoning ").append(reasoningTokens).append(" tok")
        }
        append(" · ").append(threads).append(" threads")
        if (prefillThreads > threads) append("/").append(prefillThreads).append(" for prompt")
        append(", ctx ").append(contextSize)
        append(", loads ").append(modelLoads)
    }

    companion object {
        /**
         * Regression guard for the bug this file was written for: a short prompt
         * must not spend minutes before the first visible character.
         *
         * The thresholds are deliberately generous — a 4 GB phone is slow, but
         * "hello" never legitimately needs minutes. Returns null when the run
         * looks healthy.
         */
        fun audit(trace: InferenceTrace): String? {
            val shortPrompt = trace.estimatedPromptTokens in 1..64
            return when {
                // The engine never produced a token: the wait was structural.
                trace.tokensGenerated == 0 && trace.totalMs > 60_000 ->
                    "No token was generated in ${trace.totalMs} ms with a " +
                        "${trace.prefillTokens}-token prompt. The delay is before sampling " +
                        "(load, queue, or prompt evaluation), not in the model's output."

                // Reasoning dominated the wait: the model was working, invisibly.
                shortPrompt && trace.firstRenderMs > 10_000 && trace.reasoningShare > 0.6f ->
                    "${trace.reasoningTokens} reasoning tokens (${trace.reasoningChars} chars) ran " +
                        "before ${trace.answerTokensComputed} answer tokens on a ${trace.estimatedPromptTokens}-token " +
                        "prompt — the minutes went to hidden reasoning. Turn Reasoning off."

                // Slow decode: the phone is the bottleneck, not the prompt.
                shortPrompt && trace.firstRenderMs > 10_000 && trace.decodeTokensPerSecond < 3.0 ->
                    "Only ${"%.1f".format(trace.decodeTokensPerSecond)} tok/s at " +
                        "${trace.threads} threads on a ${trace.estimatedPromptTokens}-token prompt. " +
                        "The device is decoding slowly; try fewer threads or a smaller context."

                // Prompt evaluation is out of proportion to the prompt.
                shortPrompt && trace.prefillMs > 5_000 ->
                    "A ${trace.prefillTokens}-token prompt took ${trace.prefillMs} ms to evaluate. " +
                        "That is prompt-evaluation cost, not generation."

                else -> null
            }
        }
    }

    private val answerTokensComputed: Int get() = tokens(answerChars)
}
