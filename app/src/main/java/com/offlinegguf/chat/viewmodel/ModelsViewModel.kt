package com.offlinegguf.chat.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.data.local.LocalModelEntity
import com.offlinegguf.chat.data.repository.ImportResult
import com.offlinegguf.chat.domain.device.DeviceProfile
import com.offlinegguf.chat.domain.fit.ModelFit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Live copy progress for a GGUF being pulled into app storage. */
data class ImportProgress(val name: String, val copied: Long, val total: Long) {
    val fraction: Float get() = if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else 0f
    val unknownTotal: Boolean get() = total <= 0
    val label: String
        get() = if (unknownTotal) {
            "${Formats.bytes(copied)} copied"
        } else {
            "${Formats.bytes(copied)} of ${Formats.bytes(total)}"
        }
}

enum class ModelSort(val label: String) {
    RECENT("Recently added"),
    NAME("Name"),
    SIZE("Smallest first"),
    USED("Recently used"),
}

data class ModelsUiState(
    val models: List<LocalModelEntity> = emptyList(),
    val query: String = "",
    val sort: ModelSort = ModelSort.RECENT,
    val onlyLoadable: Boolean = false,
    val storageUsed: Long = 0,
    val scanning: Boolean = false,
    val importing: ImportProgress? = null,
    val error: String? = null,
    val notice: String? = null,
    val profile: DeviceProfile = DeviceProfile.Unknown,
    val engineAvailable: Boolean = false,
    val engineNotice: String? = null,
) {
    val visible: List<LocalModelEntity>
        get() {
            val needle = query.trim().lowercase()
            val filtered = models.filter { model ->
                (!onlyLoadable || (model.valid && File(model.path).exists())) &&
                    (needle.isEmpty() ||
                        model.displayName.lowercase().contains(needle) ||
                        model.fileName.lowercase().contains(needle) ||
                        model.architecture.lowercase().contains(needle) ||
                        model.quantization.lowercase().contains(needle))
            }
            return when (sort) {
                ModelSort.RECENT -> filtered.sortedByDescending { it.addedAt }
                ModelSort.NAME -> filtered.sortedBy { it.displayName.lowercase() }
                ModelSort.SIZE -> filtered.sortedBy { it.sizeBytes }
                ModelSort.USED -> filtered.sortedByDescending { it.lastUsedAt }
            }
        }

    val loadableCount: Int get() = models.count { it.valid }
    val busy: Boolean get() = scanning || importing != null
}

/**
 * Local model library: import, scan, inspect, delete.
 *
 * Importing from a file manager is a real copy into app storage — SAF grants are
 * revoked as soon as the picker closes, so a file that is only "referenced" would
 * stop working the next time the app opens. Progress is reported per 64 KB chunk.
 */
class ModelsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    private val models = container.modelRepository
    private var importJob: Job? = null

    init {
        _state.update {
            it.copy(
                profile = container.deviceProfile,
                engineAvailable = container.engine.isAvailable,
                engineNotice = container.engine.unavailableReason,
            )
        }
        viewModelScope.launch {
            models.observeAll().collect { rows ->
                _state.update { it.copy(models = rows, storageUsed = rows.sumOf { m -> m.sizeBytes }) }
            }
        }
        viewModelScope.launch { refreshStorage() }
        drainPendingImport()
    }

    /** The app was opened from a file manager ("Open with" / share). */
    private fun drainPendingImport() {
        viewModelScope.launch {
            container.pendingImport.collect { uri ->
                if (uri != null) {
                    container.clearPendingImport()
                    importFrom(uri)
                }
            }
        }
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun setSort(value: ModelSort) = _state.update { it.copy(sort = value) }

    fun toggleOnlyLoadable() = _state.update { it.copy(onlyLoadable = !it.onlyLoadable) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    private suspend fun refreshStorage() {
        val used = models.storageUsedBytes()
        _state.update { it.copy(storageUsed = used) }
    }

    fun importFrom(uri: Uri, sourceUrl: String? = null, catalogId: String? = null) {
        if (_state.value.importing != null) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    importing = ImportProgress("gguf", 0L, -1L),
                    error = null,
                )
            }
            val result = models.importFrom(
                uri = uri,
                sourceUrl = sourceUrl,
                catalogId = catalogId,
                onProgress = { copied, total ->
                    _state.update { current ->
                        val name = current.importing?.name ?: "gguf"
                        current.copy(importing = ImportProgress(name, copied, total))
                    }
                },
            )
            refreshStorage()
            when (result) {
                is ImportResult.Success -> _state.update {
                    it.copy(
                        importing = null,
                        notice = "Imported ${result.model.displayName} " +
                            "(${Formats.bytes(result.model.sizeBytes)})",
                    )
                }

                is ImportResult.Failed -> _state.update {
                    it.copy(importing = null, error = result.message)
                }
            }
        }
    }

    fun scanFolder() {
        viewModelScope.launch {
            _state.update { it.copy(scanning = true, error = null) }
            val report = models.scan()
            refreshStorage()
            _state.update {
                it.copy(
                    scanning = false,
                    notice = when {
                        report.added > 0 -> "Added ${report.added} model${if (report.added == 1) "" else "s"}" +
                            if (report.updated > 0) ", refreshed ${report.updated}" else ""
                        report.scanned == 0 -> "No .gguf files in the models folder"
                        else -> "Nothing new — ${report.scanned} file(s) already known"
                    },
                    error = report.errors.firstOrNull(),
                )
            }
        }
    }

    fun rename(path: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { models.rename(path, trimmed) }
    }

    fun delete(path: String, deleteFile: Boolean = true) {
        viewModelScope.launch {
            val removed = models.delete(path, deleteFile)
            refreshStorage()
            _state.update {
                it.copy(notice = if (removed) "Model removed" else "Row removed; the file stayed on disk")
            }
        }
    }

    /** Fit verdict from the header already stored — no re-read of the file. */
    fun fitFor(model: LocalModelEntity): ModelFit.Assessment {
        val spec = ModelFit.Spec(
            weightBytes = if (model.weightBytes > 0) model.weightBytes else model.sizeBytes,
            paramCount = model.paramCount,
            contextLength = model.contextLength.toLong(),
            blockCount = model.blockCount.toLong(),
            embeddingLength = model.embeddingLength,
            quantization = model.quantization,
            hasChatTemplate = model.hasChatTemplate,
            multimodal = model.multimodal,
            vocabSize = model.vocabSize.toLong(),
            valid = model.valid,
            error = model.errorText,
        )
        return ModelFit.assess(
            profile = _state.value.profile,
            spec = spec,
            request = ModelFit.Request(storageFreeBytes = _state.value.profile.freeStorageBytes),
        )
    }

    /** Re-reads the header; used by the detail screen's "Refresh" action. */
    fun reprobe(path: String) {
        viewModelScope.launch {
            val file = File(path)
            if (!file.exists()) {
                _state.update { it.copy(error = "That file is gone") }
                return@launch
            }
            val probe = models.probe(file)
            val existing = models.byPath(path)
            models.probeAndStore(file, existing?.sourceUrl, existing?.catalogId)
            _state.update { it.copy(notice = "Header re-read: ${probe.architecture.ifBlank { "unknown" }}") }
        }
    }

    fun modelsDirPath(): String = models.modelsDir.absolutePath

    override fun onCleared() {
        importJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ModelsViewModel(container) }
        }
    }
}
