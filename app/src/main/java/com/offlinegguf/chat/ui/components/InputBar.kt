package com.offlinegguf.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.offlinegguf.chat.R
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme

/**
 * The composer.
 *
 * Enter inserts a newline rather than sending — this app is used for code and
 * prompts that span lines, so the send button owns sending. While a generation is
 * running the same button becomes a stop button in exactly the same place, so it
 * can be hit without looking.
 */
@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    generating: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "Ask anything…",
    skillLabel: String? = null,
    onOpenSkills: (() -> Unit)? = null,
    attachments: List<String> = emptyList(),
    onAddAttachment: (() -> Unit)? = null,
    onRemoveAttachment: ((String) -> Unit)? = null,
    hint: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    val canSend = enabled && !generating && value.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = spacing.gutter, vertical = spacing.sm),
    ) {
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                attachments.forEach { name ->
                    Row(
                        modifier = Modifier
                            .background(
                                colors.surfaceContainerHigh,
                                RoundedCornerShape(spacing.chip),
                            )
                            .padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_text),
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                        if (onRemoveAttachment != null) {
                            IconButton(
                                onClick = { onRemoveAttachment(name) },
                                modifier = Modifier.size(22.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = "Remove $name",
                                    tint = colors.tertiary,
                                    modifier = Modifier.size(11.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 180.dp),
                enabled = enabled && !generating,
                placeholder = {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.tertiary,
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(spacing.field),
                maxLines = 7,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.onSurface,
                    unfocusedTextColor = colors.onSurface,
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                    focusedBorderColor = colors.onSurface,
                    unfocusedBorderColor = colors.outline,
                    cursorColor = colors.onSurface,
                    focusedPlaceholderColor = colors.tertiary,
                    unfocusedPlaceholderColor = colors.tertiary,
                    disabledBorderColor = colors.outlineVariant,
                    disabledTextColor = colors.tertiary,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )

            if (onAddAttachment != null) {
                IconButton(
                    onClick = onAddAttachment,
                    enabled = enabled && !generating,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_plus),
                        contentDescription = "Attach a text file",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            IconButton(
                onClick = { if (generating) onStop() else onSend() },
                enabled = generating || canSend,
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (generating || canSend) colors.primary else colors.surfaceContainerHigh,
                        RoundedCornerShape(spacing.field),
                    ),
            ) {
                Icon(
                    painter = painterResource(if (generating) R.drawable.ic_stop else R.drawable.ic_send),
                    contentDescription = if (generating) "Stop generating" else "Send",
                    tint = if (generating || canSend) colors.onPrimary else colors.tertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (skillLabel != null || hint != null) {
            Spacer(Modifier.height(spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (skillLabel != null && onOpenSkills != null) {
                    InkChip(
                        text = skillLabel,
                        icon = R.drawable.ic_sparkle,
                        onClick = onOpenSkills,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.tertiary,
                    )
                }
            }
        }
    }
}
