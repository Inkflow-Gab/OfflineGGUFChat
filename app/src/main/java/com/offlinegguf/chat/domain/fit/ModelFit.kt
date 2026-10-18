package com.offlinegguf.chat.domain.fit

import com.offlinegguf.chat.domain.device.DeviceProfile
import com.offlinegguf.chat.domain.device.DeviceTier
import kotlin.math.max
import kotlin.math.min

/**
 * Will this model run on this phone, how fast, and what should the settings be?
 *
 * Everything here is arithmetic on numbers that come either from a real GGUF
 * header ([com.offlinegguf.chat.data.gguf.GgufReader]) or from the curated
 * catalogue, so the same code answers "before I download it" and "now that I
 * have it". The estimates are deliberately conservative: it is much better to
 * tell someone a 7B model will crawl than to let Android kill the process
 * halfway through an answer.
 */
object ModelFit {

    enum class Verdict(val label: String) {
        COMFORTABLE("Comfortable"),
        WORKABLE("Workable"),
        TIGHT("Tight"),
        WILL_NOT_FIT("Will not fit"),
        UNREADABLE("File problem"),
    }

    /** What a model claims to need, from its header or from the catalogue. */
    data class Spec(
        val weightBytes: Long,
        val paramCount: Long = 0,
        val contextLength: Long = 0,
        val blockCount: Long = 0,
        val embeddingLength: Long = 0,
        val headCount: Long = 0,
        val headCountKv: Long = 0,
        val quantization: String = "",
        val hasChatTemplate: Boolean = true,
        val multimodal: Boolean = false,
        val vocabSize: Long = 0,
        val tokenCount: Long = 0,
        val version: Int = 0,
        val valid: Boolean = true,
        val error: String? = null,
    )

    /** Settings the caller wants; nulls mean "you decide". */
    data class Request(
        val context: Int? = null,
        val threads: Int? = null,
        val batch: Int? = null,
        val kvType: String = DEFAULT_KV_TYPE,
        val storageFreeBytes: Long = -1L,
    )

    data class Settings(val context: Int, val threads: Int, val batch: Int, val kvType: String)

    data class Assessment(
        val verdict: Verdict,
        val settings: Settings,
        val weightBytes: Long,
        val kvBytes: Long,
        val overheadBytes: Long,
        val peakRamBytes: Long,
        val budgetBytes: Long,
        val headroomBytes: Long,
        val tokensPerSecond: Double,
        val warnings: List<String>,
        val notes: List<String>,
    ) {
        /** 0..1 — how much of the model budget this configuration eats. */
        val pressure: Float
            get() = if (budgetBytes <= 0) 1f else (peakRamBytes.toFloat() / budgetBytes).coerceIn(0f, 1.5f)

        val usable: Boolean get() = verdict != Verdict.WILL_NOT_FIT && verdict != Verdict.UNREADABLE

        val speedLabel: String
            get() = when {
                tokensPerSecond <= 0.0 -> "—"
                tokensPerSecond < 2.0 -> "very slow"
                tokensPerSecond < 5.0 -> "slow"
                tokensPerSecond < 12.0 -> "steady"
                tokensPerSecond < 25.0 -> "fast"
                else -> "very fast"
            }
    }

    const val DEFAULT_KV_TYPE = "f16"

    /** Weights are mmapped, but page tables and partial residency still cost. */
    private const val MMAP_FACTOR = 1.12

    /** Compute buffers, sampler state, the JNI layer and general slack. */
    private const val RUNTIME_OVERHEAD = 256L * 1024 * 1024

    /** Effective memory bandwidth per tier, in GB/s, for the speed estimate. */
    private val BANDWIDTH_GBPS = mapOf(
        DeviceTier.LIGHT to 6.0,
        DeviceTier.MEDIUM to 11.0,
        DeviceTier.STRONG to 18.0,
        DeviceTier.FLAGSHIP to 26.0,
    )

    /** Bytes per cached value for each KV cache quantization llama.cpp offers. */
    private val KV_BYTES = mapOf(
        "f32" to 4.0,
        "f16" to 2.0,
        "bf16" to 2.0,
        "q8_0" to 1.0625,
        "q4_0" to 0.5625,
    )

    fun assess(profile: DeviceProfile, spec: Spec, request: Request = Request()): Assessment {
        val warnings = ArrayList<String>()
        val notes = ArrayList<String>()

        // ---- unreadable / suspicious files first: nothing else matters ----
        if (!spec.valid || !spec.error.isNullOrEmpty()) {
            spec.error?.let { warnings += it }
            if (spec.tokenCount > 0 && spec.vocabSize > 0 && spec.tokenCount != spec.vocabSize) {
                warnings += "Header says vocab ${Formats.paramCount(spec.vocabSize)} but the tokenizer holds " +
                    "${Formats.paramCount(spec.tokenCount)} entries — the file looks inconsistent."
            }
            return Assessment(
                verdict = Verdict.UNREADABLE,
                settings = Settings(profile.suggestedContext, profile.suggestedThreads, profile.suggestedBatch, DEFAULT_KV_TYPE),
                weightBytes = spec.weightBytes,
                kvBytes = 0,
                overheadBytes = RUNTIME_OVERHEAD,
                peakRamBytes = 0,
                budgetBytes = profile.modelRamBudgetBytes,
                headroomBytes = 0,
                tokensPerSecond = 0.0,
                warnings = warnings,
                notes = notes,
            )
        }
        if (spec.version == 1) {
            warnings += "GGUF v1 is no longer supported by llama.cpp — re-download a v2/v3 file."
        }
        if (spec.tokenCount > 0 && spec.vocabSize > 0 && spec.tokenCount != spec.vocabSize) {
            warnings += "Declared vocab (${Formats.paramCount(spec.vocabSize)}) does not match the tokenizer " +
                "(${Formats.paramCount(spec.tokenCount)} entries)."
        }

        // ---- pick settings ----
        val threads = (request.threads ?: profile.suggestedThreads).coerceIn(1, max(1, profile.cores))
        val batch = (request.batch ?: profile.suggestedBatch).coerceIn(32, 4096)
        val kvType = request.kvType.ifBlank { DEFAULT_KV_TYPE }
        val trained = spec.contextLength.toInt()
        val wanted = request.context ?: profile.suggestedContext
        var context = if (trained > 0) min(wanted, trained) else wanted
        context = context.coerceAtLeast(256)

        // ---- memory ----
        val budget = profile.modelRamBudgetBytes
        val kvPerToken = kvBytesPerToken(spec)
        var kvBytes = context.toLong() * kvPerToken
        var weights = (spec.weightBytes * MMAP_FACTOR).toLong()

        // Step the context down until the whole thing fits, rather than
        // presenting a configuration we already know will be killed.
        var reduced = false
        while (context > 512 && weights + kvBytes + RUNTIME_OVERHEAD > budget) {
            context = max(512, context / 2)
            kvBytes = context.toLong() * kvPerToken
            reduced = true
        }
        if (reduced) {
            warnings += "Context dropped to $context tokens to fit in the ~${Formats.bytes(budget)} this phone can give a model."
        }
        // If even 512 does not fit, try a cheaper KV cache before giving up.
        if (weights + kvBytes + RUNTIME_OVERHEAD > budget && kvType != "q8_0" && kvPerToken > 0) {
            val cheapPerToken = kvBytesPerToken(spec, "q8_0")
            val cheapKv = context.toLong() * cheapPerToken
            if (weights + cheapKv + RUNTIME_OVERHEAD <= budget) {
                kvBytes = cheapKv
                notes += "Quantised KV cache (q8_0) keeps this within budget; enable it in model settings."
            }
        }

        val peak = weights + kvBytes + RUNTIME_OVERHEAD
        val headroom = budget - peak
        val pressure = if (budget > 0) peak.toDouble() / budget else 2.0
        val verdict = when {
            pressure <= 0.62 -> Verdict.COMFORTABLE
            pressure <= 0.85 -> Verdict.WORKABLE
            pressure <= 1.0 -> Verdict.TIGHT
            else -> Verdict.WILL_NOT_FIT
        }

        if (verdict == Verdict.WILL_NOT_FIT) {
            warnings += "Needs about ${Formats.bytes(peak)}; this phone can spare ~${Formats.bytes(budget)} for a model."
            if (spec.weightBytes > 0) {
                val smaller = suggestSmaller(spec.weightBytes, budget)
                if (smaller > 0) notes += "A quant around ${Formats.bytes(smaller)} would fit — try Q4_K_S or a smaller model."
            }
        } else if (verdict == Verdict.TIGHT) {
            warnings += "Fits, but with only ~${Formats.bytes(max(headroom, 0))} to spare. Close other apps before generating."
        }

        // ---- storage ----
        val free = if (request.storageFreeBytes >= 0) request.storageFreeBytes else profile.freeStorageBytes
        if (spec.weightBytes > 0 && free in 1 until spec.weightBytes * 2) {
            warnings += "Importing needs roughly ${Formats.bytes(spec.weightBytes * 2)} free (file plus the copy this app makes); " +
                "${Formats.bytes(free)} available."
        }

        // ---- speed ----
        val tps = estimateTokensPerSecond(profile.tier, spec.weightBytes, threads)
        if (tps in 0.001..2.0) {
            warnings += "Expect roughly ${Formats.fixed(tps, 1)} tokens/second — one short answer can take a minute."
        } else if (tps > 0.0) {
            notes += "Estimated ${Formats.fixed(tps, 1)} tokens/second on this device."
        }

        // ---- quality / capability notes ----
        val quant = spec.quantization.uppercase()
        if (quant.startsWith("Q2_") || quant.startsWith("IQ1") || quant.startsWith("IQ2")) {
            warnings += "$quant is a very aggressive quantization: grammar and instruction following degrade noticeably."
        }
        if (!spec.hasChatTemplate) {
            notes += "No chat template embedded in the file — this app applies a generic ChatML-style one."
        }
        if (spec.multimodal) {
            notes += "File carries a vision/audio projector. Images are not processed offline; text works normally."
        }
        if (profile.pageSizeBytes == 16384L && !profile.engineAvailable) {
            notes += "16 KB-page device. The bundled native build is linked with max-page-size=16384, so it loads here."
        }
        if (!profile.engineAvailable) {
            warnings += "This build has no inference engine compiled in, so nothing can be generated yet."
        }

        return Assessment(
            verdict = verdict,
            settings = Settings(context, threads, batch, kvType),
            weightBytes = spec.weightBytes,
            kvBytes = kvBytes,
            overheadBytes = RUNTIME_OVERHEAD,
            peakRamBytes = peak,
            budgetBytes = budget,
            headroomBytes = headroom,
            tokensPerSecond = tps,
            warnings = warnings,
            notes = notes,
        )
    }

    /** Bytes of KV cache per token, for the given cache quantization. */
    fun kvBytesPerToken(spec: Spec, kvType: String = DEFAULT_KV_TYPE): Long {
        val layers = spec.blockCount
        if (layers <= 0 || spec.embeddingLength <= 0) {
            // Catalogue entries often know only the parameter count. Real models
            // cluster tightly around these ratios (2 * layers * kv_dim * 2 bytes
            // per token): Llama-3.1-8B is 6.5e-5 B/param, Qwen2.5-1.5B 1.1e-4,
            // Llama-3.2-3B 1.1e-4. Grouped-query attention pulls the big models
            // down, hence the split. It is an estimate and the UI says so.
            return if (spec.paramCount > 0) {
                val ratio = if (spec.paramCount >= 5_000_000_000L) 7.0e-5 else 1.0e-4
                (spec.paramCount * ratio).toLong()
            } else 0L
        }
        val headDim = if (spec.headCount > 0) spec.embeddingLength.toDouble() / spec.headCount else 0.0
        val kvDim = if (spec.headCountKv > 0 && headDim > 0) spec.headCountKv * headDim
        else spec.embeddingLength.toDouble()
        val perValue = KV_BYTES[kvType.lowercase()] ?: 2.0
        // K and V, per layer, per token.
        return max(0.0, 2.0 * layers * kvDim * perValue).toLong()
    }

    /**
     * Generation is memory-bandwidth bound: every token re-reads the weights.
     * This is an order-of-magnitude estimate and is labelled as one in the UI.
     */
    fun estimateTokensPerSecond(tier: DeviceTier, weightBytes: Long, threads: Int): Double {
        if (weightBytes <= 0) return 0.0
        val sizeGb = weightBytes / (1024.0 * 1024 * 1024)
        val bandwidth = BANDWIDTH_GBPS[tier] ?: 8.0
        val threadFactor = (threads / 4.0).coerceIn(0.6, 1.15)
        return (bandwidth / sizeGb * threadFactor).coerceIn(0.3, 80.0)
    }

    /** Largest weight size that would still fit, for the "try something smaller" hint. */
    private fun suggestSmaller(currentWeights: Long, budget: Long): Long {
        val usable = budget - RUNTIME_OVERHEAD
        if (usable <= 0) return 0
        val target = (usable / MMAP_FACTOR).toLong() - 128L * 1024 * 1024
        return if (target in 1 until currentWeights) target else 0
    }
}
