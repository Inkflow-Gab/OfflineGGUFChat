package com.offlinegguf.chat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The whole local store: conversations, messages, models on disk, skills and the
 * per-session workspaces.
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
        WorkspaceFileEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun models(): ModelDao
    abstract fun skills(): SkillDao
    abstract fun workspace(): WorkspaceDao

    companion object {
        const val NAME = "offlinegguf-chat.db"

        /**
         * v1 → v2: the workspace tables. Hand-written to match what Room
         * generates for [WorkspaceFileEntity], and tested by every app update
         * over an existing install — never fall back to dropping chat history
         * for a schema bump.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspace_files` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`conversationId` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`language` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workspace_files_conversationId` " +
                        "ON `workspace_files` (`conversationId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_files_conversationId_name` " +
                        "ON `workspace_files` (`conversationId`, `name`)",
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                // Schema bumps ship real migrations (above). This fallback only
                // exists for corruption, and even then it is a last resort —
                // these tables hold the user's chat history.
                .fallbackToDestructiveMigration()
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
