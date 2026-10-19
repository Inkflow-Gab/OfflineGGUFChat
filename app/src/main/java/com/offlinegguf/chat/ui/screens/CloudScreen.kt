package com.offlinegguf.chat.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.R
import com.offlinegguf.chat.data.cloud.CloudModel
import com.offlinegguf.chat.data.cloud.CloudProvider
import com.offlinegguf.chat.ui.components.EmptyState
import com.offlinegguf.chat.ui.components.InkButton
import com.offlinegguf.chat.ui.components.InkChip
import com.offlinegguf.chat.ui.components.InkIconButton
import com.offlinegguf.chat.ui.components.InkTopBar
import com.offlinegguf.chat.ui.components.SectionTitle
import com.offlinegguf.chat.ui.theme.MetaKeyText
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme
import com.offlinegguf.chat.util.Formats
import com.offlinegguf.chat.viewmodel.CloudViewModel

/**
 * Optional cloud chat: pick a provider, paste a free API key, choose a model.
 *
 * The whole feature is behind one switch. Off (the default) means the app is
 * exactly what it always was — no sockets, no account, nothing leaves the
 * phone. On means the Chat tab talks to the model picked here instead of a
 * local GGUF, using the user's own key.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun CloudScreen(
    container: AppContainer,
    onOpenChat: () -> Unit,
) {
    val viewModel: CloudViewModel = viewModel(factory = CloudViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    val motion = OfflineGgufTheme.motion
    val uriHandler = LocalUriHandler.current
    var keyVisible by remember { mutableStateOf(false) }

    fun open(url: String) {
        runCatching { uriHandler.openUri(url) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        InkTopBar(
            title = "Cloud",
            subtitle = if (state.enabled) {
                state.activeTarget?.label ?: "On — no model picked yet"
            } else {
                "Optional · off by default · your key stays on this phone"
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // ------------------------------------------------ master switch
            item(key = "master") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceContainer, RoundedCornerShape(spacing.card))
                        .padding(spacing.md),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Cloud chat",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (state.enabled) FontWeight.SemiBold else FontWeight.Normal,
                                color = colors.onSurface,
                            )
                            Text(
                                text = "Chat with big hosted models (many free) using your own API key. " +
                                    "Off means fully offline, exactly as before.",
                                style = MetaKeyText,
                                color = colors.tertiary,
                            )
                        }
                        Spacer(Modifier.width(spacing.sm))
                        Switch(
                            checked = state.enabled,
                            onCheckedChange = viewModel::setEnabled,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.onPrimary,
                                checkedTrackColor = colors.primary,
                                uncheckedThumbColor = colors.surface,
                                uncheckedTrackColor = colors.surfaceContainerHigh,
                                uncheckedBorderColor = colors.outline,
                            ),
                        )
                    }

                    val target = state.activeTarget
                    if (state.enabled && target != null) {
                        Spacer(Modifier.height(spacing.sm))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_cloud),
                                contentDescription = null,
                                tint = colors.onSurface,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Chatting with ${target.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(spacing.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            InkButton(label = "Open chat", icon = R.drawable.ic_chat, onClick = onOpenChat)
                            InkButton(
                                label = "Use on-device model",
                                icon = R.drawable.ic_phone,
                                filled = false,
                                onClick = viewModel::clearTarget,
                            )
                        }
                    }
                }
            }

            // ------------------------------------------------ notices
            state.error?.let { message ->
                item(key = "error") {
                    Banner(
                        icon = R.drawable.ic_warning,
                        text = message,
                        onDismiss = viewModel::dismissError,
                    )
                }
            }
            state.notice?.let { message ->
                item(key = "notice") {
                    Banner(
                        icon = R.drawable.ic_check,
                        text = message,
                        onDismiss = viewModel::consumeNotice,
                    )
                }
            }

            // ------------------------------------------------ setup
            item(key = "setup") {
                AnimatedVisibility(
                    visible = state.enabled,
                    enter = expandVertically(tween(motion.standard)) + fadeIn(tween(motion.standard)),
                    exit = shrinkVertically(tween(motion.fast)) + fadeOut(tween(motion.fast)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.surfaceContainer, RoundedCornerShape(spacing.card))
                                .padding(spacing.md),
                        ) {
                            SectionTitle(text = "Provider")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                CloudProvider.ALL.forEach { provider ->
                                    InkChip(
                                        text = provider.label,
                                        selected = state.providerId == provider.id,
                                        icon = if (provider.custom) R.drawable.ic_globe else R.drawable.ic_cloud,
                                        onClick = { viewModel.selectProvider(provider.id) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(spacing.sm))
                            Text(
                                text = state.provider.blurb,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )

                            if (state.provider.freeTierClientLocked) {
                                Spacer(Modifier.height(spacing.sm))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(colors.surfaceContainerHigh, RoundedCornerShape(spacing.chip))
                                        .padding(spacing.sm),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = "Free models here are app-only",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.onSurface,
                                    )
                                    Text(
                                        text = state.provider.label + " serves its free models only to its " +
                                            "own app, so they answer 403 here by design. Paid models work " +
                                            "with a Console key and credits.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "Free answers that work here:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.tertiary,
                                    )
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                        listOf(CloudProvider.GROQ, CloudProvider.OPENROUTER, CloudProvider.NVIDIA)
                                            .forEach { alt ->
                                                InkChip(
                                                    text = alt.label,
                                                    selected = state.providerId == alt.id,
                                                    icon = R.drawable.ic_cloud,
                                                    onClick = { viewModel.selectProvider(alt.id) },
                                                )
                                            }
                                    }
                                    if (state.appOnlyCount > 0) {
                                        InkChip(
                                            text = if (state.showAppOnly) {
                                                "Hide the ${state.appOnlyCount} app-only models"
                                            } else {
                                                "Show the ${state.appOnlyCount} app-only models anyway"
                                            },
                                            selected = state.showAppOnly,
                                            onClick = { viewModel.setShowAppOnly(!state.showAppOnly) },
                                        )
                                    }
                                }
                            }

                            if (state.provider.custom) {
                                Spacer(Modifier.height(spacing.md))
                                OutlinedTextField(
                                    value = state.customBaseUrl,
                                    onValueChange = viewModel::onCustomUrlChange,
                                    label = { Text("Server URL") },
                                    placeholder = { Text("http://192.168.1.10:11434/v1") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = fieldColors(),
                                )
                                Text(
                                    text = "Ollama: http://<pc-ip>:11434/v1 · LM Studio: http://<pc-ip>:1234/v1 · llama.cpp: http://<pc-ip>:8080/v1",
                                    style = MetaKeyText,
                                    color = colors.tertiary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }

                            Spacer(Modifier.height(spacing.md))
                            OutlinedTextField(
                                value = state.keyDraft,
                                onValueChange = viewModel::onKeyChange,
                                label = {
                                    Text(
                                        if (state.provider.requiresKey) {
                                            "${state.provider.label} API key"
                                        } else {
                                            "API key (optional)"
                                        },
                                    )
                                },
                                placeholder = { Text("sk-…") },
                                singleLine = true,
                                visualTransformation = if (keyVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    InkIconButton(
                                        icon = if (keyVisible) R.drawable.ic_eye_off else R.drawable.ic_eye,
                                        description = if (keyVisible) "Hide key" else "Show key",
                                        onClick = { keyVisible = !keyVisible },
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = fieldColors(),
                            )
                            Spacer(Modifier.height(spacing.sm))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            ) {
                                InkButton(
                                    label = if (state.models.isEmpty()) "Load models" else "Refresh models",
                                    icon = R.drawable.ic_refresh,
                                    enabled = !state.loading,
                                    onClick = viewModel::refreshModels,
                                )
                                if (state.loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = colors.onSurface,
                                    )
                                }
                                if (state.provider.keyUrl.isNotBlank()) {
                                    InkButton(
                                        label = "Get a free key",
                                        icon = R.drawable.ic_external,
                                        filled = false,
                                        onClick = { open(state.provider.keyUrl) },
                                    )
                                }
                            }
                            Text(
                                text = "The key is stored only in this app's private storage and sent only to ${state.provider.label}.",
                                style = MetaKeyText,
                                color = colors.tertiary,
                                modifier = Modifier.padding(top = spacing.sm),
                            )
                        }

                        // ---------------------------------------- model picker
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.surfaceContainer, RoundedCornerShape(spacing.card))
                                .padding(spacing.md),
                        ) {
                            SectionTitle(text = "Models · ${state.visibleModels.size} shown") {
                                InkChip(
                                    text = "Free only",
                                    selected = state.freeOnly,
                                    icon = R.drawable.ic_sparkle,
                                    onClick = { viewModel.setFreeOnly(!state.freeOnly) },
                                )
                            }
                            OutlinedTextField(
                                value = state.query,
                                onValueChange = viewModel::onQueryChange,
                                placeholder = { Text("Search models…") },
                                singleLine = true,
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_search),
                                        contentDescription = null,
                                        tint = colors.tertiary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = fieldColors(),
                            )
                            if (state.models.isNotEmpty()) {
                                Text(
                                    text = "${state.models.size} models on ${state.provider.label} · ${state.freeCount} free. Tap one to chat with it.",
                                    style = MetaKeyText,
                                    color = colors.tertiary,
                                    modifier = Modifier.padding(top = spacing.sm),
                                )
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------ model rows
            if (state.enabled) {
                if (state.models.isEmpty() && !state.loading) {
                    item(key = "empty-models") {
                        EmptyState(
                            icon = R.drawable.ic_cloud,
                            title = "No models loaded yet",
                            body = "Paste a free ${state.provider.label} key above, then tap “Load models” to browse what you can chat with — free ones are badged and listed first.",
                        )
                    }
                } else if (state.visibleModels.isEmpty() && !state.loading) {
                    item(key = "empty-filter") {
                        val hiddenAppOnly = state.provider.freeTierClientLocked &&
                            !state.showAppOnly && state.appOnlyCount > 0
                        EmptyState(
                            icon = R.drawable.ic_search,
                            title = if (hiddenAppOnly) "No usable free models" else "Nothing matches",
                            body = when {
                                hiddenAppOnly ->
                                    "The ${state.appOnlyCount} free models on ${state.provider.label} " +
                                        "only run inside its own app. Free models that work here live " +
                                        "under Groq, OpenRouter and NVIDIA — or show the app-only ones anyway."
                                state.freeOnly ->
                                    "No free model matches that search. Turn off “Free only” to see paid models too."
                                else -> "No model matches that search."
                            },
                            actionLabel = when {
                                hiddenAppOnly -> "Show app-only models"
                                state.freeOnly -> "Show all models"
                                else -> null
                            },
                            onAction = when {
                                hiddenAppOnly -> ({ viewModel.setShowAppOnly(true) })
                                state.freeOnly -> ({ viewModel.setFreeOnly(false) })
                                else -> null
                            },
                        )
                    }
                }
                items(state.visibleModels, key = { it.id }) { model ->
                    CloudModelRow(
                        model = model,
                        selected = state.activeTarget?.modelId == model.id &&
                            state.activeTarget?.providerId == state.providerId,
                        onClick = { viewModel.selectModel(model) },
                        modifier = Modifier.animateItemPlacement(tween(motion.standard)),
                    )
                }
            } else {
                item(key = "offline-note") {
                    EmptyState(
                        icon = R.drawable.ic_offline,
                        title = "Fully offline right now",
                        body = "This is the default and the app's home turf: GGUF models on the phone, no network at all. " +
                            "Turn cloud on only if you want hosted models like DeepSeek, Llama 405B or Qwen Max with your own free key.",
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloudModelRow(
    model: CloudModel,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (selected) colors.surfaceContainerHigh else colors.surfaceContainer,
                RoundedCornerShape(spacing.card),
            )
            .clickable(onClick = onClick)
            .padding(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = "Selected",
                    tint = colors.onSurface,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            text = model.id,
            style = MetaKeyText,
            color = colors.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(spacing.sm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            if (model.isFree) {
                InkChip(text = "FREE", selected = true, icon = R.drawable.ic_sparkle, onClick = onClick)
            }
            if (model.appOnly) {
                InkChip(text = "IN-APP ONLY", onClick = onClick)
            }
            model.priceSummary?.takeIf { !model.isFree }?.let { price ->
                InkChip(text = price, onClick = onClick)
            }
            if (model.contextLength > 0) {
                InkChip(text = "${Formats.contextTokens(model.contextLength.toLong())} ctx", onClick = onClick)
            }
        }
    }
}

@Composable
private fun Banner(icon: Int, text: String, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerHigh, RoundedCornerShape(spacing.card))
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = colors.onSurface,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        InkIconButton(icon = R.drawable.ic_close, description = "Dismiss", onClick = onDismiss)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedLabelColor = MaterialTheme.colorScheme.onSurface,
    unfocusedLabelColor = MaterialTheme.colorScheme.tertiary,
)
