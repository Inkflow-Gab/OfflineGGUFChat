package com.offlinegguf.chat.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinegguf.chat.data.repository.ChatRepository
import com.offlinegguf.chat.data.local.entity.Conversation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HistoryViewModel(private val repository: ChatRepository) : ViewModel() {

    val conversations = repository.getActiveConversations()
    val archived = repository.getArchivedConversations()

    fun archive(id: String) = viewModelScope.launch { repository.archiveConversation(id) }
    fun setPinned(id: String, pinned: Boolean) = viewModelScope.launch { repository.setPinned(id, pinned) }
    fun delete(id: String) = viewModelScope.launch { repository.deleteConversation(id) }

    fun createNew(title: String = "New Chat") = viewModelScope.launch {
        repository.createConversation(title = title)
    }
}
