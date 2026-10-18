package com.offlinegguf.chat.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.offlinegguf.chat.data.local.entity.Message
import com.offlinegguf.chat.data.repository.ChatRepository
import com.offlinegguf.chat.domain.model.SamplingParams
import com.offlinegguf.chat.inference.LlamaEngine
import com.offlinegguf.chat.ui.components.AttachmentChip
import com.offlinegguf.chat.ui.components.InputBar
import com.offlinegguf.chat.ui.components.MessageBubble
import com.offlinegguf.chat.ui.components.ModelStatusChip
import com.offlinegguf.chat.ui.theme.OfflineGgufChatTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinegguf.chat.ui.viewmodel.ChatViewModel
import android.content.Context

@Composable
fun ChatScreen(
    conversationId: String,
    modelPath: String,
    repository: ChatRepository,
    context: Context,
    viewModel: ChatViewModel = viewModel {
        ChatViewModel(context, repository, conversationId)
    }
) {
    var inputValue by remember { mutableStateOf(TextFieldValue("")) }
    var imagePaths by remember { mutableStateOf(emptyList<String>()) }
    var filePaths by remember { mutableStateOf(emptyList<String>()) }
    var workspaceLinks by remember { mutableStateOf(emptyList<String>()) }

    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val status by viewModel.modelStatus.collectAsState()
    val err by viewModel.error.collectAsState()
    val tokenCount by viewModel.tokenCount.collectAsState()
    val speed by viewModel.generationSpeed.collectAsState()
    val ctxUsage by viewModel.contextUsage.collectAsState()

    val activity = context as Activity

    val pickLinkLauncher = rememberLauncherForActivityResult(OpenDocument) { uri ->
        if (uri != null) {
            val path = uri.path ?: uri.lastPathSegment ?: uri.toString()
            workspaceLinks = workspaceLinks + path
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.loadModel(modelPath, viewModel.samplingParams)
    }

    LaunchedEffect(err) {
        if (err != null) {
            alertDialog(
                onDismissRequest = { viewModel.error.value = null },
                title = { Text("Error") },
                text = { Text(err) },
                confirmButton = { TextButton(onClick = { viewModel.error.value = null }) { Text("OK") } }
            )
        }
    }

    OfflineGgufChatTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Offline Chat") },
                    actions = {
                        IconButton(onClick = { /* open params */ }) {
                            Icon(androidx.compose.material3.Icons.Default.Tune, contentDescription = "Parameters")
                        }
                    }
                )
            },
            floatingActionButton = if (isLoading) {
                FloatingActionButton(onClick = { viewModel.stopGeneration() }) {
                    Icon(androidx.compose.material3.Icons.Default.Stop, contentDescription = "Stop")
                }
            } else null
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                LinearProgressIndicator(
                    progress = ctxUsage,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    color = if (ctxUsage > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Context: ${(ctxUsage * 100).toInt()}% used",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Model: ${java.io.File(modelPath).name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isLoading) {
                    Chip(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        label = {
                            Text(
                                text = "$tokenCount tokens  •  $speed",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        colors = ChipDefaults.chipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    )
                }

                ModelStatusChip(
                    text = java.io.File(modelPath).name,
                    status = status,
                    isMultimodal = viewModel.isMultimodal()
                )

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    contentPadding = padding
                ) {
                    items(items = messages, key = { it.id }) { msg ->
                        MessageBubble(message = msg)
                    }
                }

                if (imagePaths.isNotEmpty() || filePaths.isNotEmpty() || workspaceLinks.isNotEmpty()) {
                    androidx.compose.foundation.layout.HorizontalScroll(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                            imagePaths.forEach { path ->
                                AttachmentChip(
                                    type = "image",
                                    name = java.io.File(path).name,
                                    onRemove = { imagePaths = imagePaths.filter { it != path } }
                                )
                            }
                            filePaths.forEach { path ->
                                AttachmentChip(
                                    type = "file",
                                    name = java.io.File(path).name,
                                    onRemove = { filePaths = filePaths.filter { it != path } }
                                )
                            }
                            workspaceLinks.forEach { path ->
                                AttachmentChip(
                                    type = "link",
                                    name = java.io.File(path).name,
                                    onRemove = { workspaceLinks = workspaceLinks.filter { it != path } }
                                )
                            }
                        }
                    }
                }

                InputBar(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    onSend = {
                        if (inputValue.text.isNotBlank() || imagePaths.isNotEmpty() || filePaths.isNotEmpty() || workspaceLinks.isNotEmpty()) {
                            viewModel.sendMessage(
                                text = inputValue.text,
                                imagePaths = imagePaths,
                                filePaths = filePaths + workspaceLinks
                            )
                            inputValue = TextFieldValue("")
                            imagePaths = emptyList()
                            filePaths = emptyList()
                            workspaceLinks = emptyList()
                        }
                    },
                    onAttachImage = { /* gallery picker */ },
                    onAttachFile = {
                        pickLinkLauncher.launch(Intent())
                    }
                )
            }
        }
    }
}
