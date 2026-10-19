package com.offlinegguf.chat.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.offlinegguf.chat.data.catalog.ModelStyle
import com.offlinegguf.chat.inference.SamplingParams
import com.offlinegguf.chat.ui.theme.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Prompt and sampling preset selected from the chat toolbar. */
enum class ChatMode(val label: String, val instruction: String) {
    BALANCED("Balanced", "Be accurate and useful. Answer the actual question first, separate known facts from assumptions, and say when you are uncertain."),
    FAST("Fast", "Give the shortest useful answer first. Skip background unless requested, and avoid repeating the question."),
    CHAT("Chat", "Use a natural, warm conversational style. Keep the exchange engaging without inventing personal experiences or facts."),
    CODING("Coding", "Act as a careful coding partner. Give runnable, maintainable code, preserve the user's conventions, and explain important assumptions or risks. For complete files of 50 or more lines, include the filename in the opening code fence, for example ```python main.py. Never claim code was tested unless it was."),
    CAREFUL("Deep thinking", "Prioritize correctness over speed. Check the request for ambiguity, reason through relevant constraints, and present a concise conclusion with uncertainty clearly stated.");

    companion object {
        fun fromName(name: String?): ChatMode = values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BALANCED
    }
}

/** Immutable snapshot of every user preference. */
data class AppSettings(
    // ---- appearance ----
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontScale: Float = 1.0f,
    val reduceMotion: Boolean = false,
    val highContrast: Boolean = false,
    val showLineNumbers: Boolean = true,

    // ---- chat behaviour ----
    val showThinking: Boolean = true,
    val chatMode: ChatMode = ChatMode.BALANCED,
    val keepScreenOn: Boolean = false,
    val autoLoadLastModel: Boolean = true,
    val confirmBeforeDelete: Boolean = true,
    val haptics: Boolean = true,

    // ---- generation defaults ----
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 512,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,

    // ---- model discovery ----
    val advisorStyle: String = ModelStyle.GENERAL.id,
    val advisorUncensored: Boolean = false,
    val advisorPreferSpeed: Boolean = true,
    val advisorMaxDownloadGb: Int = 8,

    // ---- state ----
    val lastConversationId: Long = -1L,
    val lastModelPath: String? = null,
    val onboarded: Boolean = false,
) {
    fun style(): ModelStyle = ModelStyle.fromId(advisorStyle)

    fun sampling(): SamplingParams = SamplingParams(
        maxTokens = maxTokens,
        temperature = temperature,
        topP = topP,
        topK = topK,
        minP = minP,
        repeatPenalty = repeatPenalty,
    )

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a concise, practical assistant running entirely on this device. " +
                "Answer directly, prefer short paragraphs and lists, and put code in fenced blocks. " +
                "If you are unsure, say so."

        const val DEFAULT_MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024 * 1024
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Preferences backed by Jetpack DataStore.
 *
 * Reads never throw: a corrupted store falls back to [AppSettings] defaults
 * rather than leaving the user with a blank screen.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        val SHOW_LINE_NUMBERS = booleanPreferencesKey("show_line_numbers")

        val SHOW_THINKING = booleanPreferencesKey("show_thinking")
        val CHAT_MODE = stringPreferencesKey("chat_mode")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val AUTO_LOAD_LAST = booleanPreferencesKey("auto_load_last_model")
        val CONFIRM_DELETE = booleanPreferencesKey("confirm_before_delete")
        val HAPTICS = booleanPreferencesKey("haptics")

        val TEMPERATURE = floatPreferencesKey("temperature")
        val TOP_P = floatPreferencesKey("top_p")
        val TOP_K = intPreferencesKey("top_k")
        val MIN_P = floatPreferencesKey("min_p")
        val REPEAT_PENALTY = floatPreferencesKey("repeat_penalty")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")

        val ADVISOR_STYLE = stringPreferencesKey("advisor_style")
        val ADVISOR_UNCENSORED = booleanPreferencesKey("advisor_uncensored")
        val ADVISOR_PREFER_SPEED = booleanPreferencesKey("advisor_prefer_speed")
        val ADVISOR_MAX_GB = intPreferencesKey("advisor_max_download_gb")

        val LAST_CONVERSATION = intPreferencesKey("last_conversation_id")
        val LAST_MODEL_PATH = stringPreferencesKey("last_model_path")
        val ONBOARDED = booleanPreferencesKey("onboarded")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { cause ->
            // DataStore throws IOException on a corrupted file; start clean.
            if (cause is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw cause
        }
        .map { prefs ->
            AppSettings(
                themeMode = ThemeMode.fromName(prefs[Keys.THEME_MODE]),
                fontScale = prefs[Keys.FONT_SCALE] ?: 1.0f,
                reduceMotion = prefs[Keys.REDUCE_MOTION] ?: false,
                highContrast = prefs[Keys.HIGH_CONTRAST] ?: false,
                showLineNumbers = prefs[Keys.SHOW_LINE_NUMBERS] ?: true,
                showThinking = prefs[Keys.SHOW_THINKING] ?: true,
                chatMode = ChatMode.fromName(prefs[Keys.CHAT_MODE]),
                keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: false,
                autoLoadLastModel = prefs[Keys.AUTO_LOAD_LAST] ?: true,
                confirmBeforeDelete = prefs[Keys.CONFIRM_DELETE] ?: true,
                haptics = prefs[Keys.HAPTICS] ?: true,
                temperature = prefs[Keys.TEMPERATURE] ?: 0.7f,
                topP = prefs[Keys.TOP_P] ?: 0.95f,
                topK = prefs[Keys.TOP_K] ?: 40,
                minP = prefs[Keys.MIN_P] ?: 0.05f,
                repeatPenalty = prefs[Keys.REPEAT_PENALTY] ?: 1.1f,
                maxTokens = prefs[Keys.MAX_TOKENS] ?: 512,
                systemPrompt = prefs[Keys.SYSTEM_PROMPT] ?: AppSettings.DEFAULT_SYSTEM_PROMPT,
                advisorStyle = prefs[Keys.ADVISOR_STYLE] ?: ModelStyle.GENERAL.id,
                advisorUncensored = prefs[Keys.ADVISOR_UNCENSORED] ?: false,
                advisorPreferSpeed = prefs[Keys.ADVISOR_PREFER_SPEED] ?: true,
                advisorMaxDownloadGb = prefs[Keys.ADVISOR_MAX_GB] ?: 8,
                lastConversationId = (prefs[Keys.LAST_CONVERSATION] ?: -1).toLong(),
                lastModelPath = prefs[Keys.LAST_MODEL_PATH],
                onboarded = prefs[Keys.ONBOARDED] ?: false,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setFontScale(scale: Float) = edit { it[Keys.FONT_SCALE] = scale.coerceIn(0.85f, 1.35f) }
    suspend fun setReduceMotion(value: Boolean) = edit { it[Keys.REDUCE_MOTION] = value }
    suspend fun setHighContrast(value: Boolean) = edit { it[Keys.HIGH_CONTRAST] = value }
    suspend fun setShowLineNumbers(value: Boolean) = edit { it[Keys.SHOW_LINE_NUMBERS] = value }
    suspend fun setShowThinking(value: Boolean) = edit { it[Keys.SHOW_THINKING] = value }
    suspend fun setChatMode(mode: ChatMode) = edit { it[Keys.CHAT_MODE] = mode.name }
    suspend fun setKeepScreenOn(value: Boolean) = edit { it[Keys.KEEP_SCREEN_ON] = value }
    suspend fun setAutoLoadLastModel(value: Boolean) = edit { it[Keys.AUTO_LOAD_LAST] = value }
    suspend fun setConfirmBeforeDelete(value: Boolean) = edit { it[Keys.CONFIRM_DELETE] = value }
    suspend fun setHaptics(value: Boolean) = edit { it[Keys.HAPTICS] = value }

    suspend fun setSampling(params: SamplingParams) = edit {
        it[Keys.TEMPERATURE] = params.temperature.coerceIn(0f, 2f)
        it[Keys.TOP_P] = params.topP.coerceIn(0f, 1f)
        it[Keys.TOP_K] = params.topK.coerceIn(0, 200)
        it[Keys.MIN_P] = params.minP.coerceIn(0f, 1f)
        it[Keys.REPEAT_PENALTY] = params.repeatPenalty.coerceIn(0.5f, 2f)
        it[Keys.MAX_TOKENS] = params.maxTokens.coerceIn(16, 8192)
    }

    suspend fun setMaxTokens(value: Int) = edit { it[Keys.MAX_TOKENS] = value.coerceIn(16, 8192) }
    suspend fun setTemperature(value: Float) = edit { it[Keys.TEMPERATURE] = value.coerceIn(0f, 2f) }
    suspend fun setSystemPrompt(value: String) = edit { it[Keys.SYSTEM_PROMPT] = value }

    suspend fun setAdvisorStyle(style: ModelStyle) = edit { it[Keys.ADVISOR_STYLE] = style.id }
    suspend fun setAdvisorUncensored(value: Boolean) = edit { it[Keys.ADVISOR_UNCENSORED] = value }
    suspend fun setAdvisorPreferSpeed(value: Boolean) = edit { it[Keys.ADVISOR_PREFER_SPEED] = value }
    suspend fun setAdvisorMaxDownloadGb(value: Int) = edit { it[Keys.ADVISOR_MAX_GB] = value.coerceIn(1, 64) }

    suspend fun setLastConversation(id: Long) = edit {
        it[Keys.LAST_CONVERSATION] = if (id > Int.MAX_VALUE) Int.MAX_VALUE else id.toInt()
    }

    suspend fun setLastModelPath(path: String?) = edit { prefs ->
        if (path == null) prefs.remove(Keys.LAST_MODEL_PATH) else prefs[Keys.LAST_MODEL_PATH] = path
    }

    suspend fun setOnboarded(value: Boolean) = edit { it[Keys.ONBOARDED] = value }

    suspend fun resetToDefaults() = edit { it.clear() }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { prefs -> block(prefs) }
    }
}
