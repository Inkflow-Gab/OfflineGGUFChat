package com.offlinegguf.chat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.offlinegguf.chat.R
import com.offlinegguf.chat.data.local.MessageEntity
import com.offlinegguf.chat.data.local.MessageStatus
import com.offlinegguf.chat.data.local.Roles
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme
import com.offlinegguf.chat.util.Formats

/**
 * One transcript entry.
 *
 * The two roles are told apart by polarity rather than colour: what you wrote is
 * a solid ink bubble on the right, what the model wrote is bare text on paper at
 * the left with its actions underneath. That keeps a long transcript scannable in
 * a monochrome palette.
 */
@Composable
fun MessageRow(
    message: MessageEntity,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    previousCode: Map<Int, String> = emptyMap(),
    showThinking: Boolean = true,
    showLineNumbers: Boolean = true,
    onCopy: () -> Unit = {},
    onLike: (Boolean) -> Unit = {},
    onFlag: (Boolean) -> Unit = {},
    onRegenerate: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDeleteFrom: () -> Unit = {},
    /** Fade and rise into place. Callers set this only for the newest message. */
    animateEntry: Boolean = false,
) {
    val spacing = OfflineGgufTheme.spacing
    val motion = OfflineGgufTheme.motion

    var shown by remember(message.id) { mutableStateOf(!animateEntry) }
    LaunchedEffect(message.id) { shown = true }

    Column(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(motion.slow, easing = motion.easeDecelerate)) +
                slideInVertically(tween(motion.slow, easing = motion.easeDecelerate)) { it / 6 },
            exit = fadeOut(tween(motion.fast)),
        ) {
            if (message.role == Roles.USER) {
                UserBubble(message = message, onEdit = onEdit, onDeleteFrom = onDeleteFrom, onCopy = onCopy)
            } else {
                AssistantMessage(
                    message = message,
                    streaming = streaming,
                    previousCode = previousCode,
                    showThinking = showThinking,
                    showLineNumbers = showLineNumbers,
                    onCopy = onCopy,
                    onLike = onLike,
                    onFlag = onFlag,
                    onRegenerate = onRegenerate,
                    onDeleteFrom = onDeleteFrom,
                )
            }
        }
        Spacer(Modifier.height(spacing.messageGap))
    }
}

@Composable
private fun UserBubble(
    message: MessageEntity,
    onEdit: () -> Unit,
    onDeleteFrom: () -> Unit,
    onCopy: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing

    Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
        if (!message.attachmentList.isEmpty()) {
            Row(
                modifier = Modifier.padding(bottom = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                message.attachmentList.forEach { name ->
                    FlagChip(text = name, icon = R.drawable.ic_text)
                }
            }
        }
        Box(
            modifier = Modifier
                .background(colors.primary, RoundedCornerShape(spacing.bubble, spacing.bubble, spacing.xs, spacing.bubble))
                .padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            MarkdownText(
                text = message.content,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = Formats.relativeTime(message.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = colors.tertiary,
            )
            InkIconButton(icon = R.drawable.ic_copy, description = "Copy", onClick = onCopy, size = 14)
            InkIconButton(icon = R.drawable.ic_edit, description = "Edit and resend", onClick = onEdit, size = 14)
            InkIconButton(
                icon = R.drawable.ic_trash,
                description = "Delete from here",
                onClick = onDeleteFrom,
                size = 14,
            )
        }
    }
}

@Composable
private fun AssistantMessage(
    message: MessageEntity,
    streaming: Boolean,
    previousCode: Map<Int, String>,
    showThinking: Boolean,
    showLineNumbers: Boolean,
    onCopy: () -> Unit,
    onLike: (Boolean) -> Unit,
    onFlag: (Boolean) -> Unit,
    onRegenerate: () -> Unit,
    onDeleteFrom: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    val motion = OfflineGgufTheme.motion
    val failed = message.status == MessageStatus.FAILED
    val empty = message.content.isBlank() && !streaming

    // Tokens land a few times a second and the reasoning block expands on tap, so
    // the height eases instead of snapping between frames.
    Column(modifier = Modifier.fillMaxWidth().animateContentSize(tween(motion.fast))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_sparkle),
                contentDescription = null,
                tint = colors.tertiary,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = message.modelName?.takeIf { it.isNotBlank() } ?: "Assistant",
                style = MaterialTheme.typography.labelSmall,
                color = colors.tertiary,
                maxLines = 1,
            )
            if (message.skillId != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "· ${message.skillId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.tertiary,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = Formats.relativeTime(message.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = colors.tertiary,
            )
        }

        if (showThinking && !message.thinkingText.isNullOrBlank()) {
            Spacer(Modifier.height(spacing.sm))
            ThinkingBlock(text = message.thinkingText.orEmpty(), streaming = streaming)
        }

        Spacer(Modifier.height(spacing.sm))

        when {
            failed -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceContainerHigh, RoundedCornerShape(spacing.chip))
                    .padding(spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = colors.onSurface,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(spacing.sm))
                Text(
                    text = message.errorText ?: "Generation failed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }

            empty -> Text(
                text = "Nothing was generated.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.tertiary,
            )

            else -> Column {
                MarkdownText(
                    text = message.content,
                    previousCode = previousCode,
                    showLineNumbers = showLineNumbers,
                )
                if (streaming) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BlinkingCaret()
                    }
                }
            }
        }

        StreamingRule(active = streaming, modifier = Modifier.padding(top = spacing.sm))

        // ---- actions ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LikeButton(
                liked = message.liked,
                count = message.likeCount,
                enabled = !streaming,
                onClick = { onLike(!message.liked) },
            )
            InkIconButton(
                icon = R.drawable.ic_flag,
                description = if (message.flagged) "Clear 'not helpful'" else "Mark as not helpful",
                onClick = { onFlag(!message.flagged) },
                active = message.flagged,
                enabled = !streaming,
                size = 16,
            )
            InkIconButton(
                icon = R.drawable.ic_copy,
                description = "Copy answer",
                onClick = onCopy,
                size = 16,
            )
            InkIconButton(
                icon = R.drawable.ic_refresh,
                description = "Regenerate",
                onClick = onRegenerate,
                enabled = !streaming,
                size = 16,
            )
            InkIconButton(
                icon = R.drawable.ic_trash,
                description = "Delete from here",
                onClick = onDeleteFrom,
                enabled = !streaming,
                size = 16,
            )
            Spacer(Modifier.weight(1f))
            if (message.tokensGenerated > 0) {
                Text(
                    text = "${Formats.fixed(message.tokensPerSecond, 1)} tok/s · ${message.tokensGenerated}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.tertiary,
                )
            }
        }

        if (message.truncated || message.cancelled) {
            SpeedRow(
                tokensPerSecond = 0.0,
                tokens = 0,
                truncated = message.truncated,
                cancelled = message.cancelled,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
    }
}

/**
 * The like affordance.
 *
 * The heart bumps once when its state changes — a tap that produced no visible
 * change would feel broken — and the count slides open the first time it becomes
 * non-zero, then collapses again when the last like is removed.
 */
@Composable
private fun LikeButton(
    liked: Boolean,
    count: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val motion = OfflineGgufTheme.motion
    val colors = MaterialTheme.colorScheme
    val bump = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(liked) }

    LaunchedEffect(liked) {
        val changed = liked != seen
        seen = liked
        if (changed && !motion.reduced) {
            bump.snapTo(1f)
            bump.animateTo(1.22f, tween(motion.fast, easing = motion.easeDecelerate))
            bump.animateTo(1f, tween(motion.standard, easing = motion.easeStandard))
        }
    }

    Box(Modifier.scale(bump.value)) {
        InkIconButton(
            icon = R.drawable.ic_heart,
            description = if (liked) "Remove like" else "Like this answer",
            onClick = onClick,
            active = liked,
            enabled = enabled,
            size = 16,
        )
    }
    AnimatedVisibility(
        visible = count > 0,
        enter = fadeIn(tween(motion.fast)) + expandHorizontally(tween(motion.standard)),
        exit = fadeOut(tween(motion.fast)) + shrinkHorizontally(tween(motion.standard)),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurface,
            modifier = Modifier.padding(end = 2.dp),
        )
    }
}
