package com.offlinegguf.chat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.offlinegguf.chat.domain.model.ModelInfo
import com.offlinegguf.chat.ui.theme.OfflineGgufChatTheme
import com.offlinegguf.chat.utils.ModelZoo
import android.content.Intent
import android.net.Uri

@Composable
fun ModelZooScreen(
    onModelSelected: ((ModelInfo) -> Unit)? = null
) {
    val models by remember { mutableStateOf(ModelZoo.models) }

    OfflineGgufChatTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Model Zoo") }) }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                Text(
                    text = "GGUF models from the llama.cpp ecosystem",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Download from Hugging Face, then pick the folder in the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = padding
                ) {
                    items(items = models, key = { it.id }) { model ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = model.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (model.isMultimodal) {
                                        Chip(
                                            label = { Text("Vision") },
                                            colors = ChipDefaults.chipColors(
                                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                            )
                                        )
                                    }
                                }

                                Text(
                                    text = model.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = "${model.sizeGB} GB",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "RAM: ${model.ramNeededGB} GB",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = model.quant,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    Column {
                                        model.tags.forEach { tag ->
                                            Text(
                                                text = "#$tag",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                modifier = Modifier.padding(end = 4.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            model.huggingFaceUrl.let { url ->
                                                if (url.isNotEmpty()) {
                                                    val intent = Intent(Intent.ACTION_VIEW).setData(Uri.parse(url))
                                                    LocalContext.current.startActivity(intent)
                                                }
                                            }
                                        }
                                    ) {
                                        Text(text = "Hugging Face")
                                    }

                                    Button(
                                        onClick = { onModelSelected?.invoke(model) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Text(text = "I have this model")
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "How to use",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        listOf(
                            "1. Tap 'Hugging Face' to download a .gguf file",
                            "2. Copy it to any folder on your phone",
                            "3. Open the app → Model Folder → select that folder",
                            "4. Tap 'Load Model' on the file",
                            "5. Start chatting — 100% offline"
                        ).forEach { step ->
                            Text(
                                text = step,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
