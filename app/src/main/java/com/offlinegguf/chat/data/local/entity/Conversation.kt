package com.offlinegguf.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.UUID

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo val title: String = "New Chat",
    @ColumnInfo val modelPath: String? = null,
    @ColumnInfo val modelName: String? = null,
    @ColumnInfo val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo var updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo var pinned: Int = 0,
    @ColumnInfo var archived: Int = 0,
    @ColumnInfo var systemPrompt: String? = null,
    @ColumnInfo var lastMessagePreview: String? = null,
    @ColumnInfo var messageCount: Int = 0
)
