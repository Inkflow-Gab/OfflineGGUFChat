package com.offlinegguf.chat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.offlinegguf.chat.domain.model.GgufModel
import com.offlinegguf.chat.ui.theme.OfflineGgufChatTheme
import com.offlinegguf.chat.ui.viewmodel.ModelPickerViewModel

@Composable
fun ModelPickerScreen(
    onPickFile: () -> Unit,
    viewModel: ModelPickerViewModel
) {
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.onError = { err ->
            error = err
        }
    }

    OfflineGgufChatTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Select GGUF Model") }) },
            floatingActionButton = FloatingActionButton(
                onClick = onPickFile
            ) { Icon(androidx.compose.material3.Icons.Default.Folder, contentDescription = "Pick GGUF") }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                error?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                Text(
                    text = "Choose any .gguf file from your phone storage.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "Works with open-source, uncensored, or any GGUF model you own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = { /* navigate to model browser */ },
                        modifier = Modifier.weight(0.5f)
                    ) {
                        Text(text = "Browse Folder")
                    }
                    Button(
                        onClick = { /* navigate to model zoo */ },
                        modifier = Modifier.weight(0.5f)
                    ) {
                        Text(text = "Model Zoo")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Low-RAM phone tips",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        listOf(
                            "Use Q4_K_M or Q2_K quant (small files)",
                            "Prefer models under 2 GB",
                            "Set context to 512–1024 tokens",
                            "Close other apps before loading"
                        ).forEach { tip ->
                            Text(
                                text = "• $tip",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }
                }

                Text(
                    text = "No model loaded yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 24.dp)
                )
            }
        }
    }
}
