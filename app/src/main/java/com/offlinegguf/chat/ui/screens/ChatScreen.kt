package com.offlinegguf.chat.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.R
import com.offlinegguf.chat.data.export.launchShare
import com.offlinegguf.chat.data.settings.AppSettings
import com.offlinegguf.chat.data.settings.ChatMode
import com.offlinegguf.chat.ui.components.ContextGauge
import com.offlinegguf.chat.ui.components.EmptyState
import com.offlinegguf.chat.ui.components.InkButton
import com.offlinegguf.chat.ui.components.InkChip
import com.offlinegguf.chat.ui.components.InkIconButton
import com.offlinegguf.chat.ui.components.InkTopBar
import com.offlinegguf.chat.ui.components.InputBar
import com.offlinegguf.chat.ui.components.MessageRow
import com.offlinegguf.chat.ui.components.SectionTitle
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme
import com.offlinegguf.chat.util.Formats
import com.offlinegguf.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The transcript.
 *
 * Three states, and each one says what to do next: no engine (build without
 * llama.cpp), no model (pick one), ready (type). The composer never moves, so the
 * send/stop button is always under the thumb.
 */
@Composable
fun ChatScreen(
    container: AppContainer,
    settings: AppSettings,
    conversationId: Long?,
    onOpenModels: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSkills: () -> Unit,
) {
    val viewModel: ChatViewModel = viewModel(
        key = "chat-${conversationId ?: 0}",
        factory = ChatViewModel.factory(container, conversationId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val spacing = OfflineGgufTheme.spacing
    val colors = MaterialTheme.colorScheme
    val motion = OfflineGgufTheme.motion

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var modeMenuOpen by remember { mutableStateOf(false) }
    var showGauge by remember { mutableStateOf(false) }
    var pendingCodeSave by remember { mutableStateOf<Pair<String, String>?>(null) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.attach(it) }
    }
    val saveCodeFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val pending = pendingCodeSave
        pendingCodeSave = null
        if (uri != null && pending != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: error("Could not open the destination file")
                        output.use { it.write(pending.first.toByteArray(Charsets.UTF_8)) }
                    }
                }
                saved.onSuccess { viewModel.reportNotice("Saved ${pending.second}") }
                    .onFailure { viewModel.reportError(it.message ?: "Could not save the code file") }
            }
        }
    }

    // Follow the answer while it streams, unless the user scrolled back to read.
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= state.messages.size - 2
        }
    }
    LaunchedEffect(state.messages.size, state.streamingText.length) {
        if (state.messages.isNotEmpty() && atBottom) {
            val target = state.messages.size - 1
            if (settings.reduceMotion) listState.scrollToItem(target) else listState.animateScrollToItem(target)
        }
    }

    // The view model renders the transcript and writes the file off the main
    // thread; this only launches what it built, and says so when the phone has
    // nothing that can take the export.
    fun share(intent: Intent?) {
        if (!launchShare(context, intent)) {
            viewModel.reportError("Nothing on this phone can take that export.")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        InkTopBar(
            title = state.title,
            subtitle = when {
                state.loadingModel -> "Loading model…"
                state.model != null -> {
                    val model = state.model!!
                    "${model.displayName} · ${model.quantization.ifBlank { "gguf" }}" +
                        if (state.contextSize > 0) " · ${Formats.contextTokens(state.contextUsed.toLong())}/" +
                            Formats.contextTokens(state.contextSize.toLong()) else ""
                }
                else -> "No model loaded"
            },
            actions = {
                if (state.likes > 0) {
                    Row(
                        modifier = Modifier
                            .background(colors.surfaceContainerHigh, RoundedCornerShape(spacing.chip))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_heart),
                            contentDescription = null,
                            tint = colors.onSurface,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = state.likes.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurface,
                        )
                    }
                }
                Box {
                    InkIconButton(
                        icon = R.drawable.ic_brain,
                        description = "Chat mode: ${settings.chatMode.label}. Open chat modes and thinking options",
                        onClick = { modeMenuOpen = true },
                    )
                    DropdownMenu(expanded = modeMenuOpen, onDismissRequest = { modeMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Chat modes") },
                            enabled = false,
                            leadingIcon = { Icon(painterResource(R.drawable.ic_sparkle), null, Modifier.size(16.dp)) },
                            onClick = {},
                        )
                        ChatMode.values().forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(if (settings.chatMode == mode) "✓ ${mode.label}" else mode.label) },
                                onClick = {
                                    modeMenuOpen = false
                                    scope.launch { container.settings.setChatMode(mode) }
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (settings.showThinking) "Hide model reasoning" else "Show model reasoning") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_brain), null, Modifier.size(16.dp)) },
                            onClick = {
                                modeMenuOpen = false
                                scope.launch { container.settings.setShowThinking(!settings.showThinking) }
                            },
                        )
                    }
                }
                InkIconButton(
                    icon = R.drawable.ic_plus,
                    description = "New chat",
                    onClick = { viewModel.newConversation() },
                    enabled = !state.generating,
                )
                InkIconButton(
                    icon = R.drawable.ic_clock,
                    description = "History",
                    onClick = onOpenHistory,
                )
                Box {
                    InkIconButton(
                        icon = R.drawable.ic_more,
                        description = "More",
                        onClick = { menuOpen = true },
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Choose a model · ${container.catalog.entries.size} options " +
                                        "(${container.catalog.uncensored().size} refusal-free)",
                                )
                            },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_cube), null, Modifier.size(16.dp))
                            },
                            onClick = { menuOpen = false; onOpenModels() },
                        )
                        DropdownMenuItem(
                            text = { Text("Skills") },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_sparkle), null, Modifier.size(16.dp))
                            },
                            onClick = { menuOpen = false; onOpenSkills() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (showGauge) "Hide context gauge" else "Show context gauge") },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_gauge), null, Modifier.size(16.dp))
                            },
                            onClick = { menuOpen = false; showGauge = !showGauge; viewModel.refreshContext() },
                        )
                        DropdownMenuItem(
                            text = { Text("Clear context") },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_broom), null, Modifier.size(16.dp))
                            },
                            enabled = state.model != null,
                            onClick = { menuOpen = false; viewModel.clearContext() },
                        )
                        DropdownMenuItem(
                            text = { Text("Export as Markdown") },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_export), null, Modifier.size(16.dp))
                            },
                            enabled = state.messages.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                viewModel.exportMarkdown { intent -> share(intent) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export as JSON") },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_code), null, Modifier.size(16.dp))
                            },
                            enabled = state.messages.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                viewModel.exportJson { intent -> share(intent) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Unload model") },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_archive), null, Modifier.size(16.dp))
                            },
                            enabled = state.model != null,
                            onClick = { menuOpen = false; viewModel.unloadModel() },
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_sliders), null, Modifier.size(16.dp))
                            },
                            onClick = { menuOpen = false; onOpenSettings() },
                        )
                    }
                }
            },
        )

        AnimatedVisibility(
            visible = showGauge && state.model != null,
            enter = expandVertically(tween(motion.standard)) + fadeIn(tween(motion.standard)),
            exit = shrinkVertically(tween(motion.fast)) + fadeOut(tween(motion.fast)),
        ) {
            ContextGauge(
                used = state.contextUsed,
                total = state.contextSize,
                onClear = { viewModel.clearContext() },
                modifier = Modifier.padding(horizontal = spacing.gutter, vertical = spacing.sm),
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = spacing.gutter,
                    vertical = spacing.md,
                ),
                verticalArrangement = Arrangement.Top,
            ) {
                if (state.showEngineOnboarding) {
                    item(key = "onboarding") {
                        EngineOnboarding(
                            engineAvailable = state.engineAvailable,
                            engineNotice = state.engineNotice,
                            engineVersion = state.engineVersion,
                            loadingModel = state.loadingModel,
                            onOpenModels = onOpenModels,
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }

                if (!state.showEngineOnboarding && state.messages.isEmpty()) {
                    item(key = "empty") {
                        Column {
                            EmptyState(
                                icon = R.drawable.ic_chat,
                                title = when (settings.chatMode) {
                                    ChatMode.FAST -> "Quick answers, right here"
                                    ChatMode.CHAT -> "Start a conversation"
                                    ChatMode.CODING -> "What are we building?"
                                    ChatMode.CAREFUL -> "Let’s work through it carefully"
                                    ChatMode.BALANCED -> "A private chat, on your terms"
                                },
                                body = "Runs on this phone. No account, no upload, no network. " +
                                    "Choose a starter or write your own prompt.",
                            )
                            Text(
                                text = "QUICK START · ${settings.chatMode.label.uppercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.tertiary,
                                modifier = Modifier.padding(start = spacing.md, bottom = spacing.xs),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                            ) {
                                quickStarters(settings.chatMode).forEach { starter ->
                                    InkChip(text = starter, onClick = { viewModel.onInputChange(starter) })
                                }
                            }
                        }
                    }
                }

                items(items = state.messages, key = { it.id }) { message ->
                    val streaming = message.id == state.streamingMessageId
                    // The database is written a few times a second; the in-memory
                    // text is immediate, so paint that while streaming.
                    val hasStreamContent = state.streamingText.isNotBlank() || state.streamingThinking.isNotBlank()
                    val shown = if (streaming && hasStreamContent) {
                        message.copy(
                            content = state.streamingText.ifBlank { message.content },
                            thinkingText = state.streamingThinking.ifBlank { message.thinkingText },
                        )
                    } else message

                    MessageRow(
                        message = shown,
                        // Only the newest entry animates in; re-animating every row
                        // each time the list scrolls would be noise.
                        animateEntry = message.id == state.messages.lastOrNull()?.id,
                        streaming = streaming && state.generating,
                        previousCode = remember(message.id, state.messages.size) {
                            viewModel.previousCodeFor(message.id)
                        },
                        showThinking = settings.showThinking,
                        showLineNumbers = settings.showLineNumbers,
                        onCopy = { clipboard.setText(AnnotatedString(message.content)) },
                        onLike = { liked -> viewModel.toggleLike(message.id, liked) },
                        onFlag = { flagged -> viewModel.toggleFlag(message.id, flagged) },
                        onRegenerate = { viewModel.regenerate() },
                        canContinue = state.continuableMessageId == message.id,
                        onContinue = { viewModel.continueMessage(message.id) },
                        onSaveCode = { code, fileName ->
                            pendingCodeSave = code to fileName
                            saveCodeFile.launch(fileName)
                        },
                        onEdit = { viewModel.startEditing(message.id, message.content) },
                        onDeleteFrom = { viewModel.deleteFrom(message.id) },
                    )
                }

                item(key = "tail") { Spacer(Modifier.height(spacing.sm)) }
            }

            // Jump back to the answer when the user has scrolled away from it.
            // Fully qualified on purpose: this Box sits inside a Column, and the
            // ColumnScope overload of AnimatedVisibility would otherwise win
            // resolution and then refuse to be called from a BoxScope.
            androidx.compose.animation.AnimatedVisibility(
                visible = !atBottom && state.messages.size > 3,
                modifier = Modifier.align(Alignment.BottomEnd).padding(spacing.gutter),
                enter = fadeIn(tween(motion.fast)),
                exit = fadeOut(tween(motion.fast)),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(colors.primary, RoundedCornerShape(20.dp))
                        .clickable {
                            val target = state.messages.size - 1
                            if (target >= 0) {
                                scope.launch {
                                    if (settings.reduceMotion) listState.scrollToItem(target)
                                    else listState.animateScrollToItem(target)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = "Jump to latest",
                        tint = colors.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        NoticeBanner(
            notice = state.notice,
            error = state.error,
            onDismiss = {
                viewModel.consumeNotice()
                viewModel.dismissError()
            },
        )

        InputBar(
            value = state.input,
            onValueChange = viewModel::onInputChange,
            onSend = { viewModel.send() },
            onStop = { viewModel.stop() },
            generating = state.generating,
            enabled = !state.loadingModel,
            placeholder = if (state.editingMessageId != null) "Editing — send to resend" else "Ask anything…",
            skillLabel = state.skill?.let { "Skill · ${it.name}" },
            onOpenSkills = onOpenSkills,
            attachments = state.attachments.map { it.name },
            onAddAttachment = {
                pickFile.launch(arrayOf("text/*", "application/json", "application/xml", "text/csv"))
            },
            onRemoveAttachment = { viewModel.removeAttachment(it) },
            hint = when {
                state.loadingModel -> "Loading…"
                state.editingMessageId != null -> "Cancel with the back key"
                state.lastSpeed > 0 -> "${Formats.fixed(state.lastSpeed, 1)} tok/s last answer"
                else -> null
            },
        )
    }
}

private fun quickStarters(mode: ChatMode): List<String> = when (mode) {
    ChatMode.FAST -> listOf("Summarize this", "Explain in one line", "Give me the key point")
    ChatMode.CHAT -> listOf("Help me brainstorm", "Tell me a short story", "Let’s explore an idea")
    ChatMode.CODING -> listOf("Write a Python script", "Explain this error", "Review my code")
    ChatMode.CAREFUL -> listOf("Check my reasoning", "Compare these options", "Explain step by step")
    ChatMode.BALANCED -> listOf("Explain a topic simply", "Help me write", "Brainstorm ideas")
}

/** Inline banner; a snackbar would fight the composer for the bottom of the screen. */
@Composable
private fun NoticeBanner(
    notice: String?,
    error: String?,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    val motion = OfflineGgufTheme.motion
    val text = error ?: notice

    AnimatedVisibility(
        visible = text != null,
        enter = expandVertically(tween(motion.standard)) + fadeIn(tween(motion.standard)),
        exit = shrinkVertically(tween(motion.fast)) + fadeOut(tween(motion.fast)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (error != null) colors.primary else colors.surfaceContainerHigh)
                .padding(horizontal = spacing.gutter, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(if (error != null) R.drawable.ic_warning else R.drawable.ic_info),
                contentDescription = null,
                tint = if (error != null) colors.onPrimary else colors.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(spacing.sm))
            Text(
                text = text.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) colors.onPrimary else colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "Dismiss",
                    tint = if (error != null) colors.onPrimary else colors.onSurfaceVariant,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/**
 * Shown when there is nothing to talk to.
 *
 * If the native engine is absent the reason is printed verbatim rather than
 * paraphrased: it names the exact script and Gradle flag that enable it.
 */
@Composable
private fun EngineOnboarding(
    engineAvailable: Boolean,
    engineNotice: String?,
    engineVersion: String,
    loadingModel: Boolean,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.lg)
            .background(colors.surfaceContainer, RoundedCornerShape(spacing.card))
            .padding(spacing.lg),
    ) {
        SectionTitle(text = if (engineAvailable) "Almost ready" else "No inference engine in this build")

        Spacer(Modifier.height(spacing.sm))

        Text(
            text = if (engineAvailable) {
                "The engine is here. Load a GGUF file and the conversation stays on this device."
            } else {
                engineNotice ?: "This APK was built without llama.cpp."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )

        Spacer(Modifier.height(spacing.md))

        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            InkButton(
                label = if (loadingModel) "Loading…" else "Pick a model",
                onClick = onOpenModels,
                enabled = !loadingModel,
                icon = R.drawable.ic_cube,
            )
            InkButton(
                label = "Settings",
                onClick = onOpenSettings,
                filled = false,
                icon = R.drawable.ic_sliders,
            )
        }

        if (engineVersion.isNotBlank()) {
            Spacer(Modifier.height(spacing.sm))
            Text(
                text = "engine $engineVersion",
                style = MaterialTheme.typography.labelSmall,
                color = colors.tertiary,
            )
        }
    }
}
