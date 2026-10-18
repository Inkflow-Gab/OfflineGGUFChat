package com.offlinegguf.chat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
fun InputBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onAttachImage: () -> Unit,
    onAttachFile: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        IconButton(onClick = onAttachImage) {
            Icon(androidx.compose.material3.Icons.Default.Image, contentDescription = "Attach image")
        }
        IconButton(onClick = onAttachFile) {
            Icon(androidx.compose.material3.Icons.Default.InsertDriveFile, contentDescription = "Attach file / link")
        }

        androidx.compose.material3.TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            placeholder = { Text("Type a message…") },
            singleLine = false,
            maxLines = 4,
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = TextFieldDefaults.textFieldColors(
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )

        IconButton(onClick = onSend, enabled = value.text.isNotBlank()) {
            Icon(androidx.compose.material3.Icons.Default.Send, contentDescription = "Send")
        }
    }
}
