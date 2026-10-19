package com.offlinegguf.chat.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.R
import com.offlinegguf.chat.data.local.WorkspaceFileEntity
import com.offlinegguf.chat.data.workspace.SandboxDocument
import com.offlinegguf.chat.data.workspace.SandboxRequest
import com.offlinegguf.chat.ui.components.EmptyState
import com.offlinegguf.chat.ui.components.InkButton
import com.offlinegguf.chat.ui.components.InkChip
import com.offlinegguf.chat.ui.components.InkTopBar
import com.offlinegguf.chat.ui.theme.MetaKeyText
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme
import com.offlinegguf.chat.util.Formats
import com.offlinegguf.chat.viewmodel.WorkspaceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The workspace of one session history: every conversation keeps its own set of
 * files. Snippets saved from answers and pages saved from the sandbox land
 * here; web files can be re-run in the sandbox, anything can be copied or
 * exported, and deleting is per file.
 */
@Composable
fun WorkspaceScreen(
    container: AppContainer,
    conversationId: Long,
    onBack: () -> Unit,
    onOpenSandbox: () -> Unit,
) {
    val viewModel: WorkspaceViewModel = viewModel(
        key = "workspace-$conversationId",
        factory = WorkspaceViewModel.factory(container, conversationId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var showNewDialog by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<WorkspaceFileEntity?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val file = pendingExport
        pendingExport = null
        if (uri != null && file != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(file.content.toByteArray(Charsets.UTF_8))
                        } ?: error("Could not open that location")
                    }
                }
                if (ok.isFailure) viewModel.reportError(ok.exceptionOrNull()?.message ?: "Export failed")
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        InkTopBar(
            title = "Workspace",
            subtitle = "${state.files.size} file${if (state.files.size == 1) "" else "s"} · this session only",
            onBack = onBack,
            actions = {
                InkButton(
                    label = "New file",
                    icon = R.drawable.ic_plus,
                    filled = false,
                    onClick = { showNewDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                )
            },
        )

        if (state.notice != null) {
            Text(
                text = state.notice.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
                modifier = Modifier
                    .padding(horizontal = spacing.gutter, vertical = spacing.xs)
                    .background(colors.surfaceContainerHigh, RoundedCornerShape(spacing.chip))
                    .padding(spacing.sm)
                    .fillMaxWidth(),
            )
        }

        if (state.files.isEmpty()) {
            EmptyState(
                icon = R.drawable.ic_folder,
                title = "This session's workspace is empty",
                body = "Save a code block from an answer, or a page from the sandbox, " +
                    "and it will live here — one workspace per chat.",
                actionLabel = "New file",
                onAction = { showNewDialog = true },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                items(state.files, key = { it.id }) { file ->
                    WorkspaceFileRow(
                        file = file,
                        onRun = {
                            container.requestSandbox(
                                SandboxRequest(
                                    language = file.language,
                                    content = file.content,
                                    name = file.name,
                                    conversationId = conversationId,
                                    fileId = file.id,
                                ),
                            )
                            onOpenSandbox()
                        },
                        onCopy = { clipboard.setText(AnnotatedString(file.content)) },
                        onExport = {
                            pendingExport = file
                            exportLauncher.launch(file.name)
                        },
                        onDelete = { viewModel.delete(file) },
                    )
                }
            }
        }
    }

    if (showNewDialog) {
        NewFileDialog(
            onDismiss = { showNewDialog = false },
            onSave = { name, language, content ->
                viewModel.save(name, language, content)
                showNewDialog = false
            },
        )
    }
}

@Composable
private fun WorkspaceFileRow(
    file: WorkspaceFileEntity,
    onRun: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    val canRun = SandboxDocument.isWebLanguage(file.language)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(spacing.card))
            .padding(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (canRun) {
                TextButton(onClick = onRun) {
                    Text("Run", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (file.language.isNotBlank()) {
                InkChip(text = file.language, onClick = onCopy)
            }
            Text(
                text = "${file.lineCount} lines · ${Formats.relativeTime(file.updatedAt)}",
                style = MetaKeyText,
                color = colors.tertiary,
            )
        }
        Spacer(Modifier.height(spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onCopy) { Text("Copy", style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = onExport) { Text("Export", style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = onDelete) { Text("Delete", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun NewFileDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, language: String, content: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("html") }
    var content by remember { mutableStateOf("") }
    val spacing = OfflineGgufTheme.spacing

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New workspace file") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name, e.g. index.html") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = language,
                    onValueChange = { language = it },
                    label = { Text("Language (html, css, js, python…)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Content") },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalName = name.ifBlank { SandboxDocument.fileNameFor(language, null) }
                    onSave(finalName, language, content)
                },
                enabled = content.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
