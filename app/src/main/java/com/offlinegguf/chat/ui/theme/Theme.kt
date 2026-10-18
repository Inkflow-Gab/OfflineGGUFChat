package com.offlinegguf.chat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.readOnlyComposable
import androidx.compose.ui.unit.dp

private val DarkTypography = OfflineGgufTypography
private val LightTypography = OfflineGgufTypography

@Composable
fun OfflineGgufChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF03DAC6),
            surface = Color(0xFF121212),
            background = Color(0xFF0F0F0F),
            onSurface = Color(0xFFFFFFFF),
            onBackground = Color(0xFFFFFFFF)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF03DAC6),
            surface = Color(0xFFFFFFFF),
            background = Color(0xFFFAFAFA),
            onSurface = Color(0xFF000000),
            onBackground = Color(0xFF000000)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = if (darkTheme) DarkTypography else LightTypography,
        content = content
    )
}
