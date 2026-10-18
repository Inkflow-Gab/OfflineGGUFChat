package com.offlinegguf.chat.ui.theme

import androidx.compose.ui.graphics.Color

val DarkColorScheme = androidColorScheme(
    primary = Color(0xFF03DAC6),
    secondary = Color(0xFF03DAC6),
    background = Color(0xFF0F0F0F),
    surface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFF2A2A2A),
    onPrimary = Color(0xFF000000),
    onSecondary = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFC0C0C0),
    error = Color(0xFFCF6679),
    onError = Color(0xFFFFFFFF)
)

val LightColorScheme = androidColorScheme(
    primary = Color(0xFF03DAC6),
    secondary = Color(0xFF03DAC6),
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0F0F0),
    onPrimary = Color(0xFF000000),
    onSecondary = Color(0xFF000000),
    onBackground = Color(0xFF000000),
    onSurface = Color(0xFF000000),
    onSurfaceVariant = Color(0xFF4F4F4F),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

private fun androidColorScheme(
    primary: Color, secondary: Color, background: Color, surface: Color,
    surfaceVariant: Color, onPrimary: Color, onSecondary: Color,
    onBackground: Color, onSurface: Color, onSurfaceVariant: Color,
    error: Color, onError: Color
) = androidColorScheme(
    primary = primary, secondary = secondary, background = background,
    surface = surface, surfaceVariant = surfaceVariant,
    onPrimary = onPrimary, onSecondary = onSecondary,
    onBackground = onBackground, onSurface = onSurface,
    onSurfaceVariant = onSurfaceVariant,
    error = error, onError = onError
)
