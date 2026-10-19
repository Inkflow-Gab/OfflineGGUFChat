package com.offlinegguf.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.data.local.WorkspaceFileEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkspaceUiState(
    val conversationId: Long = -1L,
    val files: List<WorkspaceFileEntity> = emptyList(),
    val notice: String? = null,
    val error: String? = null,
)

/**
 * One workspace per session history: saved snippets and sandbox pages for a
 * single conversation, nothing shared between sessions.
 */
class WorkspaceViewModel(
    private val container: AppContainer,
    conversationId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkspaceUiState(conversationId = conversationId))
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.workspaceRepository.observe(conversationId).collect { files ->
                _state.update { it.copy(files = files) }
            }
        }
    }

    /** Create or overwrite by name. */
    fun save(name: String, language: String, content: String) {
        val conversationId = _state.value.conversationId
        if (conversationId <= 0) return
        viewModelScope.launch {
            container.workspaceRepository.save(conversationId, name, language, content)
            _state.update { it.copy(notice = "Saved “$name” to this session's workspace.") }
        }
    }

    fun delete(file: WorkspaceFileEntity) {
        viewModelScope.launch {
            container.workspaceRepository.delete(file.id)
            _state.update { it.copy(notice = "Deleted “${file.name}”.") }
        }
    }

    /** For failures the screen hits directly, such as a failed export. */
    fun reportError(message: String) = _state.update { it.copy(error = message) }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    companion object {
        fun factory(container: AppContainer, conversationId: Long) = viewModelFactory {
            initializer { WorkspaceViewModel(container, conversationId) }
        }
    }
}
