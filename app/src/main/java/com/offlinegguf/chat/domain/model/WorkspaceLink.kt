package com.offlinegguf.chat.domain.model

import android.net.Uri

/**
 * A reference to a file or folder in the user's device storage.
 * Stored as a path + URI so it can be opened later via FileProvider / intent.
 */
data class WorkspaceLink(
    val id: String = java.util.UUID.randomUUID().toString(),
    val path: String,
    val name: String,
    val sizeBytes: Long = 0,
    val mimeType: String? = null,
    val uri: String? = null, // stored as string for persistence
    val addedAt: Long = System.currentTimeMillis()
)
