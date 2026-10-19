package com.offlinegguf.chat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.offlinegguf.chat.R
import com.offlinegguf.chat.domain.fit.ModelFit
import com.offlinegguf.chat.ui.theme.CodeText
import com.offlinegguf.chat.ui.theme.MetaKeyText
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme
import com.offlinegguf.chat.util.Formats

/**
 * The "it is working" indicator: a 2 dp rule that sweeps while tokens arrive.
 *
 * A spinner would imply the app is stuck; a progress bar would imply we know how
 * far along we are. Neither is true, so this is deliberately just motion at the
 * top of the answer.
 */
@Composable
fun StreamingRule(active: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = active,
        enter = fadeIn(tween(OfflineGgufTheme.motion.fast)),
        exit = fadeOut(tween(OfflineGgufTheme.motion.fast)),
        modifier = modifier.fillMaxWidth(),
    ) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = colors.primary,
            trackColor = colors.surfaceContainerHigh,
        )
    }
}

/** Three staggered dots signal local inference without implying a known ETA. */
@Composable
fun ThinkingPulse(
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val motion = OfflineGgufTheme.motion
    val transition = if (motion.reduced) null else rememberInfiniteTransition()

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        repeat(3) { index ->
            val alpha = if (transition != null) {
                val animated by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 720, delayMillis = index * 150),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "thinking-dot-$index",
                )
                animated
            } else 1f
            Box(
                Modifier
                    .size(6.dp)
                    .background(colors.onSurfaceVariant.copy(alpha = alpha), CircleShape),
            )
        }
    }
}

/** Blinking text caret, shown at the end of a streaming answer. */
@Composable
fun BlinkingCaret(modifier: Modifier = Modifier, visible: Boolean = true) {
    if (!visible) return
    val motion = OfflineGgufTheme.motion
    // Under "Reduce motion" no infinite transition is started at all: the caret
    // simply stays lit, which still reads as "still writing" next to the rule.
    val alpha = if (motion.caretBlink > 0) {
        val transition = rememberInfiniteTransition()
        val animated by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(motion.caretBlink),
                repeatMode = RepeatMode.Reverse,
            ),
        )
        animated
    } else {
        1f
    }
    Box(
        modifier = modifier
            .width(2.dp)
            .height(15.dp)
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                RoundedCornerShape(1.dp),
            ),
    )
}

/**
 * KV cache occupancy. When this reaches 100% the oldest turns stop being
 * remembered, so it is worth showing rather than hiding behind a debug screen.
 */
@Composable
fun ContextGauge(
    used: Int,
    total: Int,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val fraction = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Context",
                style = MaterialTheme.typography.labelSmall,
                color = colors.tertiary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (total > 0) "${Formats.paramCount(used.toLong())} / ${Formats.contextTokens(total.toLong())}"
                else "no model loaded",
                style = MetaKeyText,
                color = colors.onSurfaceVariant,
            )
            if (onClear != null && used > 0) {
                TextButton(onClick = onClear) {
                    Text("Clear", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = if (fraction > 0.9f) colors.onSurface else colors.primary,
            trackColor = colors.surfaceContainerHigh,
        )
        if (fraction >= 1f && total > 0) {
            Text(
                text = "Full — the oldest turns are being dropped. Clear it or lower n_ctx.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Tokens/second plus the flags that explain a short answer. */
@Composable
fun SpeedRow(
    tokensPerSecond: Double,
    tokens: Int,
    modifier: Modifier = Modifier,
    truncated: Boolean = false,
    cancelled: Boolean = false,
    seconds: Double = 0.0,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tokensPerSecond > 0) {
            StatChip(
                label = "speed",
                value = "${Formats.fixed(tokensPerSecond, 1)}/s",
                emphasized = true,
            )
        }
        if (tokens > 0) StatChip(label = "tokens", value = tokens.toString())
        if (seconds > 0) StatChip(label = "time", value = Formats.fixed(seconds, 1) + "s")
        if (truncated) {
            FlagChip(text = "context truncated", icon = R.drawable.ic_warning)
        }
        if (cancelled) {
            FlagChip(text = "stopped", icon = R.drawable.ic_stop)
        }
    }
}

@Composable
fun FlagChip(text: String, icon: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .background(colors.surfaceContainerHigh, RoundedCornerShape(OfflineGgufTheme.spacing.chip))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

/**
 * A model's reasoning, collapsed by default.
 *
 * Monochrome differentiation: reasoning is set in a smaller italic face behind a
 * hatched rule, so it is visibly "not the answer" without needing a colour.
 */
@Composable
fun ThinkingBlock(
    text: String,
    modifier: Modifier = Modifier,
    expandedByDefault: Boolean = false,
    streaming: Boolean = false,
) {
    if (text.isBlank()) return
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    val motion = OfflineGgufTheme.motion
    var expanded by remember(text.length == 0) { mutableStateOf(expandedByDefault) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(spacing.chip))
            .padding(horizontal = spacing.md, vertical = spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_brain),
                contentDescription = null,
                tint = colors.tertiary,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Reasoning",
                style = MaterialTheme.typography.labelSmall,
                color = colors.tertiary,
            )
            if (streaming) ThinkingPulse(modifier = Modifier.padding(start = 6.dp))
            Spacer(Modifier.weight(1f))
            Text(
                text = "${text.split('\n').size} lines",
                style = MetaKeyText,
                color = colors.tertiary,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(
                    if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right,
                ),
                contentDescription = if (expanded) "Collapse reasoning" else "Expand reasoning",
                tint = colors.tertiary,
                modifier = Modifier.size(13.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(motion.slow)) + fadeIn(tween(motion.standard)),
            exit = shrinkVertically(tween(motion.standard)) + fadeOut(tween(motion.fast)),
        ) {
            Text(
                text = text,
                style = CodeText.copy(fontStyle = FontStyle.Italic),
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.sm).fillMaxWidth(),
            )
        }
    }
}

/** Fit verdict as a monochrome badge. */
@Composable
fun VerdictBadge(verdict: ModelFit.Verdict, modifier: Modifier = Modifier) {
    val icon = when (verdict) {
        ModelFit.Verdict.COMFORTABLE -> R.drawable.ic_check
        ModelFit.Verdict.WORKABLE -> R.drawable.ic_check
        ModelFit.Verdict.TIGHT -> R.drawable.ic_gauge
        ModelFit.Verdict.WILL_NOT_FIT -> R.drawable.ic_warning
        ModelFit.Verdict.UNREADABLE -> R.drawable.ic_warning
    }
    InkChip(
        text = verdict.label,
        modifier = modifier,
        selected = verdict == ModelFit.Verdict.COMFORTABLE,
        icon = icon,
    )
}

/** A quiet label + value pair used across the model screens. */
@Composable
fun FactRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
    KeyValueRow(
        key = label,
        value = value,
        modifier = modifier,
        valueColor = valueColor ?: MaterialTheme.colorScheme.onSurface,
    )
}
