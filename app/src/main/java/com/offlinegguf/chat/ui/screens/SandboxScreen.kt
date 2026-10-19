package com.offlinegguf.chat.ui.screens

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.R
import com.offlinegguf.chat.data.workspace.SandboxDocument
import com.offlinegguf.chat.ui.components.EmptyState
import com.offlinegguf.chat.ui.components.InkIconButton
import com.offlinegguf.chat.ui.components.InkTopBar
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme
import com.offlinegguf.chat.viewmodel.SandboxViewModel
import java.io.ByteArrayInputStream
import kotlinx.coroutines.launch

/**
 * The web sandbox: HTML, CSS and JS from a chat answer running in a WebView
 * that cannot reach the network, the file system, or anything else on the
 * device. Snippets render live (console output is mirrored into the page), and
 * anything worth keeping can be saved into the session's workspace.
 */
@Composable
fun SandboxScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: SandboxViewModel = viewModel(factory = SandboxViewModel.factory(container))
    val request by viewModel.request.collectAsStateWithLifecycle()
    val spacing = OfflineGgufTheme.spacing
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }

    val current = request
    if (current == null) {
        InkTopBar(title = "Sandbox", onBack = onBack)
        EmptyState(
            icon = R.drawable.ic_play,
            title = "Nothing to run",
            body = "Tap the play icon on an HTML, CSS or JS code block in a chat answer to run it here.",
        )
        return
    }

    InkTopBar(
        title = current.name,
        subtitle = "Sandbox · offline · network blocked",
        onBack = onBack,
        actions = {
            InkIconButton(
                icon = R.drawable.ic_refresh,
                description = "Reload",
                onClick = {
                    saved = false
                    reloadTick++
                },
            )
            InkIconButton(
                icon = if (saved) R.drawable.ic_check else R.drawable.ic_folder,
                description = "Save to workspace",
                enabled = current.conversationId > 0,
                active = saved,
                onClick = {
                    scope.launch {
                        viewModel.saveToWorkspace(current)
                        saved = true
                    }
                },
            )
        },
    )

    Box(Modifier.fillMaxSize()) {
        // A new snippet (or a Reload tap) rebuilds the WebView through `key`,
        // so ordinary recomposition — like the save checkmark — never re-runs
        // the page under the user's feet.
        key(current.language, current.content, reloadTick) {
            val document = remember(current.language, current.content) {
                SandboxDocument.assemble(current.language, current.content)
            }
            Box(Modifier.fillMaxSize().padding(spacing.xs)) {
                androidx.compose.ui.viewinterop.AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): WebResourceResponse? {
                                    val scheme = request?.url?.scheme?.lowercase()
                                    // data:, about: and blob: stay (they are the
                                    // page itself); every http(s) request is
                                    // answered with an empty body so sandboxed
                                    // code cannot phone home.
                                    return if (scheme == "http" || scheme == "https") {
                                        WebResourceResponse(
                                            "text/plain",
                                            "utf-8",
                                            ByteArrayInputStream(ByteArray(0)),
                                        )
                                    } else {
                                        null
                                    }
                                }
                            }
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = false
                            settings.databaseEnabled = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            @Suppress("DEPRECATION")
                            settings.allowFileAccessFromFileURLs = false
                            @Suppress("DEPRECATION")
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            settings.mediaPlaybackRequiresUserGesture = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            loadDataWithBaseURL(
                                "https://sandbox.invalid/",
                                document,
                                "text/html",
                                "utf-8",
                                null,
                            )
                        }
                    },
                    onRelease = { view ->
                        view.stopLoading()
                        view.destroy()
                    },
                )
            }
        }
    }
}
