package com.offlinegguf.chat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The whole local store: conversations, messages, models on disk and skills.
 *
 * Schema is exported to `app/schemas` (see the kapt argument in
 * `app/build.gradle.kts`) so a migration mistake shows up as a diff in review
 * instead of a crash after an upgrade.
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        LocalModelEntity::class,
        SkillEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun models(): ModelDao
    abstract fun skills(): SkillDao

    companion object {
        const val NAME = "offlinegguf-chat.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                // v1 only: there is nothing to migrate from yet. When the schema
                // changes, add a real Migration here rather than dropping data —
                // these tables hold the user's chat history.
                .fallbackToDestructiveMigration()
                .build()
    }
}
