package com.offlinegguf.chat.data.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure JSON/SSE parsing for OpenAI-compatible hosts. No Android, no sockets —
 * everything here is unit-tested on the JVM.
 *
 * Provider shapes differ in the details, so parsing walks `JsonElement`s and
 * accepts the union: OpenRouter's `pricing` + `context_length`, Groq/NVIDIA's
 * `context_window`, and the `:free` suffix convention.
 */
object CloudModels {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Hosts whose personal keys are free to use for chat (no per-token price). */
    private val freeTierProviders = setOf("groq", "nvidia")

    /**
     * Model ids that are free on a metered host. OpenCode Zen's `big-pickle` has
     * no `-free` suffix but is free for the whole stealth period, and Zen names
     * its rotating free tier with a `-free` suffix (OpenRouter uses `:free`).
     */
    fun isFreeId(id: String): Boolean =
        id.endsWith(":free") || id.endsWith("-free") || id == "big-pickle"

    /**
     * The `data` array of a `/models` response → sorted [CloudModel]s.
     * Free models first, then alphabetical: the picker leads with what the user
     * can actually run without paying.
     */
    fun parseModels(body: String, providerId: String): List<CloudModel> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val data = runCatching { root["data"]?.jsonArray }.getOrNull() ?: return emptyList()
        val freeTier = providerId in freeTierProviders
        return data.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val pricing = runCatching { obj["pricing"]?.jsonObject }.getOrNull()
            val promptPrice = pricing?.string("prompt")?.toDoubleOrNull()
            val completionPrice = pricing?.string("completion")?.toDoubleOrNull()
            val zeroPriced = promptPrice != null && completionPrice != null &&
                promptPrice <= 0.0 && completionPrice <= 0.0
            val name = obj.string("name") ?: id
            CloudModel(
                id = id,
                name = name,
                contextLength = obj.int("context_length") ?: obj.int("context_window") ?: 0,
                promptPrice = promptPrice,
                completionPrice = completionPrice,
                isFree = zeroPriced || isFreeId(id) || name.contains("Free") || freeTier,
            )
        }.sortedWith(compareByDescending<CloudModel> { it.isFree }.thenBy { it.displayName.lowercase() })
    }

    /** Text pieces of one streamed chat-completion chunk. */
    data class ChunkDelta(
        val content: String = "",
        val reasoning: String = "",
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val finishReason: String? = null,
    ) {
        val isEmpty: Boolean get() = content.isEmpty() && reasoning.isEmpty()
    }

    /**
     * One `data:` payload (without the prefix). Handles the OpenAI delta shape
     * plus the two reasoning spellings in the wild: `reasoning_content`
     * (DeepSeek-style) and `reasoning` (OpenRouter normalises to this).
     * Returns null for `[DONE]`, comments and unparseable lines.
     */
    fun parseChunk(payload: String): ChunkDelta? {
        val text = payload.trim()
        if (text.isEmpty() || text == "[DONE]" || text.startsWith(":")) return null
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return deltaOf(root)
    }

    /**
     * A whole non-streamed `chat/completions` body (some proxies ignore
     * `"stream": true` and answer with one JSON document). Same shape out of
     * [ChunkDelta] so the caller can route it through the same path.
     */
    fun parseCompletion(body: String): ChunkDelta? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return deltaOf(root)
    }

    /** Shared reader for one SSE chunk or one whole completion document. */
    private fun deltaOf(root: kotlinx.serialization.json.JsonObject): ChunkDelta {
        val usage = runCatching { root["usage"]?.jsonObject }.getOrNull()
        val choice = runCatching { root["choices"]?.jsonArray?.firstOrNull()?.jsonObject }.getOrNull()
        val delta = runCatching { choice?.get("delta")?.jsonObject }.getOrNull()
            ?: runCatching { choice?.get("message")?.jsonObject }.getOrNull()

        return ChunkDelta(
            content = delta?.string("content").orEmpty(),
            reasoning = delta?.string("reasoning_content") ?: delta?.string("reasoning") ?: "",
            promptTokens = usage?.int("prompt_tokens") ?: 0,
            completionTokens = usage?.int("completion_tokens") ?: 0,
            finishReason = choice?.string("finish_reason"),
        )
    }

    /** `{"error":{"message":"…"}}` (or the OpenRouter bare form) → the message. */
    fun parseError(body: String, httpCode: Int): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val message = root?.let { obj ->
            runCatching { obj["error"]?.jsonObject?.string("message") }.getOrNull()
                ?: runCatching { obj["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                ?: obj.string("message")
                ?: obj.string("detail")
        }
        return if (message.isNullOrBlank()) "HTTP $httpCode" else "HTTP $httpCode: $message"
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()
            ?: runCatching { this[key]?.jsonPrimitive?.doubleOrNull?.toInt() }.getOrNull()
}
