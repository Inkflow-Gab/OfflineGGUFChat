package com.offlinegguf.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.data.catalog.ModelAdvisor
import com.offlinegguf.chat.data.gguf.GgufReader
import com.offlinegguf.chat.data.local.LocalModelEntity
import com.offlinegguf.chat.data.repository.toSpec
import com.offlinegguf.chat.domain.device.DeviceProfile
import com.offlinegguf.chat.domain.fit.ModelFit
import com.offlinegguf.chat.inference.LoadRequest
import com.offlinegguf.chat.util.Formats
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** KV cache quantizations llama.cpp supports, in the order they are offered. */
val KvTypes = listOf("f16", "q8_0", "q4_0")

/** Draft engine + sampling settings; written to the row when the user is done. */
data class ModelTuning(
    val nCtx: Int = 0,
    val threads: Int = 0,
    val batch: Int = 0,
    val gpuLayers: Int = 0,
    val useMmap: Boolean = true,
    val kvType: String = ModelFit.DEFAULT_KV_TYPE,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 256,
    val maxTokens: Int = 512,
    val seed: Int = -1,
) {
    companion object {
        fun from(model: LocalModelEntity, suggested: ModelFit.Settings) = ModelTuning(
            nCtx = model.nCtx.takeIf { it > 0 } ?: suggested.context,
            threads = model.threads.takeIf { it > 0 } ?: suggested.threads,
            batch = model.batch.takeIf { it > 0 } ?: suggested.batch,
            gpuLayers = model.gpuLayers,
            useMmap = model.useMmap,
            kvType = model.kvType.ifBlank { suggested.kvType },
            temperature = model.temperature,
            topP = model.topP,
            topK = model.topK,
            minP = model.minP,
            repeatPenalty = model.repeatPenalty,
            repeatLastN = model.repeatLastN,
            maxTokens = model.maxTokens,
            seed = model.seed,
        )
    }
}

data class ModelDetailUiState(
    val model: LocalModelEntity? = null,
    val probe: GgufReader.Probe? = null,
    val assessment: ModelFit.Assessment? = null,
    val advice: String = "",
    val tuning: ModelTuning = ModelTuning(),
    val profile: DeviceProfile = DeviceProfile.Unknown,
    val engineAvailable: Boolean = false,
    val engineNotice: String? = null,
    val probing: Boolean = false,
    val starting: Boolean = false,
    val loaded: Boolean = false,
    val loadMillis: Long = 0,
    val error: String? = null,
    val notice: String? = null,
) {
    val fileExists: Boolean get() = model?.let { File(it.path).exists() } ?: false
}

/**
 * Header inspector and engine tuner for one file.
 *
 * Everything shown here comes from the GGUF header itself — the same sequential
 * reader the C++ preflight uses — plus the fit arithmetic in [ModelFit]. Changing
 * n_ctx re-runs that arithmetic immediately, so the KV cost of a longer context is
 * visible before the model is loaded rather than after an OOM kill.
 */
class ModelDetailViewModel(
    private val container: AppContainer,
    private val path: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ModelDetailUiState())
    val state: StateFlow<ModelDetailUiState> = _state.asStateFlow()

    private val models = container.modelRepository
    private val engine = container.engine

    init {
        _state.update {
            it.copy(
                profile = container.deviceProfile,
                engineAvailable = engine.isAvailable,
                engineNotice = engine.unavailableReason,
                probing = true,
            )
        }
        viewModelScope.launch {
            models.observeByPath(path).collect { row ->
                if (row == null) return@collect
                val probe = _state.value.probe
                val suggested = if (probe != null) {
                    ModelFit.assess(container.deviceProfile, probe.toSpec()).settings
                } else {
                    ModelFit.Settings(
                        context = container.deviceProfile.suggestedContext,
                        threads = container.deviceProfile.suggestedThreads,
                        batch = container.deviceProfile.suggestedBatch,
                        kvType = ModelFit.DEFAULT_KV_TYPE,
                    )
                }
                _state.update { current ->
                    current.copy(
                        model = row,
                        tuning = if (current.model?.path == row.path) current.tuning
                        else ModelTuning.from(row, suggested),
                        loaded = engine.loadedPath == row.path && engine.isLoaded,
                    )
                }
                if (probe == null) reprobe()
            }
        }
    }

    /** Reads the header and recomputes the fit verdict. */
    fun reprobe() {
        viewModelScope.launch {
            val file = File(path)
            if (!file.exists()) {
                _state.update { it.copy(probing = false, error = "That file is gone: $path") }
                return@launch
            }
            _state.update { it.copy(probing = true, error = null) }
            val probe = runCatching { models.probe(file) }.getOrElse {
                _state.update { state ->
                    state.copy(probing = false, error = it.message ?: "Could not read the header")
                }
                return@launch
            }
            models.probeAndStore(file, _state.value.model?.sourceUrl, _state.value.model?.catalogId)
            assess(probe, _state.value.tuning)
            _state.update {
                it.copy(
                    probe = probe,
                    probing = false,
                    notice = if (probe.metadataTruncated || probe.tensorsTruncated) {
                        "Header read partially — the file may still be downloading."
                    } else null,
                )
            }
        }
    }

    private fun assess(probe: GgufReader.Probe?, tuning: ModelTuning) {
        val target = probe ?: _state.value.probe ?: return
        val assessment = ModelFit.assess(
            profile = container.deviceProfile,
            spec = target.toSpec(),
            request = ModelFit.Request(
                context = tuning.nCtx.takeIf { it > 0 },
                threads = tuning.threads.takeIf { it > 0 },
                batch = tuning.batch.takeIf { it > 0 },
                kvType = tuning.kvType,
                storageFreeBytes = container.deviceProfile.freeStorageBytes,
            ),
        )
        _state.update {
            it.copy(
                assessment = assessment,
                advice = ModelAdvisor.adviceForInstalled(container.deviceProfile, assessment),
            )
        }
    }

    private fun retune(change: ModelTuning.() -> ModelTuning) {
        _state.update { current ->
            val next = current.tuning.change()
            current.copy(tuning = next)
        }
        assess(_state.value.probe, _state.value.tuning)
    }

    fun setContext(value: Int) = retune { copy(nCtx = value.coerceIn(256, 131_072)) }

    fun setThreads(value: Int) = retune { copy(threads = value.coerceIn(1, 16)) }

    fun setBatch(value: Int) = retune { copy(batch = value.coerceIn(32, 4096)) }

    fun setGpuLayers(value: Int) = retune { copy(gpuLayers = value.coerceIn(0, 999)) }

    fun setMmap(value: Boolean) = retune { copy(useMmap = value) }

    fun setKvType(value: String) = retune { copy(kvType = value) }

    fun setTemperature(value: Float) = retune { copy(temperature = value.coerceIn(0f, 2f)) }

    fun setTopP(value: Float) = retune { copy(topP = value.coerceIn(0.1f, 1f)) }

    fun setTopK(value: Int) = retune { copy(topK = value.coerceIn(0, 500)) }

    fun setMinP(value: Float) = retune { copy(minP = value.coerceIn(0f, 1f)) }

    fun setRepeatPenalty(value: Float) = retune { copy(repeatPenalty = value.coerceIn(0.5f, 3f)) }

    fun setMaxTokens(value: Int) = retune { copy(maxTokens = value.coerceIn(16, 8192)) }

    fun setSeed(value: Int) = retune { copy(seed = value) }

    /** Suggested defaults for this phone, from the fit engine. */
    fun useSuggested() {
        val assessment = _state.value.assessment
        val probe = _state.value.probe ?: return
        val suggested = assessment?.settings
            ?: ModelFit.assess(container.deviceProfile, probe.toSpec()).settings
        retune {
            copy(
                nCtx = suggested.context,
                threads = suggested.threads,
                batch = suggested.batch,
                kvType = suggested.kvType,
            )
        }
        saveTuning()
    }

    fun saveTuning() {
        val tuning = _state.value.tuning
        viewModelScope.launch {
            models.updateEngineSettings(
                path = path,
                nCtx = tuning.nCtx,
                threads = tuning.threads,
                batch = tuning.batch,
                gpuLayers = tuning.gpuLayers,
                useMmap = tuning.useMmap,
                kvType = tuning.kvType,
            )
            models.updateSampling(
                path = path,
                temperature = tuning.temperature,
                topP = tuning.topP,
                topK = tuning.topK,
                minP = tuning.minP,
                repeatPenalty = tuning.repeatPenalty,
                repeatLastN = tuning.repeatLastN,
                maxTokens = tuning.maxTokens,
                seed = tuning.seed,
            )
            _state.update { it.copy(notice = "Settings saved") }
        }
    }

    fun loadThenChat(onReady: () -> Unit) {
        val model = _state.value.model ?: return
        if (!engine.isAvailable) {
            _state.update { it.copy(error = it.engineNotice ?: "This build has no inference engine") }
            return
        }
        val tuning = _state.value.tuning
        viewModelScope.launch {
            saveTuningInline(tuning)
            _state.update { it.copy(starting = true, error = null) }
            val result = engine.load(
                LoadRequest(
                    path = model.path,
                    context = tuning.nCtx,
                    threads = tuning.threads,
                    batch = tuning.batch,
                    gpuLayers = tuning.gpuLayers,
                    useMmap = tuning.useMmap,
                ),
            )
            if (result.success) {
                models.markUsed(model.path)
                container.settings.setLastModelPath(model.path)
                val lastConversation = container.settings.settings.first().lastConversationId
                container.chatRepository.ensureConversation(
                    lastConversation.takeIf { it > 0 },
                    model.path,
                    model.displayName,
                )
                _state.update {
                    it.copy(
                        starting = false,
                        loaded = true,
                        loadMillis = result.elapsedMs,
                        notice = "Loaded in ${Formats.fixed(result.elapsedMs / 1000.0, 1)}s",
                    )
                }
                onReady()
            } else {
                _state.update { it.copy(starting = false, error = result.message ?: "The model failed to load") }
            }
        }
    }

    private suspend fun saveTuningInline(tuning: ModelTuning) {
        models.updateEngineSettings(
            path = path,
            nCtx = tuning.nCtx,
            threads = tuning.threads,
            batch = tuning.batch,
            gpuLayers = tuning.gpuLayers,
            useMmap = tuning.useMmap,
            kvType = tuning.kvType,
        )
        models.updateSampling(
            path = path,
            temperature = tuning.temperature,
            topP = tuning.topP,
            topK = tuning.topK,
            minP = tuning.minP,
            repeatPenalty = tuning.repeatPenalty,
            repeatLastN = tuning.repeatLastN,
            maxTokens = tuning.maxTokens,
            seed = tuning.seed,
        )
    }

    fun unload() {
        viewModelScope.launch {
            engine.unload()
            _state.update { it.copy(loaded = false, notice = "Model unloaded") }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            if (engine.loadedPath == path) engine.unload()
            models.delete(path, deleteFile = true)
            onDeleted()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    companion object {
        fun factory(container: AppContainer, path: String) = viewModelFactory {
            initializer { ModelDetailViewModel(container, path) }
        }
    }
}
