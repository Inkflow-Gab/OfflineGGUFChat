package com.offlinegguf.chat.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.R
import com.offlinegguf.chat.data.local.LocalModelEntity
import com.offlinegguf.chat.data.settings.AppSettings
import com.offlinegguf.chat.domain.fit.ModelFit
import com.offlinegguf.chat.ui.components.ConfirmDialog
import com.offlinegguf.chat.ui.components.EmptyState
import com.offlinegguf.chat.ui.components.Hairline
import com.offlinegguf.chat.ui.components.InkButton
import com.offlinegguf.chat.ui.components.InkChip
import com.offlinegguf.chat.ui.components.InkIconButton
import com.offlinegguf.chat.ui.components.InkTopBar
import com.offlinegguf.chat.ui.components.VerdictBadge
import com.offlinegguf.chat.ui.theme.MetaKeyText
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme
import com.offlinegguf.chat.util.Formats
import com.offlinegguf.chat.viewmodel.ModelSort
import com.offlinegguf.chat.viewmodel.ModelsViewModel

/**
 * The local library.
 *
 * Every row states what it is (name, quant, size, parameters) and whether it will
 * run on *this* phone, computed from the header we read at import time. Nothing
 * here touches the network.
 */
@Composable
fun ModelsScreen(
    container: AppContainer,
    settings: AppSettings,
    onOpenModel: (String) -> Unit,
    onOpenDiscover: () -> Unit,
) {
    val viewModel: ModelsViewModel = viewModel(factory = ModelsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    val spacing = OfflineGgufTheme.spacing
    val colors = MaterialTheme.colorScheme
    val motion = OfflineGgufTheme.motion

    var searchVisible by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LocalModelEntity?>(null) }
    var deleting by remember { mutableStateOf<LocalModelEntity?>(null) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importFrom(it) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        InkTopBar(
            title = "Models",
            subtitle = buildString {
                append("${state.loadableCount} loadable")
                if (state.models.size != state.loadableCount) append(" of ${state.models.size}")
                append(" · ${Formats.bytes(state.storageUsed)} on disk")
            },
            actions = {
                InkIconButton(
                    icon = R.drawable.ic_search,
                    description = if (searchVisible) "Hide search" else "Search",
                    onClick = { searchVisible = !searchVisible },
                    active = searchVisible || state.query.isNotBlank(),
                )
                InkIconButton(
                    icon = R.drawable.ic_folder,
                    description = "Scan the models folder",
                    onClick = { viewModel.scanFolder() },
                    enabled = !state.busy,
                )
                InkIconButton(
                    icon = R.drawable.ic_plus,
                    description = "Import a GGUF file",
                    onClick = { pickFile.launch(arrayOf("*/*")) },
                    enabled = !state.busy,
                )
            },
        )

        AnimatedVisibility(
            visible = searchVisible,
            enter = fadeIn(tween(motion.fast)),
            exit = fadeOut(tween(motion.fast)),
        ) {
            Column(modifier = Modifier.padding(horizontal = spacing.gutter, vertical = spacing.sm)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Name, family or quant…", style = MaterialTheme.typography.bodyMedium) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true,
                    shape = RoundedCornerShape(spacing.field),
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = null,
                            tint = colors.tertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.onSurface,
                        unfocusedBorderColor = colors.outline,
                        cursorColor = colors.onSurface,
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                    ),
                )
                Spacer(Modifier.height(spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    ModelSort.values().forEach { option ->
                        InkChip(
                            text = option.label,
                            selected = state.sort == option,
                            onClick = { viewModel.setSort(option) },
                        )
                    }
                }
                Spacer(Modifier.height(spacing.xs))
                InkChip(
                    text = "Loadable only",
                    selected = state.onlyLoadable,
                    icon = R.drawable.ic_check,
                    onClick = { viewModel.toggleOnlyLoadable() },
                )
            }
        }

        if (!state.engineAvailable) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceContainerHigh)
                    .padding(horizontal = spacing.gutter, vertical = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(spacing.sm))
                Text(
                    text = state.engineNotice ?: "This build has no inference engine",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (state.models.isEmpty() && !state.busy) {
                EmptyState(
                    icon = R.drawable.ic_cube,
                    title = "No models on this phone",
                    body = "Import a .gguf file you already have, or open Discover for a " +
                        "device-matched shortlist with links to Hugging Face.",
                    actionLabel = "Import a file",
                    onAction = { pickFile.launch(arrayOf("*/*")) },
                    secondaryLabel = "Discover models",
                    onSecondary = onOpenDiscover,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(spacing.gutter),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    items(items = state.visible, key = { it.path }) { model ->
                        ModelCard(
                            model = model,
                            fit = remember(model.path, model.sizeBytes, state.profile.ramBytes) {
                                viewModel.fitFor(model)
                            },
                            engineAvailable = state.engineAvailable,
                            onOpen = { onOpenModel(model.path) },
                            onRename = { renaming = model },
                            onDelete = { deleting = model },
                            onRefreshHeader = { viewModel.reprobe(model.path) },
                        )
                    }
                    item(key = "footer") {
                        Column(modifier = Modifier.padding(vertical = spacing.md)) {
                            Hairline()
                            Spacer(Modifier.height(spacing.sm))
                            Text(
                                text = "Files live in ${viewModel.modelsDirPath()}. " +
                                    "Imports are copied there, so they keep working after the picker closes.",
                                style = MetaKeyText,
                                color = colors.tertiary,
                            )
                            Spacer(Modifier.height(spacing.md))
                            InkButton(
                                label = "Find models for this phone",
                                onClick = onOpenDiscover,
                                filled = false,
                                icon = R.drawable.ic_target,
                            )
                        }
                    }
                }
            }

            state.importing?.let { progress ->
                // A real dialog: a multi-gigabyte copy cannot be interrupted by a
                // stray tap, and the user should not be able to wander off mid-copy.
                Dialog(properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
                    ImportCard(
                        name = progress.name,
                        label = progress.label,
                        unknown = progress.unknownTotal,
                        fraction = progress.fraction,
                    )
                }
            }
        }
    }

    renaming?.let { model ->
        var name by remember(model.path) { mutableStateOf(model.displayName) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        cursorColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(model.path, name)
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel") }
            },
        )
    }

    deleting?.let { model ->
        ConfirmDialog(
            title = "Delete ${model.displayName}?",
            body = if (settings.confirmBeforeDelete) {
                "This removes ${Formats.bytes(model.sizeBytes)} from app storage. " +
                    "Chats that used it stay; you can point them at another model."
            } else {
                "This removes ${Formats.bytes(model.sizeBytes)} from app storage."
            },
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.delete(model.path, deleteFile = true)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ModelCard(
    model: LocalModelEntity,
    fit: ModelFit.Assessment,
    engineAvailable: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onRefreshHeader: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    var menuOpen by remember { mutableStateOf(false) }
    val missing = !File(model.path).exists()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(spacing.card))
            .clickable(onClick = onOpen)
            .padding(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(if (missing) R.drawable.ic_warning else R.drawable.ic_cube),
                contentDescription = null,
                tint = if (model.valid && !missing) colors.onSurface else colors.tertiary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(spacing.sm))
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                InkIconButton(
                    icon = R.drawable.ic_more,
                    description = "Model actions",
                    onClick = { menuOpen = true },
                    size = 16,
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Re-read header") },
                        onClick = { menuOpen = false; onRefreshHeader() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }

        Spacer(Modifier.height(spacing.sm))

        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (model.quantization.isNotBlank()) {
                InkChip(text = model.quantization, selected = true)
            }
            if (model.architecture.isNotBlank()) InkChip(text = model.architecture)
            VerdictBadge(verdict = fit.verdict)
            if (missing) InkChip(text = "file missing", icon = R.drawable.ic_warning)
            if (!model.valid && !missing) InkChip(text = "unreadable", icon = R.drawable.ic_warning)
        }

        Spacer(Modifier.height(spacing.sm))

        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            Fact(label = "size", value = Formats.bytes(model.sizeBytes))
            if (model.paramCount > 0) Fact(label = "params", value = Formats.paramCount(model.paramCount))
            if (model.contextLength > 0) {
                Fact(label = "context", value = Formats.contextTokens(model.contextLength.toLong()))
            }
            Fact(label = "speed", value = if (fit.tokensPerSecond > 0) "~${Formats.fixed(fit.tokensPerSecond, 0)}/s" else "—")
            if (model.useCount > 0) Fact(label = "used", value = "${model.useCount}×")
        }

        if (fit.warnings.isNotEmpty() || model.errorText != null) {
            Spacer(Modifier.height(spacing.sm))
            Text(
                text = model.errorText ?: fit.warnings.first(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.tertiary,
            )
        }

        if (!engineAvailable) {
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = "Header inspection works without the engine; generation needs a build with llama.cpp.",
                style = MetaKeyText,
                color = colors.tertiary,
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MetaKeyText,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

/** Blocks the list while a multi-gigabyte copy is running. */
@Composable
private fun ImportCard(name: String, label: String, unknown: Boolean, fraction: Float) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(spacing.card))
            .padding(spacing.lg),
    ) {
        Text(
            text = "Importing",
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (name == "gguf") "model.gguf" else name,
            style = MetaKeyText,
            color = colors.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(spacing.md))
        if (unknown) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = colors.primary,
                trackColor = colors.surfaceContainerHigh,
            )
        } else {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = colors.primary,
                trackColor = colors.surfaceContainerHigh,
            )
        }
        Spacer(Modifier.height(spacing.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Text(
            text = "Copying into app storage, then reading the header.",
            style = MetaKeyText,
            color = colors.tertiary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
