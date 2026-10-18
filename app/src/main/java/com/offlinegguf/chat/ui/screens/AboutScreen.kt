package com.offlinegguf.chat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.LocalContext
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.offlinegguf.chat.ui.theme.OfflineGgufChatTheme
import com.offlinegguf.chat.utils.PhoneInfo

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val info by remember { mutableStateOf(PhoneInfo.summary(context)) }

    OfflineGgufChatTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("About") }) }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                Text(
                    text = "Offline GGUF Chat",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Version 1.0.0 • Open Source",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "100% offline AI chat. Runs GGUF models locally via llama.cpp. No internet, no API keys, no cloud.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Text(
                    text = "Device Information",
                    style = MaterialTheme.typography.titleMedium
                )
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        info.lines().forEach { line ->
                            if (line.isNotBlank()) {
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "Built With",
                    style = MaterialTheme.typography.titleMedium
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        "llama.cpp — local GGUF inference",
                        "Jetpack Compose — UI toolkit",
                        "Room SQLite — local conversation history",
                        "Kotlin / Java — language",
                        "CMake / NDK — native bridge"
                    ).forEach { item ->
                        Text(
                            text = "• $item",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 2.dp, start = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "License: MIT",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Source code available on GitHub. Contributions welcome.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Button(
                    onClick = { /* could open browser to repo */ },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "View on GitHub")
                }
            }
        }
    }
}
