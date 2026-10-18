package com.offlinegguf.chat.data.repository

import androidx.room.withTransaction
import com.offlinegguf.chat.data.local.AppDatabase
import com.offlinegguf.chat.data.local.ConversationEntity
import com.offlinegguf.chat.data.local.MessageEntity
import com.offlinegguf.chat.data.local.MessageStatus
import com.offlinegguf.chat.data.local.Roles
import com.offlinegguf.chat.inference.GenerationOutcome
import com.offlinegguf.chat.inference.GenerationStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chat persistence: conversations, messages, feedback and export.
 *
 * Streaming updates go through [appendStream] and [finishMessage], which write
 * only the columns that changed. Rewriting the whole row per token — what an
 * earlier version did — turned a 400-token answer into hundreds of full-row
 * writes and made the transcript stutter.
 */
class ChatRepository(private val db: AppDatabase) {

    private val conversations = db.conversations()
    private val messages = db.messages()

    // ------------------------------------------------------------- observe

    fun observeConversations(): Flow<List<ConversationEntity>> = conversations.observeAll()

    fun observeArchived(): Flow<List<ConversationEntity>> = conversations.observeArchived()

    fun observeConversation(id: Long): Flow<ConversationEntity?> = conversations.observeById(id)

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        messages.observeFor(conversationId)

    fun observeMessage(id: Long): Flow<MessageEntity?> = messages.observeById(id)

    fun observeConversationCount(): Flow<Int> = conversations.observeCount()

    /** Total likes across the whole app — the number on the history screen. */
    fun observeTotalLikes(): Flow<Int> = conversations.observeTotalLikes()

    suspend fun conversation(id: Long): ConversationEntity? =
        withContext(Dispatchers.IO) { conversations.findById(id) }

    suspend fun messages(conversationId: Long): List<MessageEntity> =
        withContext(Dispatchers.IO) { messages.listFor(conversationId) }

    suspend fun message(id: Long): MessageEntity? = withContext(Dispatchers.IO) { messages.findById(id) }

    suspend fun mostRecentConversationId(): Long? =
        withContext(Dispatchers.IO) { conversations.mostRecentId() }

    // --------------------------------------------------------------- write

    suspend fun createConversation(
        title: String = "New chat",
        modelPath: String? = null,
        modelName: String? = null,
        systemPrompt: String = "",
        skillId: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        conversations.insert(
            ConversationEntity(
                title = title.ifBlank { "New chat" },
                modelPath = modelPath,
                modelName = modelName,
                systemPrompt = systemPrompt,
                skillId = skillId,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** Returns [id] when it exists, otherwise creates a fresh conversation. */
    suspend fun ensureConversation(id: Long?, modelPath: String?, modelName: String?): Long =
        withContext(Dispatchers.IO) {
            if (id != null && id > 0 && conversations.findById(id) != null) return@withContext id
            val now = System.currentTimeMillis()
            conversations.insert(
                ConversationEntity(modelPath = modelPath, modelName = modelName, createdAt = now, updatedAt = now),
            )
        }

    suspend fun rename(id: Long, title: String) = withContext(Dispatchers.IO) {
        conversations.rename(id, title.trim().ifBlank { "Untitled" }, System.currentTimeMillis())
    }

    suspend fun setModel(id: Long, path: String?, name: String?) = withContext(Dispatchers.IO) {
        conversations.setModel(id, path, name, System.currentTimeMillis())
    }

    suspend fun setPrompt(id: Long, systemPrompt: String, skillId: String?) = withContext(Dispatchers.IO) {
        conversations.setPrompt(id, systemPrompt, skillId, System.currentTimeMillis())
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = withContext(Dispatchers.IO) {
        conversations.setPinned(id, pinned)
    }

    suspend fun setArchived(id: Long, archived: Boolean) = withContext(Dispatchers.IO) {
        conversations.setArchived(id, archived)
    }

    suspend fun deleteConversation(id: Long) = withContext(Dispatchers.IO) {
        // Messages go too: the foreign key cascades.
        conversations.deleteById(id)
    }

    suspend fun deleteArchived(): Int = withContext(Dispatchers.IO) { conversations.deleteArchived() }

    suspend fun clearMessages(conversationId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            messages.clear(conversationId)
            conversations.setStats(conversationId, 0, 0, 0)
            conversations.touch(conversationId, System.currentTimeMillis())
        }
    }

    /** Appends a user message and returns its id. */
    suspend fun appendUser(
        conversationId: Long,
        content: String,
        attachments: List<String> = emptyList(),
    ): Long = withContext(Dispatchers.IO) {
        db.withTransaction {
            val now = System.currentTimeMillis()
            val id = messages.insert(
                MessageEntity(
                    conversationId = conversationId,
                    role = Roles.USER,
                    content = content,
                    createdAt = now,
                    status = MessageStatus.DONE,
                    attachments = if (attachments.isEmpty()) null else attachments.joinToString(","),
                ),
            )
            conversations.touch(conversationId, now)
            val existing = conversations.findById(conversationId)
            if (existing != null && existing.title == "New chat") {
                conversations.rename(conversationId, autoTitle(content), now)
            }
            id
        }
    }

    /** Creates the assistant row a generation will stream into. */
    suspend fun startAssistant(
        conversationId: Long,
        modelName: String? = null,
        skillId: String? = null,
        supersedesId: Long? = null,
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        messages.insert(
            MessageEntity(
                conversationId = conversationId,
                role = Roles.ASSISTANT,
                content = "",
                createdAt = now,
                status = MessageStatus.STREAMING,
                modelName = modelName,
                skillId = skillId,
                supersedesId = supersedesId,
            ),
        )
    }

    /**
     * Writes the streamed text so far. Called at the coalescer's cadence (a few
     * times a second), not per token.
     */
    suspend fun appendStream(messageId: Long, fullText: String, thinking: String? = null) =
        withContext(Dispatchers.IO) {
            messages.setContent(messageId, fullText)
            if (thinking != null) messages.setThinking(messageId, thinking)
        }

    suspend fun finishMessage(
        messageId: Long,
        outcome: GenerationOutcome,
        stats: GenerationStats,
        errorText: String? = null,
    ) = withContext(Dispatchers.IO) {
        val status = when (outcome) {
            GenerationOutcome.COMPLETED, GenerationOutcome.TRUNCATED -> MessageStatus.DONE
            GenerationOutcome.CANCELLED -> MessageStatus.CANCELLED
            GenerationOutcome.FAILED -> MessageStatus.FAILED
        }
        db.withTransaction {
            messages.finish(
                id = messageId,
                status = status,
                promptTokens = stats.promptTokens,
                tokensGenerated = stats.tokensGenerated,
                microseconds = stats.microseconds,
                truncated = stats.truncated || outcome == GenerationOutcome.TRUNCATED,
                cancelled = stats.cancelled || outcome == GenerationOutcome.CANCELLED,
                errorText = errorText,
            )
            val row = messages.findById(messageId)
            if (row != null) refreshStatsLocked(row.conversationId)
        }
    }

    /** Records a failure when no stats exist at all. */
    suspend fun failMessage(messageId: Long, errorText: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            messages.setStatus(messageId, MessageStatus.FAILED)
            messages.finish(messageId, MessageStatus.FAILED, 0, 0, 0L, false, false, errorText)
            val row = messages.findById(messageId)
            if (row != null) refreshStatsLocked(row.conversationId)
        }
    }

    // ---------------------------------------------------------- feedback

    /** The like toggle. Returns the new like total for the conversation. */
    suspend fun setLiked(messageId: Long, liked: Boolean): Int = withContext(Dispatchers.IO) {
        db.withTransaction {
            messages.setLiked(messageId, liked)
            val row = messages.findById(messageId)
            if (row != null) {
                refreshStatsLocked(row.conversationId)
                messages.likesIn(row.conversationId)
            } else 0
        }
    }

    suspend fun setFlagged(messageId: Long, flagged: Boolean) = withContext(Dispatchers.IO) {
        messages.setFlagged(messageId, flagged)
    }

    suspend fun totalLikes(): Int = withContext(Dispatchers.IO) { messages.totalLikes() }

    // ------------------------------------------------- edit / regenerate

    /**
     * "Delete from here": drops this message and everything after it, so the user
     * can edit and resend. Returns the number removed.
     */
    suspend fun deleteFrom(messageId: Long): Int = withContext(Dispatchers.IO) {
        db.withTransaction {
            val row = messages.findById(messageId) ?: return@withTransaction 0
            val before = messages.countIn(row.conversationId)
            messages.deleteFrom(row.conversationId, row.createdAt, row.id)
            val after = messages.countIn(row.conversationId)
            refreshStatsLocked(row.conversationId)
            conversations.touch(row.conversationId, System.currentTimeMillis())
            before - after
        }
    }

    suspend fun deleteMessage(messageId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val row = messages.findById(messageId)
            messages.deleteById(messageId)
            if (row != null) refreshStatsLocked(row.conversationId)
        }
    }

    /**
     * Replaces the last assistant answer: deletes it and returns the transcript
     * that should be re-sent. Used by "regenerate".
     */
    suspend fun prepareRegenerate(conversationId: Long): RegenerateWindow =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                val last = messages.lastWithRole(conversationId, Roles.ASSISTANT)
                val kept = messages.listFor(conversationId).filter { it.id != last?.id }
                if (last != null) messages.deleteById(last.id)
                refreshStatsLocked(conversationId)
                RegenerateWindow(kept, last?.id)
            }
        }

    data class RegenerateWindow(val transcript: List<MessageEntity>, val removedMessageId: Long?)

    private suspend fun refreshStatsLocked(conversationId: Long) {
        val count = messages.countIn(conversationId)
        val likes = messages.likesIn(conversationId)
        val tokens = messages.tokensIn(conversationId)
        conversations.setStats(conversationId, count, likes, tokens)
    }

    suspend fun refreshStats(conversationId: Long) = withContext(Dispatchers.IO) {
        refreshStatsLocked(conversationId)
    }

    // ------------------------------------------------------------- export

    suspend fun exportMarkdown(conversationId: Long): String = withContext(Dispatchers.IO) {
        val conversation = conversations.findById(conversationId)
        val rows = messages.listFor(conversationId)
        buildString {
            append("# ").append(conversation?.title ?: "Conversation").append("\n\n")
            append("- Exported: ").append(timestamp(System.currentTimeMillis())).append('\n')
            if (conversation?.modelName != null) append("- Model: ").append(conversation.modelName).append('\n')
            append("- Messages: ").append(rows.size).append('\n')
            val likes = rows.sumOf { it.likeCount }
            if (likes > 0) append("- Likes: ").append(likes).append('\n')
            if (conversation?.systemPrompt?.isNotBlank() == true) {
                append("\n## System prompt\n\n```\n").append(conversation.systemPrompt).append("\n```\n")
            }
            for (row in rows) {
                append("\n## ").append(roleLabel(row.role)).append(" · ").append(timestamp(row.createdAt)).append('\n')
                if (row.thinkingText?.isNotBlank() == true) {
                    append("\n<details><summary>Reasoning</summary>\n\n").append(row.thinkingText).append("\n\n</details>\n")
                }
                append('\n').append(row.content.trim()).append('\n')
                val footer = statsLine(row)
                if (footer.isNotEmpty()) append("\n<small>").append(footer).append("</small>\n")
            }
        }
    }

    suspend fun exportJson(conversationId: Long): String = withContext(Dispatchers.IO) {
        val conversation = conversations.findById(conversationId)
        val rows = messages.listFor(conversationId)
        val root: JsonObject = buildJsonObject {
            put("format", "offlinegguf-chat/export")
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("title", conversation?.title ?: "")
            put("model", conversation?.modelName ?: "")
            put("systemPrompt", conversation?.systemPrompt ?: "")
            put("likes", rows.sumOf { it.likeCount })
            put("tokens", rows.sumOf { it.tokensGenerated })
            val items: JsonArray = buildJsonArray {
                for (row in rows) {
                    add(
                        buildJsonObject {
                            put("id", row.id)
                            put("role", row.role)
                            put("content", row.content)
                            put("createdAt", row.createdAt)
                            put("status", row.status)
                            put("liked", row.liked)
                            put("likeCount", row.likeCount)
                            put("flagged", row.flagged)
                            put("promptTokens", row.promptTokens)
                            put("tokensGenerated", row.tokensGenerated)
                            put("microseconds", row.microseconds)
                            put("truncated", row.truncated)
                            put("cancelled", row.cancelled)
                            row.thinkingText?.let { put("thinking", it) }
                            row.errorText?.let { put("error", it) }
                            row.modelName?.let { put("model", it) }
                            row.skillId?.let { put("skill", it) }
                            if (row.attachmentList.isNotEmpty()) {
                                put("attachments", buildJsonArray { row.attachmentList.forEach { add(JsonPrimitive(it)) } })
                            }
                        },
                    )
                }
            }
            put("messages", items)
        }
        JSON.encodeToString(JsonObject.serializer(), root)
    }

    /** One-line summary used under an answer and in exports. */
    fun statsLine(row: MessageEntity): String {
        val parts = ArrayList<String>(4)
        if (row.tokensGenerated > 0 && row.microseconds > 0) {
            parts += "${Formats.fixed(row.tokensPerSecond, 1)} tok/s"
        }
        if (row.tokensGenerated > 0) parts += "${row.tokensGenerated} tokens"
        if (row.promptTokens > 0) parts += "${row.promptTokens} prompt"
        if (row.truncated) parts += "context truncated"
        if (row.cancelled) parts += "stopped"
        if (row.likeCount > 0) parts += "${row.likeCount} like${if (row.likeCount == 1) "" else "s"}"
        return parts.joinToString(" · ")
    }

    private fun roleLabel(role: String): String = when (role) {
        Roles.USER -> "You"
        Roles.ASSISTANT -> "Assistant"
        Roles.SYSTEM -> "System"
        Roles.TOOL -> "Tool"
        else -> role
    }

    private fun timestamp(millis: Long): String = DATE_FORMAT.format(Date(millis))

    companion object {
        private val JSON = kotlinx.serialization.json.Json { prettyPrint = true }
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        /** First line of the first user message, trimmed to a usable title. */
        fun autoTitle(firstMessage: String): String {
            val flat = firstMessage.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            if (flat.isEmpty()) return "New chat"
            return if (flat.length <= 42) flat else flat.take(40).trimEnd() + "…"
        }
    }
}
