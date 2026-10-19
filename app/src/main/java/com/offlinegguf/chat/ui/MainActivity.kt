package com.offlinegguf.chat.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinegguf.chat.App
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.data.settings.AppSettings
import com.offlinegguf.chat.ui.navigation.AppNavigation
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme

/**
 * Single activity, everything else is Compose.
 *
 * Edge-to-edge is enabled before `setContent` so the very first frame already has
 * the right insets. The user's font-scale preference is applied by overriding
 * [Density], which is what sp units are resolved against — that scales every piece
 * of text in the app without rebuilding the typography for each size.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as App).container
        handleIncoming(intent, container)

        // singleTop: a second "Open with" while we are running arrives here.
        addOnNewIntentListener { intent -> handleIncoming(intent, container) }

        setContent {
            val settings by container.settings.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            val baseDensity = LocalDensity.current

            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, settings.fontScale),
            ) {
                OfflineGgufTheme(
                    themeMode = settings.themeMode,
                    reduceMotion = settings.reduceMotion,
                    highContrast = settings.highContrast,
                ) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        AppNavigation(container = container, settings = settings)
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIncoming(intent: Intent?, container: AppContainer) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
            else -> null
        }
        if (uri != null) {
            container.requestImport(uri)
            return
        }

        // The SEND filter takes every mime type, so this activity is also in the
        // plain-text share sheet. Shared text becomes the draft prompt; without
        // this the app accepts the share and then does nothing at all.
        if (intent.action == Intent.ACTION_SEND) {
            val shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
            if (!shared.isNullOrEmpty()) container.requestPrompt(shared)
        }
    }
}
