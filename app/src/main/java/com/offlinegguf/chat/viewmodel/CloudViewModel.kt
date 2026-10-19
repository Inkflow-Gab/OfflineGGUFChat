package com.offlinegguf.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.data.cloud.CloudModel
import com.offlinegguf.chat.data.cloud.CloudProvider
import com.offlinegguf.chat.data.cloud.CloudTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudUiState(
    val enabled: Boolean = false,
    val providerId: String = CloudProvider.OPENROUTER.id,
    /** The key text field's content for the selected provider. Saved as typed. */
    val keyDraft: String = "",
    val models: List<CloudModel> = emptyList(),
    val modelsProviderId: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val query: String = "",
    val freeOnly: Boolean = true,
    val customBaseUrl: String = "",
    val activeTarget: CloudTarget? = null,
) {
    val provider: CloudProvider get() = CloudProvider.byId(providerId)

    val hasKey: Boolean get() = keyDraft.isNotBlank()

    /** What the picker shows after the free filter and the search box. */
    val visibleModels: List<CloudModel>
        get() = models.filter { model ->
            (!freeOnly || model.isFree) &&
                (query.isBlank() ||
                    model.displayName.contains(query, ignoreCase = true) ||
                    model.id.contains(query, ignoreCase = true))
        }

    val freeCount: Int get() = models.count { it.isFree }
}

/**
 * The Cloud tab: provider + key setup and the free-model picker.
 *
 * Everything network-shaped happens here and in [com.offlinegguf.chat.data.cloud.CloudChatEngine];
 * nowhere else in the app opens a socket. Keys are stored per provider in
 * DataStore (outside the settings snapshot) and only read back for this screen
 * and for a cloud generation in the chat view model.
 */
class CloudViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(CloudUiState())
    val state: StateFlow<CloudUiState> = _state.asStateFlow()

    /** Stored keys by provider id, used to refill the field on provider switch. */
    private var storedKeys: Map<String, String> = emptyMap()

    /**
     * Per-provider draft of what has been typed, mirroring DataStore in memory.
     * Switching providers swaps the field instantly from here — the key you
     * typed for one provider is never lost when you hop to another, even in the
     * milliseconds before the DataStore write round-trips back.
     */
    private val drafts: MutableMap<String, String> = mutableMapOf()

    init {
        viewModelScope.launch {
            container.settings.settings.collect { settings ->
                _state.update { current ->
                    current.copy(
                        enabled = settings.cloudEnabled,
                        freeOnly = settings.cloudFreeOnly,
                        customBaseUrl = settings.cloudCustomBaseUrl,
                        activeTarget = settings.cloudTarget(),
                    )
                }
                maybeAutoFetch()
            }
        }
        viewModelScope.launch {
            container.settings.cloudApiKeys.collect { keys ->
                storedKeys = keys
                keys.forEach { (providerId, key) ->
                    if (!drafts.containsKey(providerId)) drafts[providerId] = key
                }
                // Refill only when the field still shows the previously stored
                // value (or nothing), so typing is never overwritten mid-stream.
                _state.update { current ->
                    val draft = drafts[current.providerId].orEmpty()
                    if (current.keyDraft.isBlank() || current.keyDraft == storedKeys[current.providerId]) {
                        current.copy(keyDraft = draft)
                    } else {
                        current
                    }
                }
                maybeAutoFetch()
            }
        }
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch {
            container.settings.setCloudEnabled(value)
            if (value) maybeAutoFetch()
        }
    }

    fun selectProvider(providerId: String) {
        if (providerId == _state.value.providerId) return
        val previous = _state.value
        // Flush whatever was typed under the old provider before swapping, so a
        // quick "type key, switch provider" can never file the key under the
        // wrong id or drop it.
        if (previous.keyDraft.isNotBlank()) {
            drafts[previous.providerId] = previous.keyDraft
            viewModelScope.launch {
                container.settings.setCloudApiKey(previous.providerId, previous.keyDraft)
            }
        }
        _state.update {
            it.copy(
                providerId = providerId,
                keyDraft = drafts[providerId] ?: storedKeys[providerId].orEmpty(),
                error = null,
            )
        }
        viewModelScope.launch {
            container.settings.setCloudProvider(providerId)
            maybeAutoFetch()
        }
    }

    fun onKeyChange(value: String) {
        val providerId = _state.value.providerId
        drafts[providerId] = value
        _state.update { it.copy(keyDraft = value) }
        viewModelScope.launch {
            container.settings.setCloudApiKey(providerId, value)
        }
    }

    fun onCustomUrlChange(value: String) {
        _state.update { it.copy(customBaseUrl = value) }
        viewModelScope.launch { container.settings.setCloudCustomBaseUrl(value) }
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    fun setFreeOnly(value: Boolean) {
        _state.update { it.copy(freeOnly = value) }
        viewModelScope.launch { container.settings.setCloudFreeOnly(value) }
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Fetch the model list for the selected provider. Also the "test" action. */
    fun refreshModels() {
        val current = _state.value
        if (current.loading) return
        val provider = current.provider
        if (provider.requiresKey && current.keyDraft.isBlank()) {
            _state.update { it.copy(error = "Paste your ${provider.label} API key first — it is free to create.") }
            return
        }
        if (provider.custom && current.customBaseUrl.isBlank()) {
            _state.update { it.copy(error = "Enter the server URL, e.g. http://192.168.1.10:11434/v1") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = container.cloudEngine.fetchModels(
                provider = provider,
                customBaseUrl = current.customBaseUrl,
                apiKey = current.keyDraft.trim(),
            )
            result.onSuccess { models ->
                _state.update {
                    it.copy(
                        loading = false,
                        models = models,
                        modelsProviderId = provider.id,
                        notice = "${provider.label}: ${models.size} models, ${models.count { m -> m.isFree }} free.",
                    )
                }
            }.onFailure { failure ->
                _state.update {
                    it.copy(loading = false, error = failure.message ?: "Could not reach ${provider.label}")
                }
            }
        }
    }

    /** Make [model] the thing the Chat tab talks to (and turn cloud on). */
    fun selectModel(model: CloudModel) {
        val current = _state.value
        val target = CloudTarget(
            providerId = current.providerId,
            modelId = model.id,
            displayName = model.displayName,
            contextLength = model.contextLength,
        )
        viewModelScope.launch {
            container.settings.setCloudTarget(target)
            container.settings.setCloudEnabled(true)
            _state.update {
                it.copy(notice = "Now chatting with ${model.displayName} via ${current.provider.label}. Open the Chat tab.")
            }
        }
    }

    /** Back to on-device models only. */
    fun clearTarget() {
        viewModelScope.launch { container.settings.setCloudTarget(null) }
    }

    /** First-open convenience: with a key already stored, load the list once. */
    private fun maybeAutoFetch() {
        val current = _state.value
        val ready = current.keyDraft.isNotBlank() || !current.provider.requiresKey
        if (current.enabled && ready && !current.loading && current.modelsProviderId != current.providerId) {
            refreshModels()
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { CloudViewModel(container) }
        }
    }
}
