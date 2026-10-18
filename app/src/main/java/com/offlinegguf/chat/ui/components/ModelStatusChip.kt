package com.offlinegguf.chat.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ModelStatusChip(text: String, status: String, isMultimodal: Boolean = false) {
    val color = when {
        status.contains("Error") -> MaterialTheme.colorScheme.error
        status.contains("Loaded") || status.contains("Ready") -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Chip(
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = {
            Icon(
                imageVector = if (isMultimodal) Icons.Default.RemoveRedEye else Icons.Default.Cpu,
                contentDescription = null
            )
        },
        label = {
            Text(text = "$text • $status", style = MaterialTheme.typography.bodySmall)
        },
        colors = ChipDefaults.chipColors(containerColor = color.copy(alpha = 0.1f))
    )
}
