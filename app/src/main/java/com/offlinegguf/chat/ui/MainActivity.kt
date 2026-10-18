package com.offlinegguf.chat.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.offlinegguf.chat.ui.navigation.AppNavigation
import com.offlinegguf.chat.ui.theme.OfflineGgufChatTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(window, window.findViewById(android.R.id.content))
        controller.isAppearanceLightStatusBars = false
        controller.hide(WindowInsetsCompat.Type.systemBars())

        setContent {
            OfflineGgufChatTheme {
                AppNavigation(activity = this@MainActivity)
            }
        }
    }
}
