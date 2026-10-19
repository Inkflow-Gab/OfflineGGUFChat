package com.offlinegguf.chat.data.repository

import com.offlinegguf.chat.data.local.AppDatabase
import com.offlinegguf.chat.data.local.WorkspaceFileEntity
import kotlinx.coroutines.flow.Flow

/**
 * Per-conversation workspace storage.
 *
 * Every session history owns one workspace: snippets saved from answers and
 * pages saved from the web sandbox land here, keyed by file name. Nothing here
 * touches the network or the shared storage — workspaces are app-private and
 * leave the phone only when the user exports a file explicitly.
 */
class WorkspaceRepository(private val database: AppDatabase) {

    fun observe(conversationId: Long): Flow<List<WorkspaceFileEntity>> =
        database.workspace().observeFor(conversationId)

    fun observeCount(conversationId: Long): Flow<Int> =
        database.workspace().observeCountFor(conversationId)

    suspend fun file(id: Long): WorkspaceFileEntity? = database.workspace().findById(id)

    /** Save (or overwrite by name) one workspace file. Returns its row id. */
    suspend fun save(
        conversationId: Long,
        name: String,
        language: String,
        content: String,
    ): Long = database.workspace().upsert(
        conversationId = conversationId,
        name = sanitizeName(name),
        language = language.trim().lowercase(),
        content = content,
    )

    suspend fun delete(id: Long) = database.workspace().deleteById(id)

    /** Keep names single-line and path-free — the workspace is flat by design. */
    private fun sanitizeName(name: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\').trim()
        return cleaned.ifBlank { "untitled.txt" }
    }
}
