package com.offlinegguf.chat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.offlinegguf.chat.data.local.entity.Conversation
import com.offlinegguf.chat.data.local.entity.Message
import com.offlinegguf.chat.data.local.dao.ConversationDao
import com.offlinegguf.chat.data.local.dao.MessageDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Database(entities = [Conversation::class, Message::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "offline_gguf_chat.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    // Populate a default conversation on first launch
    fun seedIfEmpty(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val dao = conversationDao()
            if (dao.getActiveConversations().first().isEmpty()) {
                dao.insert(
                    Conversation(
                        title = "Welcome",
                        systemPrompt = "You are a helpful, offline AI assistant running entirely on this device."
                    )
                )
            }
        }
    }
}
