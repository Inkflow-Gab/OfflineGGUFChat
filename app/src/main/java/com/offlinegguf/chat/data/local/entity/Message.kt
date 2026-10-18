package com.offlinegguf.chat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.room.Index

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId"), Index("parentId")]
)
data class Message(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    @ColumnInfo val conversationId: String,
    @ColumnInfo val parentId: String? = null, // for branching / regenerate
    @ColumnInfo val role: String, // "user" | "assistant" | "system"
    @ColumnInfo val content: String, // final visible answer (thinking stripped out)
    @ColumnInfo val thinking: String? = null, // hidden reasoning trace
    @ColumnInfo val images: String? = null, // JSON list of local file paths
    @ColumnInfo val attachments: String? = null, // JSON list of file paths
    @ColumnInfo val tokenCount: Int = 0, // tokens in this message
    @ColumnInfo val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo var isStreaming: Int = 0,
    @ColumnInfo var isEdited: Int = 0
)
