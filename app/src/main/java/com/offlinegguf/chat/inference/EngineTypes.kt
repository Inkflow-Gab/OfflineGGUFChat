package com.offlinegguf.chat.inference

/** Everything needed to open a model. Values come from the model row or the device profile. */
data class LoadRequest(
    val path: String,
    val context: Int,
    val threads: Int,
    val batch: Int,
    /** Always 0: no GPU backend is wired into this build. */
    val gpuLayers: Int = 0,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val loraPaths: List<String> = emptyList(),
    val loraScales: List<Float> = emptyList(),
)

/**
 * Sampling settings.
 *
 * [toInts]/[toFloats] are the wire format for `nativeGenerate` and must stay in
 * step with the `IntArg`/`FloatArg` enums in `app/cpp/llama_jni.cpp`. The counts
 * are asserted there, so a mismatch fails loudly instead of silently sampling
 * with zeros.
 */
data class SamplingParams(
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,
    val typicalP: Float = 1.0f,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 256,
    val presencePenalty: Float = 0.0f,
    val frequencyPenalty: Float = 0.0f,
    /** -1 lets the engine pick a fresh seed each run. */
    val seed: Int = -1,
    val ignoreEos: Boolean = false,
    /** GBNF grammar, or null for unconstrained sampling. */
    val grammar: String? = null,
    val stopTokens: List<Int> = emptyList(),
) {
    fun toInts(): IntArray = intArrayOf(
        maxTokens,
        topK,
        repeatLastN,
        seed,
        if (ignoreEos) 1 else 0,
    )

    fun toFloats(): FloatArray = floatArrayOf(
        temperature,
        topP,
        minP,
        typicalP,
        repeatPenalty,
        presencePenalty,
        frequencyPenalty,
    )

    /** Grammar-constrained decoding wants greedy-ish sampling or it fights the grammar. */
    fun forGrammar(): SamplingParams =
        if (grammar.isNullOrBlank()) this else copy(temperature = minOf(temperature, 0.2f), topP = 0.9f)

    companion object {
        const val INT_COUNT = 5
        const val FLOAT_COUNT = 7

        val Default = SamplingParams()
        val Precise = SamplingParams(temperature = 0.2f, topP = 0.9f, repeatPenalty = 1.05f)
        val Creative = SamplingParams(temperature = 1.0f, topP = 0.98f, minP = 0.02f)
        val Deterministic = SamplingParams(temperature = 0.0f, topK = 1, seed = 42)
    }
}

/** Post-generation numbers, decoded from the `long[]` the JNI layer returns. */
data class GenerationStats(
    val tokensGenerated: Int = 0,
    val promptTokens: Int = 0,
    val contextUsed: Int = 0,
    val microseconds: Long = 0,
    val truncated: Boolean = false,
    val cancelled: Boolean = false,
) {
    val tokensPerSecond: Double
        get() = if (microseconds > 0 && tokensGenerated > 0) {
            tokensGenerated / (microseconds / 1_000_000.0)
        } else 0.0

    val seconds: Double get() = microseconds / 1_000_000.0

    companion object {
        const val FLAG_TRUNCATED = 1
        const val FLAG_CANCELLED = 2

        fun from(values: LongArray?): GenerationStats {
            if (values == null || values.size < 5) return GenerationStats()
            val flags = values[4]
            return GenerationStats(
                tokensGenerated = values[0].toInt(),
                promptTokens = values[1].toInt(),
                contextUsed = values[2].toInt(),
                microseconds = values[3],
                truncated = flags and FLAG_TRUNCATED.toLong() != 0L,
                cancelled = flags and FLAG_CANCELLED.toLong() != 0L,
            )
        }
    }
}

/** What the engine reports about a model it has actually loaded. */
data class ModelReport(
    val description: String = "",
    val chatTemplate: String = "",
    val vocabSize: Int = 0,
    val trainedContext: Int = 0,
    val contextSize: Int = 0,
    val contextUsed: Int = 0,
    val paramCount: Long = 0,
    val weightBytes: Long = 0,
    val multimodal: Boolean = false,
    val supportsAdapters: Boolean = false,
) {
    companion object {
        val Empty = ModelReport()
    }
}

/** Why a generation ended. */
enum class GenerationOutcome { COMPLETED, CANCELLED, TRUNCATED, FAILED }
