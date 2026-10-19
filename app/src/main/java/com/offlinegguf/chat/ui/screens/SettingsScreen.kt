package com.offlinegguf.chat.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.BuildConfig
import com.offlinegguf.chat.R
import com.offlinegguf.chat.data.settings.AppSettings
import com.offlinegguf.chat.ui.components.ConfirmDialog
import com.offlinegguf.chat.ui.components.InkButton
import com.offlinegguf.chat.ui.components.InkChip
import com.offlinegguf.chat.ui.components.InkTopBar
import com.offlinegguf.chat.ui.components.KeyValueRow
import com.offlinegguf.chat.ui.components.SectionTitle
import com.offlinegguf.chat.ui.components.StatRow
import com.offlinegguf.chat.ui.theme.CodeText
import com.offlinegguf.chat.ui.theme.MetaKeyText
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme
import com.offlinegguf.chat.ui.theme.ThemeMode
import com.offlinegguf.chat.util.Formats
import com.offlinegguf.chat.viewmodel.SettingsViewModel
import kotlin.math.roundToInt

/**
 * Preferences, engine status and the data ledger.
 *
 * The engine block is the honest one: it prints the reason the native library is
 * absent verbatim, including the script and Gradle flag that build it in.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    settings: AppSettings,
    onBack: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    val motion = OfflineGgufTheme.motion
    val reduced = settings.reduceMotion

    var showSystemPrompt by remember { mutableStateOf(false) }
    var confirmArchived by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        InkTopBar(title = "Settings", subtitle = state.appVersion, onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // -------------------------------------------------------- engine
            item(key = "engine") {
                Section(title = "Engine") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (state.engineAvailable) colors.onSurface else colors.outline,
                                    RoundedCornerShape(4.dp),
                                ),
                        )
                        Spacer(Modifier.width(spacing.sm))
                        Text(
                            text = if (state.engineAvailable) "llama.cpp compiled in" else "Not compiled in",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                        )
                    }
                    Spacer(Modifier.height(spacing.sm))
                    if (state.engineAvailable) {
                        KeyValueRow("version", state.engineVersion.ifBlank { "unknown" })
                        KeyValueRow("loaded", if (state.engineLoaded) "yes" else "no")
                        state.engineModelPath?.let { KeyValueRow("model", it.substringAfterLast('/')) }
                        Spacer(Modifier.height(spacing.sm))
                        InkButton(
                            label = "Unload model",
                            onClick = { viewModel.unloadModel() },
                            filled = false,
                            icon = R.drawable.ic_archive,
                        )
                    } else {
                        Text(
                            text = state.engineNotice
                                ?: "This APK has no native engine. Header parsing, the catalogue and " +
                                "the fit estimates all still work.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(spacing.sm))
                        KeyValueRow("status", "metadata only")
                    }
                }
            }

            item(key = "device") {
                Section(title = "This phone") {
                    Text(
                        text = state.profileSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(spacing.sm))
                    StatRow(
                        items = listOf(
                            "ram" to Formats.bytes(state.profile.ramBytes),
                            "cores" to state.profile.cores.toString(),
                            "tier" to state.profile.tier.label,
                            "abi" to state.profile.primaryAbi,
                        ),
                        emphasizedIndex = 2,
                    )
                    Spacer(Modifier.height(spacing.sm))
                    KeyValueRow("api level", state.profile.apiLevel.toString())
                    KeyValueRow("model memory budget", Formats.bytes(state.profile.modelRamBudgetBytes))
                    KeyValueRow("suggested context", Formats.contextTokens(state.profile.suggestedContext.toLong()))
                    if (state.profile.pageSizeBytes > 0) {
                        KeyValueRow("page size", "${state.profile.pageSizeBytes / 1024} KB")
                    }
                    KeyValueRow("low-ram flag", state.profile.lowRamDevice.toString())
                }
            }

            // ------------------------------------------------------------ look
            item(key = "appearance") {
                Section(title = "Appearance") {
                    var fontScaleDraft by remember(state.settings.fontScale) {
                        mutableStateOf(state.settings.fontScale)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        ThemeMode.values().forEach { mode ->
                            InkChip(
                                text = mode.label,
                                selected = state.settings.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                            )
                        }
                    }
                    Spacer(Modifier.height(spacing.md))
                    Text(
                        text = "Text size · ${(fontScaleDraft * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    Slider(
                        value = fontScaleDraft,
                        onValueChange = { fontScaleDraft = it },
                        onValueChangeFinished = { viewModel.setFontScale(fontScaleDraft) },
                        valueRange = 0.85f..1.35f,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.onSurface,
                            activeTrackColor = colors.onSurface,
                            inactiveTrackColor = colors.surfaceContainerHigh,
                        ),
                    )
                    ToggleRow(
                        title = "Reduce motion",
                        subtitle = "Cuts transitions to instant; keeps the caret from blinking.",
                        checked = state.settings.reduceMotion,
                        onCheckedChange = { viewModel.setReduceMotion(it) },
                    )
                    ToggleRow(
                        title = "High contrast",
                        subtitle = "Pure black on pure white, no tinted greys.",
                        checked = state.settings.highContrast,
                        onCheckedChange = { viewModel.setHighContrast(it) },
                    )
                }
            }

            item(key = "reading") {
                Section(title = "Reading") {
                    ToggleRow(
                        title = "Line numbers in code",
                        subtitle = "Gutter numbers on fenced code blocks and diffs.",
                        checked = state.settings.showLineNumbers,
                        onCheckedChange = { viewModel.setShowLineNumbers(it) },
                    )
                    ToggleRow(
                        title = "Show reasoning",
                        subtitle = "Collapsed blocks for models that think before answering.",
                        checked = state.settings.showThinking,
                        onCheckedChange = { viewModel.setShowThinking(it) },
                    )
                    ToggleRow(
                        title = "Keep screen on while generating",
                        subtitle = "Uses a wake lock; no foreground service, no notification.",
                        checked = state.settings.keepScreenOn,
                        onCheckedChange = { viewModel.setKeepScreenOn(it) },
                    )
                    ToggleRow(
                        title = "Reload the last model on start",
                        subtitle = "Otherwise nothing is loaded until you pick one.",
                        checked = state.settings.autoLoadLastModel,
                        onCheckedChange = { viewModel.setAutoLoadLastModel(it) },
                    )
                    ToggleRow(
                        title = "Confirm before deleting",
                        subtitle = "Applies to chats and model files.",
                        checked = state.settings.confirmBeforeDelete,
                        onCheckedChange = { viewModel.setConfirmBeforeDelete(it) },
                    )
                    ToggleRow(
                        title = "Haptic feedback",
                        subtitle = "A tap on like, send and stop.",
                        checked = state.settings.haptics,
                        onCheckedChange = { viewModel.setHaptics(it) },
                    )
                }
            }

            // ------------------------------------------------------ generation
            item(key = "generation") {
                Section(title = "Generation defaults") {
                    SliderRow(
                        label = "temperature",
                        value = state.settings.temperature,
                        range = 0f..2f,
                        onChange = viewModel::setTemperature,
                        format = { Formats.fixed(it.toDouble(), 2) },
                    )
                    SliderRow(
                        label = "top-p",
                        value = state.settings.topP,
                        range = 0.1f..1f,
                        onChange = viewModel::setTopP,
                        format = { Formats.fixed(it.toDouble(), 2) },
                    )
                    SliderRow(
                        label = "top-k",
                        value = state.settings.topK.toFloat(),
                        range = 0f..200f,
                        steps = 40,
                        onChange = { v -> viewModel.setTopK(v.toInt()) },
                        format = { it.toInt().toString() },
                    )
                    SliderRow(
                        label = "min-p",
                        value = state.settings.minP,
                        range = 0f..0.5f,
                        onChange = viewModel::setMinP,
                        format = { Formats.fixed(it.toDouble(), 3) },
                    )
                    SliderRow(
                        label = "repeat penalty",
                        value = state.settings.repeatPenalty,
                        range = 0.8f..2f,
                        onChange = viewModel::setRepeatPenalty,
                        format = { Formats.fixed(it.toDouble(), 2) },
                    )
                    SliderRow(
                        label = "max tokens",
                        value = state.settings.maxTokens.toFloat(),
                        range = 16f..4096f,
                        steps = 63,
                        onChange = { v -> viewModel.setMaxTokens(v.toInt()) },
                        format = { it.toInt().toString() },
                    )
                    Text(
                        text = "Per-model settings on the model screen override these.",
                        style = MetaKeyText,
                        color = colors.tertiary,
                    )
                    Spacer(Modifier.height(spacing.sm))
                    InkChip(
                        text = if (showSystemPrompt) "Hide system prompt" else "Edit default system prompt",
                        onClick = { showSystemPrompt = !showSystemPrompt },
                        icon = R.drawable.ic_edit,
                    )
                    AnimatedVisibility(
                        visible = showSystemPrompt,
                        enter = if (reduced) fadeIn(tween(0))
                        else expandVertically(tween(motion.standard)) + fadeIn(tween(motion.standard)),
                        exit = if (reduced) fadeOut(tween(0))
                        else shrinkVertically(tween(motion.fast)) + fadeOut(tween(motion.fast)),
                    ) {
                        Column(modifier = Modifier.padding(top = spacing.sm)) {
                            OutlinedTextField(
                                value = state.settings.systemPrompt,
                                onValueChange = viewModel::setSystemPrompt,
                                modifier = Modifier.fillMaxWidth().height(160.dp),
                                textStyle = CodeText,
                                shape = RoundedCornerShape(spacing.field),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = colors.onSurface,
                                    unfocusedBorderColor = colors.outline,
                                    cursorColor = colors.onSurface,
                                ),
                            )
                            Spacer(Modifier.height(spacing.sm))
                            InkButton(
                                label = "Restore default",
                                onClick = { viewModel.restoreSystemPrompt() },
                                filled = false,
                                icon = R.drawable.ic_refresh,
                            )
                        }
                    }
                }
            }

            // ----------------------------------------------------------- data
            item(key = "data") {
                Section(title = "Data & storage") {
                    StatRow(
                        items = listOf(
                            "models" to state.modelCount.toString(),
                            "chats" to state.conversationCount.toString(),
                            "messages" to state.messageCount.toString(),
                            "likes" to state.totalLikes.toString(),
                        ),
                        emphasizedIndex = 3,
                    )
                    Spacer(Modifier.height(spacing.sm))
                    KeyValueRow("model storage", Formats.bytes(state.storageUsedByModels))
                    KeyValueRow("free space", Formats.bytes(state.profile.freeStorageBytes))
                    Spacer(Modifier.height(spacing.md))
                    InkButton(
                        label = "Delete archived chats",
                        onClick = {
                            if (state.settings.confirmBeforeDelete) confirmArchived = true
                            else viewModel.deleteArchivedConversations()
                        },
                        filled = false,
                        icon = R.drawable.ic_broom,
                    )
                    Spacer(Modifier.height(spacing.sm))
                    Text(
                        text = "Everything is on this device: a Room database for chats, " +
                            "copied .gguf files in app storage. Nothing is uploaded.",
                        style = MetaKeyText,
                        color = colors.tertiary,
                    )
                }
            }

            item(key = "about") {
                Section(title = "About") {
                    KeyValueRow("app", "Offline GGUF Chat")
                    KeyValueRow("version", "${state.appVersion} (${BuildConfig.VERSION_CODE})")
                    KeyValueRow("permissions", "WAKE_LOCK only — no INTERNET")
                    Spacer(Modifier.height(spacing.sm))
                    Text(
                        text = "GGUF headers are parsed twice, once in Kotlin and once in C++, and the two " +
                            "implementations are checked against the same golden fixtures in CI.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmArchived) {
        ConfirmDialog(
            title = "Delete archived chats?",
            body = "Every archived conversation and its messages are removed. Pinned and active chats stay.",
            confirmLabel = "Delete archived",
            onConfirm = {
                viewModel.deleteArchivedConversations()
                confirmArchived = false
            },
            onDismiss = { confirmArchived = false },
        )
    }

    state.notice?.let { notice ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceContainerHigh)
                .padding(horizontal = spacing.gutter, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(spacing.sm))
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            InkChip(text = "Dismiss", onClick = { viewModel.consumeNotice() })
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(spacing.card))
            .padding(spacing.md),
    ) {
        SectionTitle(text = title)
        Spacer(Modifier.height(spacing.sm))
        content()
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                color = colors.onSurface,
            )
            Text(text = subtitle, style = MetaKeyText, color = colors.tertiary)
        }
        Spacer(Modifier.width(spacing.sm))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
                uncheckedThumbColor = colors.surface,
                uncheckedTrackColor = colors.surfaceContainerHigh,
                uncheckedBorderColor = colors.outline,
            ),
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    format: (Float) -> String = { it.toInt().toString() },
) {
    val colors = MaterialTheme.colorScheme
    var draft by remember(label) { mutableStateOf(value) }
    LaunchedEffect(value) { draft = value }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = format(draft),
                style = CodeText,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
        }
        Slider(
            value = draft.coerceIn(range.start, range.endInclusive),
            onValueChange = { draft = it },
            onValueChangeFinished = { onChange(draft) },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = colors.onSurface,
                activeTrackColor = colors.onSurface,
                inactiveTrackColor = colors.surfaceContainerHigh,
            ),
        )
    }
}
