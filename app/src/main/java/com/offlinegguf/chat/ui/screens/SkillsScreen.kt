package com.offlinegguf.chat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.offlinegguf.chat.domain.model.Skill
import com.offlinegguf.chat.domain.model.SkillType
import com.offlinegguf.chat.ui.theme.OfflineGgufChatTheme
import com.offlinegguf.chat.utils.SkillsManager

@Composable
fun SkillsScreen(
    onSkillsChanged: ((List<Skill>) -> Unit)? = null
) {
    var skills by remember {
        mutableStateOf(
            listOf(
                Skill(name = "JSON Output", type = SkillType.GRAMMAR,
                      content = SkillsManager.builtInGrammars["json"], enabled = false),
                Skill(name = "SQL Output", type = SkillType.GRAMMAR,
                      content = SkillsManager.builtInGrammars["sql"], enabled = false),
                Skill(name = "List Output", type = SkillType.GRAMMAR,
                      content = SkillsManager.builtInGrammars["list"], enabled = false),
                Skill(name = "Helpful Assistant", type = SkillType.SYSTEM,
                      content = SkillsManager.builtInSystemPrompts["helpful"], enabled = true)
            )
        )
    }

    var newSkillName by remember { mutableStateOf("") }
    var newSkillType by remember { mutableStateOf(SkillType.SYSTEM) }
    var newSkillContent by remember { mutableStateOf("") }
    var showingAddDialog by remember { mutableStateOf(false) }

    OfflineGgufChatTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Skills") }) },
            floatingActionButton = FloatingActionButton(
                onClick = { showingAddDialog = true }
            ) { Icon(androidx.compose.material3.Icons.Default.Add, contentDescription = "Add skill") }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                if (showingAddDialog) {
                    AlertDialog(
                        onDismissRequest = { showingAddDialog = false },
                        title = { Text("Add Custom Skill") },
                        text = {
                            Column(modifier = Modifier.width(300.dp)) {
                                TextField(
                                    value = newSkillName,
                                    onValueChange = { newSkillName = it },
                                    label = { Text("Skill name") },
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                TextField(
                                    value = newSkillContent,
                                    onValueChange = { newSkillContent = it },
                                    label = { Text("Content (system prompt or grammar)") },
                                    singleLine = false,
                                    maxLines = 4
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                DropdownMenu(
                                    expanded = true,
                                    onDismissRequest = {},
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    SkillType.values().forEach { type ->
                                        DropdownMenuItem(
                                            onClick = {
                                                newSkillType = type
                                                // dismiss handled by parent state in real impl
                                            }
                                        ) { Text(type.name) }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                if (newSkillName.isNotBlank() && newSkillContent.isNotBlank()) {
                                    val newSkill = Skill(
                                        name = newSkillName,
                                        type = newSkillType,
                                        content = newSkillContent,
                                        enabled = true
                                    )
                                    skills = skills + newSkill
                                    onSkillsChanged?.invoke(skills)
                                }
                                newSkillName = ""
                                newSkillContent = ""
                                showingAddDialog = false
                            }) { Text("Add") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showingAddDialog = false
                            }) { Text("Cancel") }
                        }
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = padding
                ) {
                    skills.groupBy { it.type }.forEach { (type, typeSkills) ->
                        item {
                            Text(
                                text = when (type) {
                                    SkillType.GRAMMAR → "Grammar Constraints"
                                    SkillType.SYSTEM → "System Prompts"
                                    SkillType.LORA → "LoRA Adapters"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )
                        }
                        items(items = typeSkills, key = { it.id }) { skill ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.weight(1f).padding(12.dp)) {
                                        Text(
                                            text = skill.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.padding(bottom = 4.dp)
                                        )
                                        Text(
                                            text = SkillsManager.skillSummary(skill),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (skill.type == SkillType.SYSTEM) {
                                            Text(
                                                text = skill.content ?: "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                maxLines = 2,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = skill.enabled,
                                        onCheckedChange = { checked ->
                                            skills = skills.map { s ->
                                                if (s.id == skill.id) s.copy(enabled = checked) else s
                                            }
                                            onSkillsChanged?.invoke(skills)
                                        }
                                    )
                                    IconButton(onClick = {
                                        skills = skills.filter { it.id != skill.id }
                                        onSkillsChanged?.invoke(skills)
                                    }) {
                                        Icon(androidx.compose.material3.Icons.Default.Delete, contentDescription = "Delete")
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
