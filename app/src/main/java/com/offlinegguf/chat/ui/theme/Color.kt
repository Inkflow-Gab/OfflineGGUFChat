package com.offlinegguf.chat.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The whole app is monochrome: black ink on white paper, and its inverse.
 *
 * No hue is used anywhere — not for accents, not for "success", not for errors.
 * State is carried by polarity, weight, fill and motion instead, which keeps the
 * workspace calm when a screen holds a long transcript, a code diff and a token
 * gauge at the same time. Destructive actions read as *solid ink*; safe actions
 * read as outlines.
 */
object Ink {

    // ---- paper (light) ----
    val Paper = Color(0xFFFFFFFF)
    val PaperLow = Color(0xFFFCFCFC)
    val PaperRaised = Color(0xFFF7F7F7)
    val PaperHigh = Color(0xFFF1F1F2)
    val PaperHighest = Color(0xFFEAEAEC)

    // ---- ink (dark) ----
    val Ink0 = Color(0xFF0B0B0C)
    val Ink10 = Color(0xFF17171A)
    val Ink20 = Color(0xFF1D1D21)
    val Ink30 = Color(0xFF26262B)
    val Ink40 = Color(0xFF33333A)

    // ---- grayscale text ramp ----
    // Three steps, and every one clears WCAG AA (4.5:1) against the paper it is
    // drawn on: 19.7 / 8.5 / 6.0 in light mode, 18.2 / 10.1 / 7.0 in dark. A
    // monochrome design has nothing to hide behind, so a "faint" label that is
    // actually unreadable would be a bug rather than a style choice — there is no
    // fourth step, and the muted one is the floor.
    val TextPrimaryLight = Color(0xFF0B0B0C)   // 19.67:1 on Paper
    val TextSecondaryLight = Color(0xFF4C4C53) //  8.52:1
    val TextMutedLight = Color(0xFF63636B)     //  5.95:1

    val TextPrimaryDark = Color(0xFFF6F6F7)    // 18.22:1 on Ink0
    val TextSecondaryDark = Color(0xFFB9B9C0)  // 10.08:1
    val TextMutedDark = Color(0xFF9A9AA2)      //  7.04:1

    // ---- rules ----
    // "Strong" is the colour interactive boundaries use (field, chip, switch and
    // button outlines): it clears the 3:1 non-text contrast minimum. The plain
    // rule is the 1 dp separator, which is decorative — spacing does the real
    // work of dividing a screen — and is deliberately quiet.
    val RuleStrongLight = Color(0xFF8E8E96)    // 3.25:1 on Paper
    val RuleLight = Color(0xFFDCDCE0)          // 1.37:1, separators only
    val RuleStrongDark = Color(0xFF72727B)     // 4.13:1 on Ink0
    val RuleDark = Color(0xFF2E2E34)           // 1.46:1, separators only

    // ---- code diff fills (monochrome: a faint tint plus a +/- gutter mark) ----
    val DiffAddedLight = Color(0xFFF2F2F3)
    val DiffRemovedLight = Color(0xFFE6E6E8)
    val DiffAddedDark = Color(0xFF1B1B1F)
    val DiffRemovedDark = Color(0xFF26262B)
    val DiffGutterLight = Color(0xFF55555C)    // 6.6:1 on the added fill
    val DiffGutterDark = Color(0xFF9A9AA2)     // 6.2:1 on the added fill
}

val LightInkScheme = lightColorScheme(
    primary = Ink.Ink0,
    onPrimary = Ink.Paper,
    primaryContainer = Ink.PaperHighest,
    onPrimaryContainer = Ink.Ink0,
    inversePrimary = Ink.Paper,
    secondary = Ink.TextSecondaryLight,
    onSecondary = Ink.Paper,
    secondaryContainer = Ink.PaperHigh,
    onSecondaryContainer = Ink.Ink0,
    tertiary = Ink.TextMutedLight,
    onTertiary = Ink.Paper,
    tertiaryContainer = Ink.PaperRaised,
    onTertiaryContainer = Ink.Ink0,
    background = Ink.Paper,
    onBackground = Ink.TextPrimaryLight,
    surface = Ink.Paper,
    onSurface = Ink.TextPrimaryLight,
    surfaceVariant = Ink.PaperRaised,
    onSurfaceVariant = Ink.TextSecondaryLight,
    surfaceTint = Ink.Ink0,
    inverseSurface = Ink.Ink10,
    inverseOnSurface = Ink.TextPrimaryDark,
    error = Ink.Ink0,
    onError = Ink.Paper,
    errorContainer = Ink.PaperHighest,
    onErrorContainer = Ink.Ink0,
    outline = Ink.RuleStrongLight,
    outlineVariant = Ink.RuleLight,
    scrim = Ink.Ink0,
    surfaceContainerLowest = Ink.Paper,
    surfaceContainerLow = Ink.PaperLow,
    surfaceContainer = Ink.PaperRaised,
    surfaceContainerHigh = Ink.PaperHigh,
    surfaceContainerHighest = Ink.PaperHighest,
    surfaceBright = Ink.Paper,
    surfaceDim = Ink.PaperHighest,
)

val DarkInkScheme = darkColorScheme(
    primary = Ink.TextPrimaryDark,
    onPrimary = Ink.Ink0,
    primaryContainer = Ink.Ink30,
    onPrimaryContainer = Ink.TextPrimaryDark,
    inversePrimary = Ink.Ink0,
    secondary = Ink.TextSecondaryDark,
    onSecondary = Ink.Ink0,
    secondaryContainer = Ink.Ink20,
    onSecondaryContainer = Ink.TextPrimaryDark,
    tertiary = Ink.TextMutedDark,
    onTertiary = Ink.Ink0,
    tertiaryContainer = Ink.Ink10,
    onTertiaryContainer = Ink.TextSecondaryDark,
    background = Ink.Ink0,
    onBackground = Ink.TextPrimaryDark,
    surface = Ink.Ink0,
    onSurface = Ink.TextPrimaryDark,
    surfaceVariant = Ink.Ink10,
    onSurfaceVariant = Ink.TextSecondaryDark,
    surfaceTint = Ink.TextPrimaryDark,
    inverseSurface = Ink.TextPrimaryDark,
    inverseOnSurface = Ink.Ink0,
    error = Ink.TextPrimaryDark,
    onError = Ink.Ink0,
    errorContainer = Ink.Ink20,
    onErrorContainer = Ink.TextPrimaryDark,
    outline = Ink.RuleStrongDark,
    outlineVariant = Ink.RuleDark,
    scrim = Ink.Ink0,
    surfaceContainerLowest = Ink.Ink0,
    surfaceContainerLow = Ink.Ink10,
    surfaceContainer = Ink.Ink20,
    surfaceContainerHigh = Ink.Ink30,
    surfaceContainerHighest = Ink.Ink40,
    surfaceBright = Ink.Ink30,
    surfaceDim = Ink.Ink0,
)

/**
 * Diff colours resolved against the active scheme, so a code block can render
 * added/removed lines without knowing whether the app is in light or dark mode.
 */
data class DiffPalette(
    val added: Color,
    val removed: Color,
    val gutter: Color,
    val addedMark: Char = '+',
    val removedMark: Char = '−',
)

fun diffPalette(dark: Boolean, highContrast: Boolean = false): DiffPalette = when {
    // High contrast pushes the two tints further apart and the gutter to full ink,
    // so a diff stays readable without relying on a subtle grey.
    highContrast && dark -> DiffPalette(
        added = Ink.Ink40,
        removed = Ink.Ink20,
        gutter = Ink.TextPrimaryDark,
    )
    highContrast -> DiffPalette(
        added = Ink.PaperHighest,
        removed = Ink.PaperHigh,
        gutter = Ink.TextPrimaryLight,
    )
    dark -> DiffPalette(
        added = Ink.DiffAddedDark,
        removed = Ink.DiffRemovedDark,
        gutter = Ink.DiffGutterDark,
    )
    else -> DiffPalette(
        added = Ink.DiffAddedLight,
        removed = Ink.DiffRemovedLight,
        gutter = Ink.DiffGutterLight,
    )
}

/**
 * The "High contrast" preference: pure ink on pure paper, no tinted greys.
 *
 * Every muted text role is promoted to full-strength ink, the five tinted paper
 * steps collapse onto plain paper, and hairlines become rules. Nothing on screen
 * then depends on a subtle grey to be legible, which is the whole point of the
 * toggle — and it costs no extra palette, because monochrome already has the two
 * extremes available.
 */
fun ColorScheme.highContrast(dark: Boolean): ColorScheme {
    val ink = if (dark) Ink.TextPrimaryDark else Ink.TextPrimaryLight
    val paper = if (dark) Ink.Ink0 else Ink.Paper
    return copy(
        onSurface = ink,
        onSurfaceVariant = ink,
        onBackground = ink,
        secondary = ink,
        tertiary = ink,
        background = paper,
        surface = paper,
        surfaceVariant = paper,
        surfaceContainerLowest = paper,
        surfaceContainerLow = paper,
        surfaceContainer = paper,
        surfaceContainerHigh = paper,
        surfaceContainerHighest = paper,
        surfaceBright = paper,
        surfaceDim = paper,
        outline = ink,
        outlineVariant = ink,
    )
}
