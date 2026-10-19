package com.offlinegguf.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.data.workspace.SandboxRequest
import kotlinx.coroutines.flow.StateFlow

/**
 * Backs the sandbox screen. The snippet to run arrives via
 * [AppContainer.pendingSandbox] (set the moment the user taps "Run" on a code
 * block), and saving writes into the workspace of the session it came from.
 */
class SandboxViewModel(private val container: AppContainer) : ViewModel() {

    val request: StateFlow<SandboxRequest?> = container.pendingSandbox

    /** Persist the running snippet into its session's workspace. */
    suspend fun saveToWorkspace(request: SandboxRequest) {
        if (request.conversationId <= 0) return
        container.workspaceRepository.save(
            conversationId = request.conversationId,
            name = request.name,
            language = request.language,
            content = request.content,
        )
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SandboxViewModel(container) }
        }
    }
}
