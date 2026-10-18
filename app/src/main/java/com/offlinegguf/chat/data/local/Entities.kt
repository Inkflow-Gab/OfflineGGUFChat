package com.offlinegguf.chat.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Roles, stored as strings so the schema stays readable in exported JSON. */
object Roles {
    const val SYSTEM = "system"
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL = "tool"
}

/** Lifecycle of one assistant message. */
object MessageStatus {
    const val PENDING = "pending"
    const val STREAMING = "streaming"
    const val DONE = "done"
    const val CANCELLED = "cancelled"
    const val FAILED = "failed"
}

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "New chat",
    /** Absolute path of the model this conversation was run with. */
    val modelPath: String? = null,
    val modelName: String? = null,
    val systemPrompt: String = "",
    val skillId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** Denormalised for the history list; the repository keeps it in step. */
    @ColumnInfo(defaultValue = "0") val messageCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val likeCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val tokenCount: Int = 0,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index("createdAt")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String = Roles.USER,
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = MessageStatus.DONE,

    // ---- generation stats ----
    val promptTokens: Int = 0,
    val tokensGenerated: Int = 0,
    val microseconds: Long = 0,
    val truncated: Boolean = false,
    val cancelled: Boolean = false,
    val errorText: String? = null,

    // ---- feedback: the like button and its count ----
    val liked: Boolean = false,
    val likeCount: Int = 0,
    /** "Not helpful" — used to offer a regenerate without deleting the answer. */
    val flagged: Boolean = false,

    // ---- presentation ----
    /** Reasoning text a model emitted before the answer, if it produced any. */
    val thinkingText: String? = null,
    val skillId: String? = null,
    val modelName: String? = null,
    /** Comma-separated display names of attached files. */
    val attachments: String? = null,
    /** Set when this message replaced an earlier one (edit-and-resend). */
    val supersedesId: Long? = null,
) {
    val tokensPerSecond: Double
        get() = if (microseconds > 0 && tokensGenerated > 0) {
            tokensGenerated / (microseconds / 1_000_000.0)
        } else 0.0

    val isAssistant: Boolean get() = role == Roles.ASSISTANT
    val attachmentList: List<String>
        get() = attachments?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
}

/**
 * A GGUF file that is actually on the device, plus everything learned from its
 * header and the per-model settings the user chose.
 *
 * The path is the primary key: one row per file, and deleting the file deletes
 * the row.
 */
@Entity(tableName = "models", indices = [Index("addedAt")])
data class LocalModelEntity(
    @PrimaryKey val path: String,
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long = 0,

    // ---- from the GGUF header ----
    val valid: Boolean = true,
    val errorText: String? = null,
    val ggufVersion: Int = 0,
    val architecture: String = "",
    val quantization: String = "",
    val contextLength: Int = 0,
    val vocabSize: Int = 0,
    val blockCount: Int = 0,
    val embeddingLength: Int = 0,
    val paramCount: Long = 0,
    val weightBytes: Long = 0,
    val tensorCount: Int = 0,
    val hasChatTemplate: Boolean = false,
    val multimodal: Boolean = false,
    val bitsPerWeight: Double = 0.0,

    // ---- provenance ----
    val sourceUrl: String? = null,
    val catalogId: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0,
    val useCount: Int = 0,

    // ---- engine settings ----
    val nCtx: Int = 0,
    val threads: Int = 0,
    val batch: Int = 0,
    val gpuLayers: Int = 0,
    val useMmap: Boolean = true,
    val kvType: String = "f16",

    // ---- sampling defaults ----
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 256,
    val maxTokens: Int = 512,
    val seed: Int = -1,
) {
    /** 0 means "not configured yet" — the UI then derives it from the device. */
    val configured: Boolean get() = nCtx > 0 && threads > 0
}

/**
 * A reusable prompt + sampling + grammar preset.
 *
 * Built-in skills ship as assets and are inserted on first run with
 * `builtin = true`; user skills are editable and deletable.
 */
@Entity(tableName = "skills", indices = [Index(value = ["slug"], unique = true)])
data class SkillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val slug: String,
    val name: String,
    val description: String = "",
    val systemPrompt: String = "",
    /** Optional user-message template; `{{input}}` and `{{attachments}}` expand. */
    val userTemplate: String = "",
    val grammar: String? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val builtin: Boolean = false,
    val sortOrder: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    /** How often it has been applied — used to order the picker. */
    val useCount: Int = 0,
)
