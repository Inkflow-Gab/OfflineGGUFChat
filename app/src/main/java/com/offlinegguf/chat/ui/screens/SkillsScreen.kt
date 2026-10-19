package com.offlinegguf.chat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.R
import com.offlinegguf.chat.data.local.SkillEntity
import com.offlinegguf.chat.ui.components.ConfirmDialog
import com.offlinegguf.chat.ui.components.EmptyState
import com.offlinegguf.chat.ui.components.Hairline
import com.offlinegguf.chat.ui.components.InkButton
import com.offlinegguf.chat.ui.components.InkChip
import com.offlinegguf.chat.ui.components.InkIconButton
import com.offlinegguf.chat.ui.components.InkTopBar
import com.offlinegguf.chat.ui.components.KeyValueRow
import com.offlinegguf.chat.ui.components.SectionTitle
import com.offlinegguf.chat.ui.theme.CodeText
import com.offlinegguf.chat.ui.theme.MetaKeyText
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme
import com.offlinegguf.chat.viewmodel.SkillsViewModel

/**
 * Prompt presets.
 *
 * The built-ins cover the common shapes — a patch request that produces a unified
 * diff, strict JSON, bullet summaries, verbatim quotes. Custom skills can also be
 * used as reusable fictional personas or role presets. A skill can carry a GBNF
 * grammar, which constrains decoding so the output cannot leave the shape.
 */
@Composable
fun SkillsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: SkillsViewModel = viewModel(factory = SkillsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    var resetting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SkillEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        InkTopBar(
            title = "Skills",
            subtitle = "${state.skills.size} presets",
            onBack = if (state.draft == null) onBack else viewModel::cancelEdit,
            actions = {
                if (state.draft == null) {
                    InkIconButton(
                        icon = R.drawable.ic_refresh,
                        description = "Restore built-ins",
                        onClick = { resetting = true },
                    )
                    InkIconButton(
                        icon = R.drawable.ic_plus,
                        description = "New skill",
                        onClick = { viewModel.startNew() },
                    )
                }
            },
        )

        val draft = state.draft
        if (draft == null) {
            if (state.skills.isEmpty()) {
                EmptyState(
                    icon = R.drawable.ic_sparkle,
                    title = "No skills yet",
                    body = "Create reusable task presets or fictional personas with their own voice and rules. " +
                        "Restore the built-ins, or write your own.",
                    actionLabel = "Restore built-ins",
                    onAction = { viewModel.resetBuiltins() },
                    secondaryLabel = "New skill",
                    onSecondary = { viewModel.startNew() },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(spacing.gutter),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    items(items = state.skills, key = { it.slug }) { skill ->
                        SkillCard(
                            skill = skill,
                            onEdit = { viewModel.startEdit(skill) },
                            onDelete = { deleting = skill },
                            onUse = {
                                container.requestSkill(skill.slug)
                                viewModel.consumeNotice()
                                onBack()
                            },
                        )
                    }
                }
            }
        } else {
            SkillEditor(
                modifier = Modifier.weight(1f),
                draft = draft,
                isNew = state.isNew,
                grammarError = state.grammarError,
                onName = viewModel::setName,
                onSlug = viewModel::setSlug,
                onDescription = viewModel::setDescription,
                onSystemPrompt = viewModel::setSystemPrompt,
                onUserTemplate = viewModel::setUserTemplate,
                onGrammar = viewModel::setGrammar,
                onValidate = viewModel::validateGrammar,
                onTemperature = viewModel::setTemperature,
                onTopP = viewModel::setTopP,
                onMaxTokens = viewModel::setMaxTokens,
                onClearSampling = viewModel::clearSamplingOverrides,
                onSave = viewModel::save,
                onCancel = viewModel::cancelEdit,
            )
        }

        Banner(text = state.error ?: state.notice, isError = state.error != null) {
            viewModel.dismissError()
            viewModel.consumeNotice()
        }
    }

    if (resetting) {
        ConfirmDialog(
            title = "Restore built-in skills?",
            body = "Your edits to the built-in presets are overwritten. Custom skills you wrote are kept.",
            confirmLabel = "Restore",
            onConfirm = {
                viewModel.resetBuiltins()
                resetting = false
            },
            onDismiss = { resetting = false },
        )
    }

    deleting?.let { skill ->
        ConfirmDialog(
            title = "Delete \"${skill.name}\"?",
            body = if (skill.builtin) {
                "This is a built-in preset. Deleting removes it until you restore the built-ins."
            } else "The preset is removed. Chats that already used it keep their saved prompt.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.delete(skill.slug)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun SkillCard(
    skill: SkillEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUse: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(spacing.card))
            .padding(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(if (skill.builtin) R.drawable.ic_shield else R.drawable.ic_sparkle),
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = skill.slug,
                    style = CodeText,
                    color = colors.tertiary,
                )
            }
            InkIconButton(icon = R.drawable.ic_edit, description = "Edit", onClick = onEdit, size = 16)
            InkIconButton(icon = R.drawable.ic_trash, description = "Delete", onClick = onDelete, size = 16)
        }

        if (skill.description.isNotBlank()) {
            Spacer(Modifier.height(spacing.sm))
            Text(
                text = skill.description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            if (skill.builtin) InkChip(text = "built-in")
            if (!skill.grammar.isNullOrBlank()) InkChip(text = "grammar", icon = R.drawable.ic_code, selected = true)
            if (skill.userTemplate.isNotBlank()) InkChip(text = "template", icon = R.drawable.ic_text)
            if (skill.temperature != null) InkChip(text = "temp ${skill.temperature}")
            if (skill.maxTokens != null) InkChip(text = "${skill.maxTokens} tok")
            if (skill.useCount > 0) InkChip(text = "used ${skill.useCount}×")
        }

        Spacer(Modifier.height(spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            InkButton(label = "Use in chat", onClick = onUse, icon = R.drawable.ic_play)
            InkButton(label = "Edit", onClick = onEdit, filled = false)
        }
    }
}

@Composable
private fun SkillEditor(
    modifier: Modifier = Modifier,
    draft: SkillEntity,
    isNew: Boolean,
    grammarError: String?,
    onName: (String) -> Unit,
    onSlug: (String) -> Unit,
    onDescription: (String) -> Unit,
    onSystemPrompt: (String) -> Unit,
    onUserTemplate: (String) -> Unit,
    onGrammar: (String) -> Unit,
    onValidate: () -> Unit,
    onTemperature: (Float?) -> Unit,
    onTopP: (Float?) -> Unit,
    onMaxTokens: (Int?) -> Unit,
    onClearSampling: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.onSurface,
        unfocusedBorderColor = colors.outline,
        cursorColor = colors.onSurface,
        focusedContainerColor = colors.surface,
        unfocusedContainerColor = colors.surface,
    )

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.gutter, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            SectionTitle(text = if (isNew) "New skill" else "Edit ${draft.name.ifBlank { draft.slug }}")

            Field(label = "Name", value = draft.name, onValue = onName, colors = fieldColors)
            if (isNew) {
                Field(
                    label = "Slug (lowercase, no spaces)",
                    value = draft.slug,
                    onValue = onSlug,
                    colors = fieldColors,
                    mono = true,
                )
            } else {
                KeyValueRow(key = "slug", value = draft.slug)
            }
            Field(
                label = "Description",
                value = draft.description,
                onValue = onDescription,
                colors = fieldColors,
            )

            Hairline()
            Field(
                label = "System prompt",
                value = draft.systemPrompt,
                onValue = onSystemPrompt,
                colors = fieldColors,
                minLines = 4,
                hint = "Appended after your default prompt. Define a reusable task role or fictional persona and its speaking style.",
            )
            Field(
                label = "User template (optional)",
                value = draft.userTemplate,
                onValue = onUserTemplate,
                colors = fieldColors,
                minLines = 3,
                mono = true,
                hint = "{{input}} and {{attachments}} are replaced. Without {{input}} your text is appended.",
            )

            Hairline()
            Field(
                label = "GBNF grammar (optional)",
                value = draft.grammar.orEmpty(),
                onValue = onGrammar,
                colors = fieldColors,
                minLines = 5,
                mono = true,
                hint = "root = …  Constrains decoding. Empty means unconstrained.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                InkButton(label = "Validate grammar", onClick = onValidate, filled = false, icon = R.drawable.ic_check)
                if (grammarError == null && draft.grammar != null) {
                    Text(text = "grammar looks fine", style = MetaKeyText, color = colors.tertiary)
                }
            }
            if (grammarError != null) {
                Text(text = grammarError, style = CodeText, color = colors.onSurface)
            }

            Hairline()
            SectionTitle(text = "Sampling overrides")
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                NumberField(
                    label = "temperature",
                    value = draft.temperature?.toString() ?: "",
                    colors = fieldColors,
                    modifier = Modifier.weight(1f),
                    onValue = { onTemperature(it.toFloatOrNull()) },
                )
                NumberField(
                    label = "top-p",
                    value = draft.topP?.toString() ?: "",
                    colors = fieldColors,
                    modifier = Modifier.weight(1f),
                    onValue = { onTopP(it.toFloatOrNull()) },
                )
                NumberField(
                    label = "max tokens",
                    value = draft.maxTokens?.toString() ?: "",
                    colors = fieldColors,
                    modifier = Modifier.weight(1f),
                    onValue = { onMaxTokens(it.toIntOrNull()) },
                )
            }
            Text(
                text = "Blank means \"use the model's setting\". A grammar forces greedy sampling.",
                style = MetaKeyText,
                color = colors.tertiary,
            )
            InkButton(label = "Clear overrides", onClick = onClearSampling, filled = false)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = spacing.gutter, vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
        InkButton(label = "Save skill", onClick = onSave, icon = R.drawable.ic_check, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    colors: TextFieldColors,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    mono: Boolean = false,
    hint: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val supporting: (@Composable () -> Unit)? =
            if (hint == null) null else {
                { Text(hint, style = MetaKeyText, color = MaterialTheme.colorScheme.tertiary) }
            }
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth().heightIn(min = (minLines * 22 + 24).dp, max = 320.dp),
            textStyle = if (mono) CodeText else MaterialTheme.typography.bodyMedium,
            minLines = minLines,
            maxLines = 12,
            shape = RoundedCornerShape(OfflineGgufTheme.spacing.field),
            colors = colors,
            supportingText = supporting,
        )
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    colors: TextFieldColors,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = modifier,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        textStyle = CodeText,
        shape = RoundedCornerShape(OfflineGgufTheme.spacing.field),
        colors = colors,
    )
}

@Composable
private fun Banner(text: String?, isError: Boolean, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    if (text.isNullOrBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isError) colors.primary else colors.surfaceContainerHigh)
            .padding(horizontal = spacing.gutter, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(if (isError) R.drawable.ic_warning else R.drawable.ic_info),
            contentDescription = null,
            tint = if (isError) colors.onPrimary else colors.onSurfaceVariant,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) colors.onPrimary else colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text(
                text = "Dismiss",
                style = MaterialTheme.typography.labelMedium,
                color = if (isError) colors.onPrimary else colors.onSurfaceVariant,
            )
        }
    }
}
