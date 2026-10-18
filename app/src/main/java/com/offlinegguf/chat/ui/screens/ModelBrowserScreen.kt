package com.offlinegguf.chat.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.offlinegguf.chat.domain.model.GgufModel
import com.offlinegguf.chat.ui.theme.OfflineGgufChatTheme
import com.offlinegguf.chat.ui.viewmodel.ModelBrowserViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectAsState

@Composable
fun ModelBrowserScreen(
    activity: Activity,
    viewModel: ModelBrowserViewModel = viewModel()
) {
    var folderUri by remember { mutableStateOf<Uri?>(null) }
    var scanning by viewModel.isScanning.collectAsState()
    var models by viewModel.modelsFound.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }

    val pickFolderLauncher = rememberLauncherForActivityResult(OpenDocumentTree) { uri ->
        if (uri != null) {
            // Take persistable permission so we can access this folder later
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            folderUri = uri
            viewModel.scanFolder(activity, uri)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.onError = { err ->
            error = err
        }
        viewModel.onModelSelected = { model ->
            // This will be handled by navigation layer if needed
            // For now we just store it; the screen doesn't auto-navigate
        }
    }

    OfflineGgufChatTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Model Folder") }) },
            floatingActionButton = FloatingActionButton(
                onClick = { pickFolderLauncher.launch(Intent()) }
            ) { Icon(androidx.compose.material3.Icons.Default.FolderOpen, contentDescription = "Pick folder") }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                folderUri?.let { uri ->
                    Text(
                        text = "Folder: ${uri.lastPathSegment ?: uri.toString()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                error?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                if (scanning) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
                        Text(text = "Scanning for .gguf files…")
                    }
                } else if (folderUri != null && models.isEmpty()) {
                    Text(text = "No .gguf files found in this folder.", style = MaterialTheme.typography.bodyMedium)
                } else if (models.isNotEmpty()) {
                    Text(
                        text = "${models.size} model(s) found",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = padding
                    ) {
                        items(items = models, key = { it.path }) { model ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = model.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${(model.sizeBytes / 1024.0 / 1024.0 / 1024.0).toString().takeWhile { it != '.' }} GB",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = model.architecture ?: "GGUF",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (model.error != null) {
                                        Text(
                                            text = model.error!!,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            // The parent navigation needs to handle this.
                                            // We expose via a callback that AppNavigation sets.
                                            viewModel.onModelSelected?.invoke(model)
                                        }
                                    ) {
                                        Text(text = "Load Model")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
