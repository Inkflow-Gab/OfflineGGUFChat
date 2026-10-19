package com.offlinegguf.chat.data.cloud

/**
 * Optional cloud back-ends, all speaking the OpenAI chat-completions dialect.
 *
 * Cloud is **off by default** and stays off until the user enables it and pastes
 * their own API key. With cloud disabled nothing in the app opens a socket; the
 * INTERNET permission exists solely for this opt-in path. The built-ins were
 * picked because each has a genuinely usable free tier:
 *
 * - **OpenRouter** — one key for hundreds of models, many with `:free` variants.
 * - **Groq** — LPU inference, very fast, generous free tier.
 * - **NVIDIA NIM** — free API credits on build.nvidia.com for most open models.
 * - **OpenCode Zen (Console)** — the OpenCode team's curated gateway. Paid
 *   models work from any client with a Console key; the free models
 *   (`big-pickle`, `*-free`) are restricted by OpenCode to their own app, so
 *   they are shown as app-only instead of pretending otherwise.
 *
 * The last slot is any OpenAI-compatible endpoint the user points at: a
 * llama.cpp server, LM Studio or Ollama on the same Wi-Fi, or another host.
 */
data class CloudProvider(
    val id: String,
    val label: String,
    /** API root, no trailing slash. Empty for [CUSTOM] — the user supplies it. */
    val baseUrl: String,
    /** Where to create a (free) API key. */
    val keyUrl: String,
    val blurb: String,
    val requiresKey: Boolean = true,
    val custom: Boolean = false,
    /** Discovery endpoint when it does not live at `$baseUrl/models`. */
    val modelsUrl: String? = null,
    /**
     * True when the host runs free models only for its own official client.
     * Those models are still listed (behind a toggle) but badged app-only, and
     * a refusal is explained instead of looking like a broken key.
     */
    val freeTierClientLocked: Boolean = false,
) {
    /** The API root to actually call; [customBaseUrl] only matters for [CUSTOM]. */
    fun resolveBaseUrl(customBaseUrl: String): String =
        (if (custom) customBaseUrl.trim().trimEnd('/') else baseUrl).trimEnd('/')

    companion object {
        val OPENROUTER = CloudProvider(
            id = "openrouter",
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            keyUrl = "https://openrouter.ai/keys",
            blurb = "Hundreds of models behind one key. Many have free variants — look for the FREE badge.",
        )

        val GROQ = CloudProvider(
            id = "groq",
            label = "Groq",
            baseUrl = "https://api.Groq.com/openai/v1",
            keyUrl = "https://console.Groq.com/keys",
            blurb = "LPU hardware: answers start almost instantly. The free tier is generous for chat.",
        )

        val NVIDIA = CloudProvider(
            id = "nvidia",
            label = "NVIDIA",
            baseUrl = "https://integrate.api.nvidia.com/v1",
            keyUrl = "https://build.nvidia.com/explore/discover",
            blurb = "NVIDIA-hosted open models (Llama, Qwen, DeepSeek…) with free API credits.",
        )

        val OPENCODE = CloudProvider(
            id = "opencode",
            label = "OpenCode Zen",
            // Console's documented OpenAI-compatible endpoint for other coding
            // agents. Free models still answer 403 FreeTierError here — by
            // OpenCode's design, not a bug in this app.
            baseUrl = "https://opencode.ai/inference/openai/v1",
            modelsUrl = "https://opencode.ai/inference/v1/models",
            keyUrl = "https://opencode.ai/console",
            blurb = "The OpenCode team's curated gateway. Paid models work here with a " +
                "Console key and credits; OpenCode restricts its free models to the " +
                "OpenCode app itself.",
            freeTierClientLocked = true,
        )

        val GEMINI = CloudProvider(
            id = "gemini",
            label = "Google Gemini",
            // Google's OpenAI-compatibility layer: same SDK shape as the rest,
            // key from AI Studio, generous free tier on the Flash models.
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            keyUrl = "https://aistudio.google.com/apikey",
            blurb = "Google's models through their OpenAI-compatible endpoint. " +
                "Gemini Flash models have a free tier with a key from AI Studio.",
        )

        val CEREBRAS = CloudProvider(
            id = "cerebras",
            label = "Cerebras",
            baseUrl = "https://api.cerebras.ai/v1",
            keyUrl = "https://cloud.cerebras.ai/",
            blurb = "Wafer-scale silicon: the fastest first token of any provider here. " +
                "Free tier with a personal key.",
        )

        val MISTRAL = CloudProvider(
            id = "mistral",
            label = "Mistral",
            baseUrl = "https://api.mistral.ai/v1",
            keyUrl = "https://console.mistral.ai/api-keys",
            blurb = "Mistral's own models, including the small fast ones. " +
                "The free \"Experiment\" plan is enough for chat.",
        )

        val CUSTOM = CloudProvider(
            id = "custom",
            label = "Custom",
            baseUrl = "",
            keyUrl = "",
            blurb = "Any OpenAI-compatible server: llama.cpp, LM Studio or Ollama on your PC or LAN.",
            requiresKey = false,
            custom = true,
        )

        val ALL: List<CloudProvider> =
            listOf(OPENROUTER, GEMINI, GROQ, CEREBRAS, MISTRAL, NVIDIA, OPENCODE, CUSTOM)

        fun byId(id: String?): CloudProvider = ALL.firstOrNull { it.id == id } ?: OPENROUTER
    }
}

/**
 * One model as reported by a provider's `/models` endpoint.
 *
 * Prices are per-token USD exactly as OpenRouter reports them (strings in their
 * JSON, parsed to Double here). Providers that do not publish prices (Groq,
 * NVIDIA) leave them null and are treated as free-tier: with a personal key on
 * those two hosts, chat completions cost nothing until credits/rate limits say
 * otherwise, which is the honest way to badge them.
 */
data class CloudModel(
    val id: String,
    val name: String,
    val contextLength: Int = 0,
    val promptPrice: Double? = null,
    val completionPrice: Double? = null,
    /** Free to call with a personal key (zero price, `:free` id, or free-tier host). */
    val isFree: Boolean = false,
    /**
     * Free only inside the provider's own app. OpenCode Zen blocks its free
     * tier for third-party clients, so these are listed with an honest badge
     * and hidden behind a toggle rather than offered as if they worked.
     */
    val appOnly: Boolean = false,
) {
    val displayName: String get() = name.ifBlank { id }

    /** "~$0.50 / $1.50 per 1M" style summary, or null when pricing is unknown. */
    val priceSummary: String?
        get() {
            val prompt = promptPrice ?: return null
            val completion = completionPrice ?: return null
            if (prompt <= 0.0 && completion <= 0.0) return "Free"
            return "$${trimPrice(prompt * 1_000_000)} in · $${trimPrice(completion * 1_000_000)} out / 1M tok"
        }

    private fun trimPrice(value: Double): String =
        if (value >= 100) "%.0f".format(value) else "%.2f".format(value).trimEnd('0').trimEnd('.')
}

/** The cloud model the chat screen is currently talking to. Persisted in settings. */
data class CloudTarget(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val contextLength: Int = 0,
) {
    val provider: CloudProvider get() = CloudProvider.byId(providerId)

    /** Short label for the chat top bar, e.g. "OpenRouter · DeepSeek V3 (free)". */
    val label: String get() = "${provider.label} · $displayName"
}
