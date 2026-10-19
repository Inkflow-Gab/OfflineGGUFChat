package com.offlinegguf.chat.data.catalog

import android.content.Context
import java.net.URLEncoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One curated GGUF build the app knows how to recommend.
 *
 * The app has no INTERNET permission and never downloads a model: entries carry
 * links that open huggingface.co in the browser. Sizes and layer counts here are
 * *catalogue estimates* used to judge fit before a download; the moment a real
 * file is on the device, [com.offlinegguf.chat.data.gguf.GgufReader] replaces
 * them with the values from its header.
 */
@Serializable
data class CatalogModel(
    val id: String,
    val repo: String = "",
    val family: String = "",
    val displayName: String = "",
    val paramsBillions: Double = 0.0,
    val quant: String = "",
    val approxSizeBytes: Long = 0,
    val contextLength: Int = 4096,
    val blockCount: Int = 0,
    val embeddingLength: Int = 0,
    val headCountKv: Int = 0,
    val style: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val license: String = "",
    val uncensored: Boolean = false,
    val qualityScore: Int = 50,
    val released: String = "",
    val filePattern: String = "*.gguf",
    val fileHint: String? = null,
    val notes: String = "",
) {
    val paramCount: Long get() = (paramsBillions * 1_000_000_000.0).toLong()

    val modelCardUrl: String get() = "https://huggingface.co/$repo"

    /** File tree — always valid even when a quant gets renamed upstream. */
    val repoUrl: String get() = "https://huggingface.co/$repo/tree/main"

    /** Search fallback: works even if this exact repository has moved. */
    val searchUrl: String
        get() = "https://huggingface.co/models?search=" + encode("$family GGUF")

    fun fileUrl(fileName: String): String =
        "https://huggingface.co/$repo/resolve/main/${encode(fileName)}"

    /** Best single link to hand the user: the file if we know its name, else the tree. */
    val primaryUrl: String get() = fileHint?.let { fileUrl(it) } ?: repoUrl

    fun hasStyle(styleId: String): Boolean = style.contains(styleId)

    /** Whether this entry belongs in a result set built from [prefs]. */
    fun matches(prefs: ModelAdvisor.Prefs): Boolean {
        if (prefs.uncensored && !uncensored) return false
        if (!prefs.uncensored && prefs.style == ModelStyle.UNCENSORED && !uncensored) return false
        if (prefs.style != ModelStyle.UNCENSORED && !hasStyle(prefs.style.id) && style.isNotEmpty()) return false
        return approxSizeBytes <= prefs.maxDownloadBytes
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** What the user wants the model *for*. Drives both filtering and the copy. */
enum class ModelStyle(val id: String, val label: String, val blurb: String) {
    GENERAL("general", "General", "Questions, rewriting, summarising, brainstorming."),
    CODE("code", "Code", "Patches, explanations and review. Pairs with the diff view."),
    CHAT("chat", "Conversation", "Long, natural back-and-forth and roleplay."),
    ANALYSIS("analysis", "Analysis", "Extraction, structured output and careful reasoning."),
    UNCENSORED("uncensored", "Refusal-free", "Fine-tunes with the refusal behaviour removed or reduced.");

    companion object {
        fun fromId(id: String?): ModelStyle =
            values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GENERAL
    }
}

@Serializable
data class ModelCatalog(
    val catalogVersion: Int = 0,
    val updated: String = "",
    val note: String = "",
    val entries: List<CatalogModel> = emptyList(),
) {
    val isEmpty: Boolean get() = entries.isEmpty()

    fun byId(id: String): CatalogModel? = entries.firstOrNull { it.id == id }

    fun uncensored(): List<CatalogModel> = entries.filter { it.uncensored }
}

/** Reads `assets/catalog/models.json`. Cached: the file never changes at runtime. */
object CatalogStore {
    const val ASSET_PATH = "catalog/models.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Volatile
    private var cached: ModelCatalog? = null

    fun parse(text: String): ModelCatalog =
        json.decodeFromString(ModelCatalog.serializer(), text)

    /** Null instead of throwing — a corrupt asset must not crash the app. */
    fun parseOrNull(text: String): ModelCatalog? = runCatching { parse(text) }.getOrNull()

    fun load(context: Context): ModelCatalog {
        cached?.let { return it }
        val loaded = runCatching {
            context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
        }.getOrElse { ModelCatalog() }
        cached = loaded
        return loaded
    }

    /** Test hook. */
    fun reset() {
        cached = null
    }
}
