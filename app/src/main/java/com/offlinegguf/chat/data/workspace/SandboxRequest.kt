package com.offlinegguf.chat.data.workspace

/**
 * One sandbox run: a snippet to execute in the locked-down WebView, plus where
 * it belongs when the user saves it. Carried across screens through
 * [com.offlinegguf.chat.AppContainer.pendingSandbox] (code is too large and too
 * arbitrary for a navigation route string).
 */
data class SandboxRequest(
    val language: String,
    val content: String,
    val name: String,
    /** Session the snippet came from; its workspace receives saved copies. */
    val conversationId: Long = -1L,
    /** Row id when the snippet already lives in a workspace. */
    val fileId: Long = -1L,
)
