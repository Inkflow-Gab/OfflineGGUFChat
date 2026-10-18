package com.offlinegguf.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.BuildConfig
import com.offlinegguf.chat.data.settings.AppSettings
import com.offlinegguf.chat.domain.device.DeviceProbe
import com.offlinegguf.chat.domain.device.DeviceProfile
import com.offlinegguf.chat.inference.SamplingParams
import com.offlinegguf.chat.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val engineAvailable: Boolean = false,
    val engineVersion: String = "",
    val engineNotice: String? = null,
    val engineLoaded: Boolean = false,
    val engineModelPath: String? = null,
    val profile: DeviceProfile = DeviceProfile.Unknown,
    val profileSummary: String = "",
    val storageUsedByModels: Long = 0,
    val modelCount: Int = 0,
    val conversationCount: Int = 0,
    val messageCount: Int = 0,
    val totalLikes: Int = 0,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val notice: String? = null,
    val error: String? = null,
)

/**
 * Preferences, engine status and the data ledger.
 *
 * The engine section prints [com.offlinegguf.chat.inference.NativeLlamaEngine.unavailableReason]
 * verbatim: when llama.cpp is not compiled in, the message names the script and the
 * Gradle flag that turn it on, rather than a vague "not supported".
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val store = container.settings

    init {
        _state.update {
            it.copy(
                engineAvailable = container.engine.isAvailable,
                engineVersion = container.engine.version,
                engineNotice = container.engine.unavailableReason,
                profile = container.deviceProfile,
                profileSummary = DeviceProbe.describe(container.deviceProfile),
                engineLoaded = container.engine.isLoaded,
                engineModelPath = container.engine.loadedPath,
            )
        }
        viewModelScope.launch {
            store.settings.collect { settings -> _state.update { it.copy(settings = settings) } }
        }
        viewModelScope.launch {
            container.modelRepository.observeAll().collect { rows ->
                _state.update {
                    it.copy(modelCount = rows.size, storageUsedByModels = rows.sumOf { m -> m.sizeBytes })
                }
            }
        }
        viewModelScope.launch {
            container.chatRepository.observeConversations().collect { rows ->
                _state.update {
                    it.copy(
                        conversationCount = rows.size,
                        messageCount = rows.sumOf { row -> row.messageCount },
                    )
                }
            }
        }
        viewModelScope.launch {
            container.chatRepository.observeTotalLikes().collect { likes ->
                _state.update { it.copy(totalLikes = likes) }
            }
        }
    }

    // ------------------------------------------------------------ appearance

    fun setThemeMode(mode: ThemeMode) = launchEdit { store.setThemeMode(mode) }

    fun setFontScale(scale: Float) = launchEdit { store.setFontScale(scale) }

    fun setReduceMotion(value: Boolean) = launchEdit { store.setReduceMotion(value) }

    fun setHighContrast(value: Boolean) = launchEdit { store.setHighContrast(value) }

    // --------------------------------------------------------------- reading

    fun setShowLineNumbers(value: Boolean) = launchEdit { store.setShowLineNumbers(value) }

    fun setShowThinking(value: Boolean) = launchEdit { store.setShowThinking(value) }

    fun setKeepScreenOn(value: Boolean) = launchEdit { store.setKeepScreenOn(value) }

    fun setAutoLoadLastModel(value: Boolean) = launchEdit { store.setAutoLoadLastModel(value) }

    fun setConfirmBeforeDelete(value: Boolean) = launchEdit { store.setConfirmBeforeDelete(value) }

    fun setHaptics(value: Boolean) = launchEdit { store.setHaptics(value) }

    // ------------------------------------------------------------ generation

    fun setTemperature(value: Float) = launchEdit { store.setTemperature(value) }

    fun setMaxTokens(value: Int) = launchEdit { store.setMaxTokens(value) }

    fun setSystemPrompt(value: String) = launchEdit { store.setSystemPrompt(value) }

    fun restoreSystemPrompt() = launchEdit { store.setSystemPrompt(AppSettings.DEFAULT_SYSTEM_PROMPT) }

    fun setTopP(value: Float) = withSampling { it.copy(topP = value) }

    fun setTopK(value: Int) = withSampling { it.copy(topK = value) }

    fun setMinP(value: Float) = withSampling { it.copy(minP = value) }

    fun setRepeatPenalty(value: Float) = withSampling { it.copy(repeatPenalty = value) }

    private fun withSampling(change: SamplingParams.() -> SamplingParams) = launchEdit {
        store.setSampling(_state.value.settings.sampling().change())
    }

    private fun launchEdit(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "Could not save that setting") }
            }
        }
    }

    // ------------------------------------------------------------------ data

    fun deleteArchivedConversations() {
        viewModelScope.launch {
            val removed = container.chatRepository.deleteArchived()
            _state.update {
                it.copy(notice = if (removed > 0) "Deleted $removed archived chat(s)" else "Nothing was archived")
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            container.engine.unload()
            _state.update {
                it.copy(
                    engineLoaded = false,
                    engineModelPath = null,
                    notice = "Model unloaded; its memory is released",
                )
            }
        }
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun storageSummary(): String = buildString {
        append("${Formats.bytes(_state.value.storageUsedByModels)} in models")
        append(" · ${Formats.bytes(_state.value.profile.freeStorageBytes)} free")
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SettingsViewModel(container) }
        }
    }
}
