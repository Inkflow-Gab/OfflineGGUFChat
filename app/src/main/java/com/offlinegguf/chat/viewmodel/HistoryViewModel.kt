package com.offlinegguf.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.data.local.ConversationEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val conversations: List<ConversationEntity> = emptyList(),
    val archived: List<ConversationEntity> = emptyList(),
    val query: String = "",
    val showArchived: Boolean = false,
    val totalLikes: Int = 0,
    val messageTotal: Int = 0,
    val tokenTotal: Long = 0,
    val notice: String? = null,
    val error: String? = null,
) {
    /** Pinned first, then most recently updated. */
    val visible: List<ConversationEntity>
        get() {
            val source = if (showArchived) archived else conversations.filter { !it.archived }
            val needle = query.trim().lowercase()
            return source
                .filter { row ->
                    needle.isEmpty() ||
                        row.title.lowercase().contains(needle) ||
                        (row.modelName?.lowercase()?.contains(needle) == true)
                }
                .sortedWith(compareByDescending<ConversationEntity> { it.pinned }.thenByDescending { it.updatedAt })
        }
}

/** Saved chats, with the feedback totals the app keeps per conversation. */
class HistoryViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private val chat = container.chatRepository

    init {
        viewModelScope.launch {
            chat.observeConversations().collect { rows ->
                _state.update {
                    it.copy(
                        conversations = rows,
                        messageTotal = rows.sumOf { row -> row.messageCount },
                        tokenTotal = rows.sumOf { row -> row.tokenCount.toLong() },
                    )
                }
            }
        }
        viewModelScope.launch {
            chat.observeArchived().collect { rows -> _state.update { it.copy(archived = rows) } }
        }
        viewModelScope.launch {
            chat.observeTotalLikes().collect { likes -> _state.update { it.copy(totalLikes = likes) } }
        }
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun toggleArchived() = _state.update { it.copy(showArchived = !it.showArchived) }

    fun rename(id: Long, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { chat.rename(id, trimmed) }
    }

    fun setPinned(id: Long, pinned: Boolean) {
        viewModelScope.launch { chat.setPinned(id, pinned) }
    }

    fun setArchived(id: Long, archived: Boolean) {
        viewModelScope.launch {
            chat.setArchived(id, archived)
            _state.update { it.copy(notice = if (archived) "Chat archived" else "Chat restored") }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            chat.deleteConversation(id)
            _state.update { it.copy(notice = "Chat deleted") }
        }
    }

    fun deleteArchived() {
        viewModelScope.launch {
            val removed = chat.deleteArchived()
            _state.update {
                it.copy(
                    notice = if (removed > 0) "Deleted $removed archived chat(s)" else "Nothing was archived",
                    showArchived = false,
                )
            }
        }
    }

    fun clearMessages(id: Long) {
        viewModelScope.launch {
            chat.clearMessages(id)
            _state.update { it.copy(notice = "Messages cleared; the chat itself was kept") }
        }
    }

    fun exportMarkdown(id: Long, onResult: (String) -> Unit) {
        viewModelScope.launch { onResult(chat.exportMarkdown(id)) }
    }

    fun exportJson(id: Long, onResult: (String) -> Unit) {
        viewModelScope.launch { onResult(chat.exportJson(id)) }
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HistoryViewModel(container) }
        }
    }
}
