package com.offlinegguf.chat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AttachmentChip(
    type: String,
    name: String,
    onRemove: () -> Unit
) {
    val icon = when (type) {
        "image" -> Icons.Default.Image
        "link" -> Icons.Default.Link
        else -> Icons.Default.InsertDriveFile
    }

    val chipColor = when (type) {
        "image" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        "link" -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Chip(
        onClick = {},
        modifier = Modifier.padding(horizontal = 4.dp),
        colors = ChipDefaults.chipColors(containerColor = chipColor)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = name, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
            }
        }
    }
}
