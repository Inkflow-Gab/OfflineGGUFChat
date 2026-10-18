package com.offlinegguf.chat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.LocalContext
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.offlinegguf.chat.ui.theme.OfflineGgufChatTheme
import com.offlinegguf.chat.ui.viewmodel.SettingsViewModel
import com.offlinegguf.chat.utils.PhoneInfo
import com.offlinegguf.chat.utils.SettingsRecommendations
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() }
) {
    val context = LocalContext.current
    val recommendations = SettingsRecommendations.all(context)

    OfflineGgufChatTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Settings") }) }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = padding
            ) {
                item {
                    Text(
                        text = "Recommended for your device",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${"%.1f".format(PhoneInfo.totalRamGB(context))} GB RAM • ${PhoneInfo.cpuCores()} cores • Low-RAM mode: ${PhoneInfo.isLowEndDevice(context)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    items(items = recommendations, key = { it.title }) { rec ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = rec.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = rec.value.toString(),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = androidx.compose.ui.text.FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        text = rec.reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Model storage",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Place .gguf files anywhere on device storage and pick them from the Model screen.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    ListItem(
                        text = { Text("Storage free") },
                        leadingContent = { Icon(androidx.compose.material3.Icons.Default.Storage, contentDescription = null) },
                        trailingContent = { Text("${"%.1f".format(PhoneInfo.storageFreeGB())} GB") }
                    )
                    ListItem(
                        text = { Text("Storage total") },
                        leadingContent = { Icon(androidx.compose.material3.Icons.Default.HardDrive, contentDescription = null) },
                        trailingContent = { Text("${"%.1f".format(PhoneInfo.storageTotalBytes() / (1024.0 * 1024.0 * 1024.0))} GB") }
                    )
                }

                item {
                    Text(
                        text = "Inference defaults",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Applied when loading a new GGUF model. Override per-chat if needed.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    ListItem(
                        text = { Text("Default temperature") },
                        trailingContent = { Text("${viewModel.defaultTemperature}") }
                    )
                    ListItem(
                        text = { Text("Default context (n_ctx)") },
                        trailingContent = { Text("${PhoneInfo.recommendedNctx(context)}") }
                    )
                    Switch(
                        checked = true,
                        onCheckedChange = {},
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Text("Enable memory-mapping (mmap) for large GGUF files")
                    }
                }

                item {
                    Text(
                        text = "App",
                        style = MaterialTheme.typography.titleMedium
                    )
                    ListItem(
                        text = { Text("About & phone info") },
                        leadingContent = { Icon(androidx.compose.material3.Icons.Default.Info, contentDescription = null) },
                        onClick = { /* navigate to about */ }
                    )
                    ListItem(
                        text = { Text("Export all chats") },
                        leadingContent = { Icon(androidx.compose.material3.Icons.Default.Download, contentDescription = null) },
                        onClick = { /* export */ }
                    )
                    ListItem(
                        text = { Text("Clear all data") },
                        leadingContent = { Icon(androidx.compose.material3.Icons.Default.DeleteForever, contentDescription = null) },
                        onClick = { /* clear */ }
                    )
                }
            }
        }
    }
}
