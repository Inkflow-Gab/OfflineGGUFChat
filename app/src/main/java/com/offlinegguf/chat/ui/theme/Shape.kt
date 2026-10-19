package com.offlinegguf.chat.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Four radii, used consistently: 2 / 4 / 8 / 12 dp, plus the 20 dp message bubble
 * defined in [Spacing.bubble]. Nothing else is rounded, and nothing is fully
 * circular — pill shapes would fight the rectangular, paper-like layout.
 */
val InkShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
