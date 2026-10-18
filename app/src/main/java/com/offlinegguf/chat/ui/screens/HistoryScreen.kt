package com.offlinegguf.chat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.offlinegguf.chat.data.repository.ChatRepository
import com.offlinegguf.chat.data.local.entity.Conversation
import com.offlinegguf.chat.ui.theme.OfflineGgufChatTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinegguf.chat.ui.viewmodel.HistoryViewModel

@Composable
fun HistoryScreen(
    repository: ChatRepository,
    viewModel: HistoryViewModel = viewModel { HistoryViewModel(repository) }
) {
    val conversations by viewModel.conversations.collectAsState()

    OfflineGgufChatTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("History") }) },
            floatingActionButton = FloatingActionButton(
                onClick = { viewModel.createNew("New Chat") }
            ) { Icon(androidx.compose.material3.Icons.Default.Add, contentDescription = "New Chat") }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = padding
            ) {
                items(items = conversations, key = { it.id }) { conv ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clickable { /* navigate to chat */ }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = conv.title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Text(
                                text = "${conv.messageCount} messages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
