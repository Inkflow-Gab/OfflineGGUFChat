package com.offlinegguf.chat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.offlinegguf.chat.ui.theme.OfflineGgufChatTheme
import com.offlinegguf.chat.utils.PhoneInfo

@Composable
fun ModelDetailsScreen(
    modelPath: String? = null,
    architecture: String? = null,
    nVocab: Int? = null,
    nCtxTrain: Int? = null,
    isMultimodal: Boolean = false,
    sizeBytes: Long = 0,
    quantization: String? = null
) {
    val context = LocalContext.current

    OfflineGgufChatTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Model Details") }) }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                modelPath?.let { path ->
                    val file = java.io.File(path)
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = "Size: ${(sizeBytes / 1024.0 / 1024.0 / 1024.0).toString().takeWhile { it != '.' }} GB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )
                }

                Text(
                    text = "GGUF Information",
                    style = MaterialTheme.typography.titleMedium
                )
                DetailRow(label = "Architecture", value = architecture ?: "Unknown")
                DetailRow(label = "Vocabulary size", value = nVocab?.toString() ?: "Unknown")
                DetailRow(label = "Training context (n_ctx_train)", value = nCtxTrain?.toString() ?: "Unknown")
                DetailRow(label = "Quantization", value = quantization ?: "Unknown (embedded in GGUF)")
                DetailRow(
                    label = "Multimodal (vision)",
                    value = if (isMultimodal) "Yes — supports image attach" else "No — text only"
                )
                DetailRow(
                    label = "Source",
                    value = "Your device storage (offline)"
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Device Fit Recommendations",
                    style = MaterialTheme.typography.titleMedium
                )
                DetailRow(
                    label = "Recommended context",
                    value = "${PhoneInfo.recommendedNctx(context)} tokens"
                )
                DetailRow(
                    label = "Recommended threads",
                    value = "${PhoneInfo.recommendedThreads(context)}"
                )
                DetailRow(
                    label = "Suggested quantization",
                    value = PhoneInfo.recommendedQuantization(context)
                )
                DetailRow(
                    label = "RAM available",
                    value = "${"%.1f".format(PhoneInfo.availableRamGB(context))} GB free"
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "⚠️ This app runs models entirely on your device. You are responsible for the models you load — including uncensored or open-weight models. No data leaves your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                OutlinedButton(
                    onClick = { /* could copy path to clipboard */ },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Copy model path")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.5f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.5f)
        )
    }
}
