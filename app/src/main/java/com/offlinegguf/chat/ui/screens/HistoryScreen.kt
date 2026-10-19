package com.offlinegguf.chat.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.R
import com.offlinegguf.chat.data.export.launchShare
import com.offlinegguf.chat.data.local.ConversationEntity
import com.offlinegguf.chat.ui.components.ConfirmDialog
import com.offlinegguf.chat.ui.components.EmptyState
import com.offlinegguf.chat.ui.components.InkChip
import com.offlinegguf.chat.ui.components.InkIconButton
import com.offlinegguf.chat.ui.components.InkTopBar
import com.offlinegguf.chat.ui.components.StatRow
import com.offlinegguf.chat.ui.theme.MetaKeyText
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme
import com.offlinegguf.chat.util.Formats
import com.offlinegguf.chat.viewmodel.HistoryViewModel

/** Saved chats, with likes and token totals per conversation. */
@Composable
fun HistoryScreen(
    container: AppContainer,
    onOpenConversation: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    val context = LocalContext.current

    var searchVisible by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ConversationEntity?>(null) }
    var deleting by remember { mutableStateOf<ConversationEntity?>(null) }
    var clearing by remember { mutableStateOf<ConversationEntity?>(null) }

    // The view model renders the transcript and writes the file off the main
    // thread; this only launches what it built, and says so when the phone has
    // nothing that can take the export.
    fun share(intent: Intent?) {
        if (!launchShare(context, intent)) {
            viewModel.reportError("Nothing on this phone can take that export.")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        InkTopBar(
            title = if (state.showArchived) "Archived" else "History",
            subtitle = "${state.conversations.size} chats · ${state.totalLikes} likes",
            onBack = onBack,
            actions = {
                InkIconButton(
                    icon = R.drawable.ic_search,
                    description = "Search chats",
                    onClick = { searchVisible = !searchVisible },
                    active = searchVisible || state.query.isNotBlank(),
                )
                InkIconButton(
                    icon = R.drawable.ic_archive,
                    description = if (state.showArchived) "Show active chats" else "Show archived",
                    onClick = { viewModel.toggleArchived() },
                    active = state.showArchived,
                )
                InkIconButton(
                    icon = R.drawable.ic_broom,
                    description = "Delete all archived",
                    onClick = { viewModel.deleteArchived() },
                    enabled = state.archived.isNotEmpty(),
                )
            },
        )

        if (searchVisible) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.gutter, vertical = spacing.sm),
                placeholder = { Text("Title or model…", style = MaterialTheme.typography.bodyMedium) },
                textStyle = MaterialTheme.typography.bodyMedium,
                singleLine = true,
                shape = RoundedCornerShape(spacing.field),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.onSurface,
                    unfocusedBorderColor = colors.outline,
                    cursorColor = colors.onSurface,
                ),
            )
        }

        if (state.visible.isEmpty()) {
            EmptyState(
                icon = R.drawable.ic_clock,
                title = if (state.showArchived) "Nothing archived" else "No chats yet",
                body = if (state.query.isNotBlank()) {
                    "Nothing matches \"${state.query}\"."
                } else if (state.showArchived) {
                    "Archived chats are hidden from the main list but never deleted on their own."
                } else {
                    "Conversations are saved on this device with their like counts and token totals."
                },
                actionLabel = if (state.showArchived) "Show active chats" else null,
                onAction = if (state.showArchived) { { viewModel.toggleArchived() } } else null,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                items(items = state.visible, key = { it.id }) { conversation ->
                    ConversationCard(
                        conversation = conversation,
                        onOpen = { onOpenConversation(conversation.id) },
                        onPin = { viewModel.setPinned(conversation.id, !conversation.pinned) },
                        onArchive = { viewModel.setArchived(conversation.id, !conversation.archived) },
                        onRename = { renaming = conversation },
                        onDelete = { deleting = conversation },
                        onClear = { clearing = conversation },
                        onExportMarkdown = {
                            viewModel.exportMarkdown(conversation.id) { intent -> share(intent) }
                        },
                        onExportJson = {
                            viewModel.exportJson(conversation.id) { intent -> share(intent) }
                        },
                    )
                }
                item(key = "totals") {
                    StatRow(
                        items = listOf(
                            "messages" to state.messageTotal.toString(),
                            "tokens" to state.tokenTotal.toString(),
                            "likes" to state.totalLikes.toString(),
                        ),
                        emphasizedIndex = 2,
                        modifier = Modifier.padding(top = spacing.md),
                    )
                }
            }
        }

        state.notice?.let { notice ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceContainerHigh)
                    .padding(horizontal = spacing.gutter, vertical = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(spacing.sm))
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                InkChip(text = "Dismiss", onClick = { viewModel.consumeNotice() })
            }
        }
    }

    renaming?.let { conversation ->
        var title by remember(conversation.id) { mutableStateOf(conversation.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.onSurface,
                        unfocusedBorderColor = colors.outline,
                        cursorColor = colors.onSurface,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(conversation.id, title)
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel") }
            },
        )
    }

    deleting?.let { conversation ->
        ConfirmDialog(
            title = "Delete \"${conversation.title}\"?",
            body = "${conversation.messageCount} message(s) and their likes go with it. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.delete(conversation.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }

    clearing?.let { conversation ->
        ConfirmDialog(
            title = "Clear the messages?",
            body = "The chat stays in your history with its title and model, but every message is removed.",
            confirmLabel = "Clear",
            onConfirm = {
                viewModel.clearMessages(conversation.id)
                clearing = null
            },
            onDismiss = { clearing = null },
        )
    }
}

@Composable
private fun ConversationCard(
    conversation: ConversationEntity,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onExportMarkdown: () -> Unit,
    onExportJson: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(spacing.card))
            .clickable(onClick = onOpen)
            .padding(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (conversation.pinned) {
                Icon(
                    painter = painterResource(R.drawable.ic_pin),
                    contentDescription = "Pinned",
                    tint = colors.onSurface,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (conversation.pinned) FontWeight.SemiBold else FontWeight.Normal,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                InkIconButton(
                    icon = R.drawable.ic_more,
                    description = "Chat actions",
                    onClick = { menuOpen = true },
                    size = 16,
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (conversation.pinned) "Unpin" else "Pin to top") },
                        onClick = { menuOpen = false; onPin() },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (conversation.archived) "Restore" else "Archive") },
                        onClick = { menuOpen = false; onArchive() },
                    )
                    DropdownMenuItem(
                        text = { Text("Export Markdown") },
                        onClick = { menuOpen = false; onExportMarkdown() },
                    )
                    DropdownMenuItem(
                        text = { Text("Export JSON") },
                        onClick = { menuOpen = false; onExportJson() },
                    )
                    DropdownMenuItem(
                        text = { Text("Clear messages") },
                        onClick = { menuOpen = false; onClear() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete chat") },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }

        Spacer(Modifier.height(spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Formats.relativeTime(conversation.updatedAt),
                style = MetaKeyText,
                color = colors.tertiary,
            )
            conversation.modelName?.let { name ->
                Text(text = " · ", style = MetaKeyText, color = colors.tertiary)
                Text(
                    text = name,
                    style = MetaKeyText,
                    color = colors.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            if (conversation.likeCount > 0) {
                Icon(
                    painter = painterResource(R.drawable.ic_heart),
                    contentDescription = null,
                    tint = colors.onSurface,
                    modifier = Modifier.size(11.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = conversation.likeCount.toString(),
                    style = MetaKeyText,
                    color = colors.onSurface,
                )
            }
        }

        if (conversation.messageCount > 0) {
            Spacer(Modifier.height(spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                InkChip(text = "${conversation.messageCount} messages")
                if (conversation.tokenCount > 0) InkChip(text = "${conversation.tokenCount} tokens")
                if (conversation.archived) InkChip(text = "archived", icon = R.drawable.ic_archive)
            }
        }
    }
}
