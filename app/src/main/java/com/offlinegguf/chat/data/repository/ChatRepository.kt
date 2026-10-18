package com.offlinegguf.chat.data.repository

import android.content.Context
import com.offlinegguf.chat.App
import com.offlinegguf.chat.data.local.AppDatabase
import com.offlinegguf.chat.data.local.dao.ConversationDao
import com.offlinegguf.chat.data.local.dao.MessageDao
import com.offlinegguf.chat.data.local.entity.Conversation
import com.offlinegguf.chat.data.local.entity.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ChatRepository(private val context: Context) {

    private val db: AppDatabase = AppDatabase.getDatabase(context)
    private val convDao: ConversationDao = db.conversationDao()
    private val msgDao: MessageDao = db.messageDao()

    // --- Conversations ---
    fun getActiveConversations(): Flow<List<Conversation>> = convDao.getActiveConversations()
    fun getArchivedConversations(): Flow<List<Conversation>> = convDao.getArchivedConversations()
    suspend fun getConversation(id: String): Conversation? = convDao.getConversation(id)

    suspend fun createConversation(title: String = "New Chat", systemPrompt: String? = null): String {
        val id = java.util.UUID.randomUUID().toString()
        val c = Conversation(id = id, title = title, systemPrompt = systemPrompt)
        convDao.insert(c)
        return id
    }

    suspend fun updateConversationTitle(id: String, title: String) {
        convDao.touch(id, System.currentTimeMillis(), title)
    }

    suspend fun archiveConversation(id: String) = convDao.archive(id)
    suspend fun setPinned(id: String, pinned: Boolean) = convDao.setPinned(id, pinned)
    suspend fun deleteConversation(id: String) {
        val c = convDao.getConversation(id) ?: return
        convDao.delete(c)
    }

    // --- Messages ---
    fun getMessages(conversationId: String): Flow<List<Message>> = msgDao.getMessages(conversationId)

    suspend fun addMessage(message: Message): Long = msgDao.insert(message)
    suspend fun updateMessage(message: Message) = msgDao.update(message)
    suspend fun deleteMessage(message: Message) = msgDao.delete(message)
    suspend fun stopStreaming(conversationId: String) = msgDao.stopStreaming(conversationId)

    /**
     * Append a streaming token. Detects thinking tags (<thinking>...</thinking>, <reasoning>...</reasoning>)
     * and splits them into the `thinking` field so the UI can show a separate "thinking" section.
     */
    suspend fun appendStreamingToken(conversationId: String, token: String) {
        val messages = msgDao.getMessages(conversationId).first()
        val streaming = messages.lastOrNull { it.role == "assistant" && it.isStreaming == 1 }
        if (streaming != null) {
            val newContent = streaming.content + token
            val newThinking = extractThinking(newContent)
            val visible = stripThinking(newContent)
            msgDao.update(streaming.copy(content = visible, thinking = newThinking))
        } else {
            val newThinking = extractThinking(token)
            val visible = stripThinking(token)
            val newMsg = Message(
                conversationId = conversationId,
                role = "assistant",
                content = visible,
                thinking = newThinking,
                isStreaming = 1
            )
            msgDao.insert(newMsg)
        }
    }

    suspend fun finalizeStreaming(conversationId: String) {
        val messages = msgDao.getMessages(conversationId).first()
        val streaming = messages.lastOrNull { it.role == "assistant" && it.isStreaming == 1 }
        if (streaming != null) {
            msgDao.update(streaming.copy(isStreaming = 0))
        }
    }

    // ---------- Thinking tag helpers ----------
    private fun extractThinking(full: String): String? {
        val tags = listOf("<thinking>" to "</thinking>", "<reasoning>" to "</reasoning>")
        for ((open, close) in tags) {
            val start = full.indexOf(open)
            if (start >= 0) {
                val end = full.indexOf(close, start + open.length)
                if (end >= 0) {
                    return full.substring(start + open.length, end).trim()
                }
                // still open — return what we have so far
                return full.substring(start + open.length).trim()
            }
        }
        return null
    }

    private fun stripThinking(full: String): String {
        var result = full
        val tags = listOf("<thinking>" to "</thinking>", "<reasoning>" to "</reasoning>")
        for ((open, close) in tags) {
            var idx = result.indexOf(open)
            while (idx >= 0) {
                val end = result.indexOf(close, idx + open.length)
                if (end >= 0) {
                    result = result.substring(0, idx) + result.substring(end + close.length)
                } else {
                    result = result.substring(0, idx)
                }
                idx = result.indexOf(open)
            }
        }
        return result.trim()
    }
}
