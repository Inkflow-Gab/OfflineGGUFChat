package com.offlinegguf.chat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** User-selectable appearance. Monochrome either way — only the polarity flips. */
enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Paper"),
    DARK("Ink");

    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromName(name: String?): ThemeMode =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SYSTEM
    }
}

private val LocalSpacing = staticCompositionLocalOf { Spacing() }
private val LocalMotion = staticCompositionLocalOf { MotionSpec() }
private val LocalDiff = staticCompositionLocalOf { diffPalette(false) }

/**
 * Installs the monochrome workspace theme.
 *
 * Dynamic colour is deliberately *not* used: a Material You wallpaper palette
 * would reintroduce hue, and the whole point of this design is that a screen
 * full of code, diffs and numbers stays readable and calm.
 *
 * Two accessibility preferences are resolved here rather than at the call sites,
 * so no screen has to know about them: [reduceMotion] collapses every duration in
 * [MotionSpec] to zero, and [highContrast] swaps in a scheme with no tinted greys.
 */
@Composable
fun OfflineGgufTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    reduceMotion: Boolean = false,
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = themeMode.isDark(systemDark)
    val base = if (dark) DarkInkScheme else LightInkScheme
    val colors = if (highContrast) base.highContrast(dark) else base

    CompositionLocalProvider(
        LocalSpacing provides Spacing(),
        LocalMotion provides MotionSpec.forPreference(reduceMotion),
        LocalDiff provides diffPalette(dark, highContrast),
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = InkTypography,
            shapes = InkShapes,
            content = content,
        )
    }
}

/**
 * The three things a component cannot get from `MaterialTheme`: the spacing grid,
 * the motion tokens, and the resolved diff palette.
 *
 * Colours are deliberately *not* re-exported here — every screen reads
 * `MaterialTheme.colorScheme` directly, which is the idiomatic call and keeps one
 * obvious place to look. Only values with no Material equivalent live below.
 */
object OfflineGgufTheme {
    val spacing: Spacing @Composable get() = LocalSpacing.current
    val motion: MotionSpec @Composable get() = LocalMotion.current
    val diff: DiffPalette @Composable get() = LocalDiff.current
}
