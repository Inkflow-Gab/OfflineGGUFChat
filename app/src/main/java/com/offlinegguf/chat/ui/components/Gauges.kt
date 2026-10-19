package com.offlinegguf.chat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.offlinegguf.chat.R
import com.offlinegguf.chat.domain.fit.ModelFit
import com.offlinegguf.chat.ui.theme.CodeText
import com.offlinegguf.chat.ui.theme.MetaKeyText
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme
import com.offlinegguf.chat.util.Formats

/**
 * The "it is working" indicator: a thin rule with a comet of light sweeping
 * across it while tokens arrive.
 *
 * A spinner would imply the app is stuck; a progress bar would imply we know how
 * far along we are. Neither is true, so this is deliberately just motion along
 * the answer — the comet glides and wraps instead of bouncing back and forth.
 */
@Composable
fun StreamingRule(active: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val motion = OfflineGgufTheme.motion
    AnimatedVisibility(
        visible = active,
        enter = fadeIn(tween(motion.fast)),
        exit = fadeOut(tween(motion.fast)),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (motion.reduced) {
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(colors.surfaceContainerHigh),
                )
                Box(
                    Modifier
                        .fillMaxWidth(0.3f)
                        .height(2.dp)
                        .background(colors.onSurface),
                )
            }
        } else {
            val transition = rememberInfiniteTransition(label = "stream-rule")
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1_100, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "stream-rule-phase",
            )
            Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                drawRect(color = colors.surfaceContainerHigh)
                val comet = size.width * 0.3f
                val x = (size.width + comet) * phase - comet
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            colors.onSurface.copy(alpha = 0f),
                            colors.onSurface,
                            colors.onSurface.copy(alpha = 0f),
                        ),
                        startX = x,
                        endX = x + comet,
                    ),
                    topLeft = Offset(x, 0f),
                    size = Size(comet, size.height),
                )
            }
        }
    }
}

/** Frames of the thinking glyph: a braille wave that reads as "processing". */
private val THINKING_FRAMES = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

/**
 * The "it is thinking" indicator: a braille symbol cycling like a spinner,
 * beside a label a band of light shimmers across.
 *
 * This replaces the old three bouncing dots, which read as loading rather than
 * as thinking. Under "Reduce motion" the glyph stands still and the label is
 * solid — the state is still legible without any movement at all.
 */
@Composable
fun ThinkingPulse(
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val motion = OfflineGgufTheme.motion
    val reduced = motion.reduced

    val frame = if (reduced) {
        0
    } else {
        val transition = rememberInfiniteTransition(label = "thinking-glyph")
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = THINKING_FRAMES.size.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "thinking-frame",
        )
        progress.toInt().coerceIn(0, THINKING_FRAMES.lastIndex)
    }

    val shimmer = if (reduced || label == null) {
        null
    } else {
        val transition = rememberInfiniteTransition(label = "thinking-shimmer")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "thinking-shimmer-phase",
        ).value
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = THINKING_FRAMES[frame],
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurface,
            fontFamily = FontFamily.Monospace,
        )
        if (label != null) {
            Text(
                text = shimmerAnnotated(label, shimmer, colors.onSurfaceVariant),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Per-character alpha band travelling left → right; null [phase] is solid. */
private fun shimmerAnnotated(text: String, phase: Float?, base: Color): AnnotatedString =
    buildAnnotatedString {
        if (phase == null) {
            withStyle(SpanStyle(color = base)) { append(text) }
            return@buildAnnotatedString
        }
        val n = text.length.coerceAtLeast(1)
        text.forEachIndexed { index, char ->
            val position = index.toFloat() / n
            val delta = phase - position
            val glow = if (delta in 0f..0.25f) 1f - (delta / 0.25f) else 0f
            val alpha = (0.5f + 0.5f * glow).coerceIn(0f, 1f)
            withStyle(SpanStyle(color = base.copy(alpha = alpha))) { append(char) }
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
