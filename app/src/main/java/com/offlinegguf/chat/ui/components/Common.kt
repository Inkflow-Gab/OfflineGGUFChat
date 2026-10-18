package com.offlinegguf.chat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlinegguf.chat.R
import com.offlinegguf.chat.ui.theme.CodeText
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme

/** Section heading with an optional trailing action. */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = OfflineGgufTheme.spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        if (action != null) action()
    }
}

/** A 1 dp rule in the outline colour — the app's only separator. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.outlineVariant) {
    HorizontalDivider(modifier = modifier, thickness = 1.dp, color = color)
}

/**
 * Chip used for filters, skills and badges. Selected state is filled ink; the
 * unselected state is an outline. No colour is involved.
 */
@Composable
fun InkChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    icon: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(OfflineGgufTheme.spacing.chip)
    val background = if (selected) colors.primary else colors.surface
    val content = if (selected) colors.onPrimary else colors.onSurfaceVariant

    val base = Modifier
        .heightIn(min = 32.dp)
        .background(background, shape)
        // A selected chip is solid ink; an unselected one is outlined.
        .then(if (selected) Modifier else Modifier.border(BorderStroke(1.dp, colors.outline), shape))
        .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)

    Row(
        modifier = modifier.then(base).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) content else colors.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Two-column metadata row: monospace key, wrapped value. */
@Composable
fun KeyValueRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = true,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = key,
            style = if (mono) CodeText else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.width(132.dp),
        )
        Text(
            text = value,
            style = if (mono) CodeText else MaterialTheme.typography.bodySmall,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Compact stat readout: label above, value below. */
@Composable
fun StatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .background(
                if (emphasized) colors.surfaceContainerHigh else colors.surfaceContainer,
                RoundedCornerShape(OfflineGgufTheme.spacing.chip),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.tertiary,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
fun InkIconButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    size: Int = 20,
) {
    val colors = MaterialTheme.colorScheme
    IconButton(onClick = onClick, modifier = modifier.size(40.dp), enabled = enabled) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = when {
                !enabled -> colors.tertiary
                active -> colors.onSurface
                else -> colors.onSurfaceVariant
            },
            modifier = Modifier.size(size.dp),
        )
    }
}

/** App bar used by every non-tab screen; tabs render their own header. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InkTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                InkIconButton(
                    icon = R.drawable.ic_chevron_left,
                    description = "Back",
                    onClick = onBack,
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
fun EmptyState(
    icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val spacing = OfflineGgufTheme.spacing
    Column(
        modifier = modifier.fillMaxWidth().padding(spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainer,
                    RoundedCornerShape(spacing.card),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(spacing.xs))
            InkButton(label = actionLabel, onClick = onAction)
        }
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(onClick = onSecondary) {
                Text(secondaryLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * The app's primary button: solid ink, square-ish, no ripple colour surprise.
 * Destructive uses are the same button — the label carries the meaning.
 */
@Composable
fun InkButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Int? = null,
    filled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(OfflineGgufTheme.spacing.chip)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = if (filled) colors.primary else colors.surface,
        contentColor = if (filled) colors.onPrimary else colors.onSurface,
        border = if (filled) null else BorderStroke(1.dp, colors.outline),
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirmLabel, style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(OfflineGgufTheme.spacing.card),
    )
}

/** Vertically stacked content with the app's standard rhythm. */
@Composable
fun InkColumn(
    modifier: Modifier = Modifier,
    spacingDp: Int = 12,
    contentPadding: PaddingValues = PaddingValues(OfflineGgufTheme.spacing.gutter),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(spacingDp.dp),
        content = content,
    )
}

/** Row of stat chips that scrolls horizontally when it overflows. */
@Composable
fun StatRow(
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    emphasizedIndex: Int = -1,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEachIndexed { index, (label, value) ->
            StatChip(label = label, value = value, emphasized = index == emphasizedIndex)
        }
    }
}

/** Small square used as a bullet/legend marker. */
@Composable
fun Swatch(color: Color, modifier: Modifier = Modifier, size: Int = 10) {
    Box(
        modifier = modifier
            .size(size.dp)
            .background(color, RoundedCornerShape(2.dp)),
    )
}

/** Keeps a row of trailing widgets pinned right, with a leading label. */
@Composable
fun LabeledRow(
    label: String,
    modifier: Modifier = Modifier,
    value: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (value != null) value()
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}
