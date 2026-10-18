package com.offlinegguf.chat.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A 4 dp grid. Screens take padding from here rather than hard-coding numbers,
 * which is what keeps the layout looking like one design instead of five.
 */
class Spacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
) {
    /** Horizontal screen margin. */
    val gutter: Dp = 20.dp

    /** Vertical rhythm between transcript entries. */
    val messageGap: Dp = 18.dp

    /** Message bubble corner — the single large radius in the app. */
    val bubble: Dp = 20.dp

    /** Everything else (cards, chips, fields) stays on the 4 dp rhythm. */
    val card: Dp = 12.dp
    val chip: Dp = 8.dp
    val field: Dp = 10.dp

    /** Height of the thin streaming rule and other hairlines. */
    val hairline: Dp = 1.dp
    val rule: Dp = 2.dp

    /** Minimum touch target, per the accessibility guidance. */
    val target: Dp = 48.dp
}
