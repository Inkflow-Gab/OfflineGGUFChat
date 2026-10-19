package com.offlinegguf.chat.data.catalog

import com.offlinegguf.chat.domain.device.DeviceProfile
import com.offlinegguf.chat.domain.fit.ModelFit
import com.offlinegguf.chat.util.Formats
import kotlin.math.max
import kotlin.math.min

/**
 * "Given *this* phone, which model should I get?"
 *
 * The advisor never talks to the network. It reads the device once
 * ([com.offlinegguf.chat.domain.device.DeviceProbe]), scores the bundled
 * catalogue with the same fit arithmetic the app uses for files already on disk,
 * and returns a ranked list with plain-language reasons.
 */
object ModelAdvisor {

    data class Prefs(
        val style: ModelStyle = ModelStyle.GENERAL,
        /** Only refusal-free fine-tunes. */
        val uncensored: Boolean = false,
        /** Skip anything bigger than this to download. */
        val maxDownloadBytes: Long = Long.MAX_VALUE,
        /** Weight speed over capability when true. */
        val preferSpeed: Boolean = false,
        /** Context the user actually wants; null means the device default. */
        val wantedContext: Int? = null,
    )

    data class Recommendation(
        val model: CatalogModel,
        val assessment: ModelFit.Assessment,
        /** 0..100. Ordered descending; unfit models are pushed to the bottom. */
        val score: Double,
        val reasons: List<String>,
        val badges: List<String>,
        val rank: Int,
    ) {
        val headline: String
            get() = when (assessment.verdict) {
                ModelFit.Verdict.COMFORTABLE -> "Runs comfortably"
                ModelFit.Verdict.WORKABLE -> "Runs well"
                ModelFit.Verdict.TIGHT -> "Runs, but tight"
                ModelFit.Verdict.WILL_NOT_FIT -> "Too big for this phone"
                ModelFit.Verdict.UNREADABLE -> "Unknown"
            }
    }

    /** Ranked suggestions. [limit] caps the list; unfit entries are kept at the end. */
    fun recommend(
        profile: DeviceProfile,
        catalog: List<CatalogModel>,
        prefs: Prefs = Prefs(),
        limit: Int = 6,
    ): List<Recommendation> {
        if (catalog.isEmpty()) return emptyList()

        val usable = catalog.filter { it.approxSizeBytes <= prefs.maxDownloadBytes }
        val styleMatched = usable.filter { it.matchesStyle(prefs) }
        // Nothing matches the filter exactly? Fall back to the whole set rather
        // than showing an empty screen — ranked lower by the style term anyway.
        // Refusal-free is the one filter with no fallback: handing back a
        // filtered model under a "refusal-free only" toggle is a wrong answer,
        // not a helpful one, so an empty shortlist is the honest result.
        val pool = when {
            styleMatched.isNotEmpty() -> styleMatched
            prefs.uncensored || prefs.style == ModelStyle.UNCENSORED -> styleMatched
            else -> usable.ifEmpty { catalog }
        }

        val scored = pool.map { model ->
            val assessment = assess(profile, model, prefs)
            val score = score(profile, model, assessment, prefs)
            Recommendation(
                model = model,
                assessment = assessment,
                score = score,
                reasons = reasons(profile, model, assessment, prefs),
                badges = badges(profile, model, assessment, prefs),
                rank = 0,
            )
        }.sortedWith(
            compareByDescending<Recommendation> { it.assessment.verdict != ModelFit.Verdict.WILL_NOT_FIT }
                .thenByDescending { it.score }
        )

        return scored.take(max(1, limit)).mapIndexed { index, rec -> rec.copy(rank = index + 1) }
    }

    fun best(profile: DeviceProfile, catalog: List<CatalogModel>, prefs: Prefs = Prefs()): Recommendation? =
        recommend(profile, catalog, prefs, limit = 1).firstOrNull()

    /** The single line the onboarding card shows: "Best fit for this phone: …". */
    fun headline(profile: DeviceProfile, catalog: List<CatalogModel>, prefs: Prefs = Prefs()): String {
        val top = best(profile, catalog, prefs) ?: return "No catalogue entries available."
        val speed = top.assessment.tokensPerSecond
        val speedText = if (speed > 0) " · ~${Formats.fixed(speed, 0)} tok/s" else ""
        return "${top.model.displayName} · ${Formats.bytes(top.model.approxSizeBytes)}$speedText"
    }

    fun assess(profile: DeviceProfile, model: CatalogModel, prefs: Prefs = Prefs()): ModelFit.Assessment {
        val spec = ModelFit.Spec(
            weightBytes = model.approxSizeBytes,
            paramCount = model.paramCount,
            contextLength = model.contextLength.toLong(),
            blockCount = model.blockCount.toLong(),
            embeddingLength = model.embeddingLength.toLong(),
            headCountKv = model.headCountKv.toLong(),
            quantization = model.quant,
            hasChatTemplate = true,
            multimodal = false,
            vocabSize = 0,
            tokenCount = 0,
            valid = true,
        )
        return ModelFit.assess(
            profile = profile,
            spec = spec,
            request = ModelFit.Request(
                context = prefs.wantedContext ?: profile.suggestedContext,
                storageFreeBytes = profile.freeStorageBytes,
            ),
        )
    }

    private fun score(
        profile: DeviceProfile,
        model: CatalogModel,
        assessment: ModelFit.Assessment,
        prefs: Prefs,
    ): Double {
        val quality = (model.qualityScore / 100.0).coerceIn(0.0, 1.0)
        val speed = (assessment.tokensPerSecond / 15.0).coerceIn(0.0, 1.0)
        val headroom = (1.0 - assessment.pressure.toDouble()).coerceIn(0.0, 1.0)
        val styleMatch = when {
            prefs.uncensored && model.uncensored -> 1.0
            prefs.style == ModelStyle.UNCENSORED && model.uncensored -> 1.0
            model.hasStyle(prefs.style.id) -> 1.0
            model.hasStyle(ModelStyle.GENERAL.id) -> 0.55
            else -> 0.2
        }

        val (wQuality, wSpeed, wFit, wStyle) = if (prefs.preferSpeed) {
            arrayOf(0.22, 0.45, 0.20, 0.13)
        } else {
            arrayOf(0.42, 0.26, 0.20, 0.12)
        }
        var total = wQuality * quality + wSpeed * speed + wFit * headroom + wStyle * styleMatch

        // A phone with 3 GB of RAM should not be told to fetch 8 GB "because it
        // is the best model": capability is only worth scoring once it fits.
        if (assessment.verdict == ModelFit.Verdict.WILL_NOT_FIT) total *= 0.12
        if (profile.freeStorageBytes in 1 until model.approxSizeBytes * 2) total *= 0.5

        return (total * 100.0).coerceIn(0.0, 100.0)
    }

    private fun reasons(
        profile: DeviceProfile,
        model: CatalogModel,
        assessment: ModelFit.Assessment,
        prefs: Prefs,
    ): List<String> {
        val out = ArrayList<String>(5)
        out += when (assessment.verdict) {
            ModelFit.Verdict.COMFORTABLE ->
                "Fits with ~${Formats.bytes(max(assessment.headroomBytes, 0))} to spare."
            ModelFit.Verdict.WORKABLE ->
                "Fits: ~${Formats.bytes(assessment.peakRamBytes)} of the ~${Formats.bytes(assessment.budgetBytes)} available."
            ModelFit.Verdict.TIGHT ->
                "Fits with almost nothing to spare — close other apps first."
            ModelFit.Verdict.WILL_NOT_FIT ->
                "Needs ~${Formats.bytes(assessment.peakRamBytes)}; this ${profile.tier.label.lowercase()} phone has ~${Formats.bytes(assessment.budgetBytes)}."
            ModelFit.Verdict.UNREADABLE -> "Could not estimate fit."
        }
        if (assessment.tokensPerSecond > 0) {
            out += "About ${Formats.fixed(assessment.tokensPerSecond, 1)} tok/s (${assessment.speedLabel})."
        }
        val storage = profile.freeStorageBytes
        if (storage in 1 until model.approxSizeBytes * 2) {
            out += "Only ${Formats.bytes(storage)} free; importing wants ~${Formats.bytes(model.approxSizeBytes * 2)}."
        }
        if (model.contextLength > assessment.settings.context * 2L) {
            out += "Trained at ${Formats.contextTokens(model.contextLength.toLong())} context, but " +
                "${Formats.contextTokens(assessment.settings.context.toLong())} is what fits here."
        }
        if (prefs.uncensored && model.uncensored) {
            out += "Refusal-free fine-tune: output is unfiltered, so read it as raw text."
        }
        if (prefs.style == ModelStyle.CODE && model.hasStyle(ModelStyle.CODE.id)) {
            out += "Code-tuned — better at patches and diffs than a general model of the same size."
        }
        if (model.license.isNotBlank() && !model.license.startsWith("Apache")) {
            out += "Licence: ${model.license}."
        }
        return out
    }

    private fun badges(
        profile: DeviceProfile,
        model: CatalogModel,
        assessment: ModelFit.Assessment,
        prefs: Prefs,
    ): List<String> {
        val out = ArrayList<String>(4)
        when (assessment.verdict) {
            ModelFit.Verdict.COMFORTABLE, ModelFit.Verdict.WORKABLE -> out += "Fits"
            ModelFit.Verdict.TIGHT -> out += "Tight fit"
            ModelFit.Verdict.WILL_NOT_FIT -> out += "Too big"
            ModelFit.Verdict.UNREADABLE -> Unit
        }
        if (assessment.tokensPerSecond >= 8.0) out += "Fast"
        if (model.uncensored) out += "Refusal-free"
        if (model.hasStyle(ModelStyle.CODE.id)) out += "Code"
        if (model.approxSizeBytes <= 1L * 1024 * 1024 * 1024) out += "Under 1 GB"
        if (profile.freeStorageBytes in 1 until model.approxSizeBytes * 2) out += "Low storage"
        return out.distinct().take(4)
    }

    /**
     * Advice for a file that is already on the device: combines the real header
     * with the device profile. Used by the model detail screen.
     */
    fun adviceForInstalled(profile: DeviceProfile, assessment: ModelFit.Assessment): String {
        val settings = assessment.settings
        return buildString {
            append("Suggested: n_ctx ${settings.context}, ${settings.threads} threads, batch ${settings.batch}.")
            if (assessment.tokensPerSecond > 0) {
                append(" Expect ~${Formats.fixed(assessment.tokensPerSecond, 1)} tok/s.")
            }
            if (assessment.verdict == ModelFit.Verdict.WILL_NOT_FIT) {
                append(" This model will very likely be killed by Android — pick a smaller quant.")
            }
        }
    }

    /**
     * Largest context that still fits beside the weights, never more than
     * [requested]. Used when the user drags the context slider past what the
     * phone can hold.
     */
    fun clampContext(
        profile: DeviceProfile,
        requested: Int,
        kvBytesPerToken: Long,
        weightBytes: Long,
        overheadBytes: Long = RUNTIME_OVERHEAD,
    ): Int {
        if (kvBytesPerToken <= 0) return max(256, requested)
        val room = profile.modelRamBudgetBytes - (weightBytes * MMAP_FACTOR).toLong() - overheadBytes
        if (room <= 0) return 256
        val maxByRam = (room / kvBytesPerToken).toInt()
        return min(max(256, maxByRam), max(256, requested))
    }

    /** Mirrors ModelFit's private constants so the slider and the fit maths agree. */
    private const val MMAP_FACTOR = 1.12
    private const val RUNTIME_OVERHEAD = 256L * 1024 * 1024
}
