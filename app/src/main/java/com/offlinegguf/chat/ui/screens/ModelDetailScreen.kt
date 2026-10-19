package com.offlinegguf.chat.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.R
import com.offlinegguf.chat.data.gguf.GgufDtype
import com.offlinegguf.chat.data.settings.AppSettings
import com.offlinegguf.chat.domain.fit.ModelFit
import com.offlinegguf.chat.ui.components.ConfirmDialog
import com.offlinegguf.chat.ui.components.Hairline
import com.offlinegguf.chat.ui.components.InkButton
import com.offlinegguf.chat.ui.components.InkChip
import com.offlinegguf.chat.ui.components.InkIconButton
import com.offlinegguf.chat.ui.components.InkTopBar
import com.offlinegguf.chat.ui.components.KeyValueRow
import com.offlinegguf.chat.ui.components.SectionTitle
import com.offlinegguf.chat.ui.components.StatChip
import com.offlinegguf.chat.ui.components.StatRow
import com.offlinegguf.chat.ui.components.VerdictBadge
import com.offlinegguf.chat.ui.theme.CodeText
import com.offlinegguf.chat.ui.theme.MetaKeyText
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme
import com.offlinegguf.chat.util.Formats
import com.offlinegguf.chat.viewmodel.KvTypes
import com.offlinegguf.chat.viewmodel.ModelDetailViewModel
import com.offlinegguf.chat.viewmodel.ModelTuning
import kotlin.math.roundToInt

/**
 * One file, fully explained.
 *
 * This is the screen that makes the "real GGUF handling" visible: the header is
 * parsed here, the memory arithmetic that decides whether it will run on *this*
 * phone is shown line by line, and the engine settings are tuned against it
 * before anything is loaded.
 */
@Composable
fun ModelDetailScreen(
    container: AppContainer,
    settings: AppSettings,
    path: String,
    onBack: () -> Unit,
    onUse: () -> Unit,
) {
    val viewModel: ModelDetailViewModel = viewModel(
        key = "model-$path",
        factory = ModelDetailViewModel.factory(container, path),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showMetadata by remember { mutableStateOf(false) }
    var showTensors by remember { mutableStateOf(false) }

    val model = state.model
    val probe = state.probe

    Column(modifier = Modifier.fillMaxSize()) {
        InkTopBar(
            title = model?.displayName ?: "Model",
            subtitle = model?.fileName ?: path.substringAfterLast('/'),
            onBack = onBack,
            actions = {
                InkIconButton(
                    icon = R.drawable.ic_refresh,
                    description = "Re-read the header",
                    onClick = { viewModel.reprobe() },
                    enabled = !state.probing,
                )
                Box {
                    InkIconButton(
                        icon = R.drawable.ic_more,
                        description = "More",
                        onClick = { menuOpen = true },
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Copy path") },
                            onClick = {
                                menuOpen = false
                                clipboard.setText(AnnotatedString(path))
                            },
                        )
                        model?.sourceUrl?.let { url ->
                            DropdownMenuItem(
                                text = { Text("Open source page") },
                                onClick = {
                                    menuOpen = false
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (state.loaded) "Unload" else "Load into engine") },
                            enabled = state.engineAvailable,
                            onClick = {
                                menuOpen = false
                                if (state.loaded) viewModel.unload() else viewModel.loadThenChat(onUse)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete file") },
                            onClick = {
                                menuOpen = false
                                if (settings.confirmBeforeDelete) confirmDelete = true else viewModel.delete(onBack)
                            },
                        )
                    }
                }
            },
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // ---------------------------------------------------------- hero
            item(key = "hero") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceContainer, RoundedCornerShape(spacing.card))
                        .padding(spacing.md),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        state.assessment?.let { VerdictBadge(verdict = it.verdict) }
                        if (probe?.loadable == false) InkChip(text = "will not load", icon = R.drawable.ic_warning)
                        if (probe?.multimodal == true) InkChip(text = "multimodal", icon = R.drawable.ic_layers)
                        if (probe?.chatTemplate?.isNotBlank() == true) InkChip(text = "chat template")
                        if (state.loaded) InkChip(text = "loaded", icon = R.drawable.ic_check, selected = true)
                    }

                    Spacer(Modifier.height(spacing.md))

                    StatRow(
                        items = listOfNotNull(
                            "size" to Formats.bytes(model?.sizeBytes ?: probe?.fileSize ?: 0),
                            if ((model?.paramCount ?: probe?.paramCount ?: 0) > 0) {
                                "params" to Formats.paramCount(model?.paramCount ?: probe!!.paramCount)
                            } else null,
                            if ((probe?.bitsPerWeight ?: 0.0) > 0) {
                                "bits/weight" to Formats.fixed(probe!!.bitsPerWeight, 2)
                            } else null,
                            if ((model?.quantization?.isNotBlank() == true)) {
                                "quant" to model!!.quantization
                            } else null,
                        ),
                        emphasizedIndex = 0,
                    )

                    probe?.error?.let { error ->
                        Spacer(Modifier.height(spacing.sm))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurface,
                        )
                    }
                }
            }

            // ----------------------------------------------------------- fit
            state.assessment?.let { assessment ->
                item(key = "fit") { FitCard(assessment = assessment, advice = state.advice) }
            }

            // -------------------------------------------------------- tuning
            item(key = "tuning") {
                TuningCard(
                    tuning = state.tuning,
                    assessment = state.assessment,
                    profileContext = state.profile.suggestedContext,
                    trainedContext = (probe?.contextLength ?: 0L).toInt(),
                    engineAvailable = state.engineAvailable,
                    onContext = viewModel::setContext,
                    onThreads = viewModel::setThreads,
                    onBatch = viewModel::setBatch,
                    onGpuLayers = viewModel::setGpuLayers,
                    onMmap = viewModel::setMmap,
                    onKvType = viewModel::setKvType,
                    onSuggested = viewModel::useSuggested,
                    onSave = viewModel::saveTuning,
                )
            }

            item(key = "sampling") {
                SamplingCard(
                    tuning = state.tuning,
                    onTemperature = viewModel::setTemperature,
                    onTopP = viewModel::setTopP,
                    onTopK = viewModel::setTopK,
                    onMinP = viewModel::setMinP,
                    onRepeat = viewModel::setRepeatPenalty,
                    onMaxTokens = viewModel::setMaxTokens,
                    onSave = viewModel::saveTuning,
                )
            }

            // -------------------------------------------------------- header
            if (probe != null) {
                item(key = "header") {
                    Section(title = "Header") {
                        KeyValueRow("gguf version", "v${probe.version}")
                        KeyValueRow("architecture", probe.architecture.ifBlank { "unknown" })
                        KeyValueRow("general.name", probe.name.ifBlank { "—" })
                        KeyValueRow("tensors", probe.tensorCount.toString())
                        KeyValueRow("key-value pairs", probe.kvCount.toString())
                        KeyValueRow("alignment", probe.alignment.toString())
                        KeyValueRow("tensor table", Formats.bytes(probe.tensorTableBytes))
                        KeyValueRow("data starts at", Formats.bytes(probe.dataStart))
                        KeyValueRow("file size", Formats.bytes(probe.fileSize))
                        if (probe.contextLength > 0) {
                            KeyValueRow("context length", Formats.contextTokens(probe.contextLength))
                        }
                        if (probe.vocabSize > 0) KeyValueRow("vocab size", probe.vocabSize.toString())
                        if (probe.tokenCount > 0) KeyValueRow("tokenizer tokens", probe.tokenCount.toString())
                        if (probe.blockCount > 0) KeyValueRow("blocks", probe.blockCount.toString())
                        if (probe.embeddingLength > 0) {
                            KeyValueRow("embedding length", probe.embeddingLength.toString())
                        }
                        if (probe.headCount > 0) KeyValueRow("attention heads", probe.headCount.toString())
                        probe.int("${probe.architecture}.attention.head_count_kv")?.let {
                            KeyValueRow("kv heads", it.toString())
                        }
                        probe.tokenizerModel?.let { KeyValueRow("tokenizer", it) }
                        if (probe.eosTokenId != -1L) KeyValueRow("eos id", probe.eosTokenId.toString())
                        if (probe.bosTokenId != -1L) KeyValueRow("bos id", probe.bosTokenId.toString())
                        KeyValueRow("dominant dtype", GgufDtype.fromId(probe.dominantDtype)?.label ?: "unknown")
                        KeyValueRow(
                            "read",
                            "${Formats.bytes(probe.bytesScanned)} in ${probe.elapsedMs} ms",
                        )
                    }
                }

                item(key = "metadata") {
                    ExpandableSection(
                        title = "Metadata",
                        subtitle = "${probe.metadata.size} keys",
                        expanded = showMetadata,
                        onToggle = { showMetadata = !showMetadata },
                    ) {
                        probe.metadata.entries.take(80).forEach { (key, value) ->
                            KeyValueRow(key = key, value = value.render(), mono = true)
                        }
                        if (probe.metadata.size > 80) {
                            Text(
                                text = "… ${probe.metadata.size - 80} more keys not shown",
                                style = MetaKeyText,
                                color = colors.tertiary,
                            )
                        }
                    }
                }

                if (probe.tensorDtypes.isNotEmpty()) {
                    item(key = "tensors") {
                        ExpandableSection(
                            title = "Tensors",
                            subtitle = "${probe.tensorsScanned} scanned",
                            expanded = showTensors,
                            onToggle = { showTensors = !showTensors },
                        ) {
                            probe.tensorDtypes.take(60).forEach { (name, dtype) ->
                                KeyValueRow(
                                    key = name,
                                    value = GgufDtype.fromId(dtype)?.label ?: dtype.toString(),
                                )
                            }
                        }
                    }
                }
            }

            item(key = "device") {
                Section(title = "This phone") {
                    KeyValueRow("ram", Formats.bytes(state.profile.ramBytes))
                    KeyValueRow("cores", state.profile.cores.toString())
                    KeyValueRow("tier", state.profile.tier.name.lowercase())
                    KeyValueRow("abi", state.profile.primaryAbi)
                    KeyValueRow("api level", state.profile.apiLevel.toString())
                    KeyValueRow("free storage", Formats.bytes(state.profile.freeStorageBytes))
                    if (state.profile.pageSizeBytes > 0) {
                        KeyValueRow("page size", "${state.profile.pageSizeBytes / 1024} KB")
                    }
                    val notice = state.engineNotice
                    KeyValueRow("engine", if (state.engineAvailable) notice ?: "native" else "absent")
                    if (!state.engineAvailable && notice != null) {
                        Spacer(Modifier.height(spacing.sm))
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "source") {
                Section(title = "Provenance") {
                    KeyValueRow("path", path)
                    if (model?.sourceUrl != null) KeyValueRow("source", model.sourceUrl)
                    if (model?.catalogId != null) KeyValueRow("catalogue id", model.catalogId)
                    KeyValueRow("added", Formats.relativeTime(model?.addedAt ?: 0))
                    if ((model?.lastUsedAt ?: 0) > 0) {
                        KeyValueRow("last used", Formats.relativeTime(model!!.lastUsedAt))
                    }
                    KeyValueRow("uses", (model?.useCount ?: 0).toString())
                    Spacer(Modifier.height(spacing.sm))
                    model?.sourceUrl?.let { url ->
                        InkButton(
                            label = "Open on Hugging Face",
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            },
                            filled = false,
                            icon = R.drawable.ic_external,
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------- sticky foot
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = spacing.gutter, vertical = spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.loaded) {
                InkButton(
                    label = "Unload",
                    onClick = { viewModel.unload() },
                    filled = false,
                    icon = R.drawable.ic_archive,
                )
            }
            InkButton(
                label = when {
                    state.starting -> "Loading…"
                    state.loaded -> "Chat with it"
                    else -> "Load and chat"
                },
                onClick = { if (state.loaded) onUse() else viewModel.loadThenChat(onUse) },
                enabled = !state.starting && state.engineAvailable && state.fileExists &&
                    (state.assessment?.usable != false),
                icon = R.drawable.ic_play,
                modifier = Modifier.weight(1f),
            )
        }
        if (!state.engineAvailable) {
            Text(
                text = state.engineNotice ?: "This build has no inference engine",
                style = MetaKeyText,
                color = colors.tertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(horizontal = spacing.gutter)
                    .padding(bottom = spacing.sm),
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this model?",
            body = "${Formats.bytes(model?.sizeBytes ?: 0)} will be removed from app storage. " +
                "This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                viewModel.delete(onBack)
            },
            onDismiss = { confirmDelete = false },
        )
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text("Could not do that") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun FitCard(assessment: ModelFit.Assessment, advice: String) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing

    Section(title = "Will it run here?") {
        LinearProgressIndicator(
            progress = { assessment.pressure.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = if (assessment.pressure > 0.85f) colors.onSurface else colors.primary,
            trackColor = colors.surfaceContainerHigh,
        )
        Spacer(Modifier.height(spacing.sm))
        KeyValueRow("memory pressure", "${(assessment.pressure * 100).roundToInt()}% of budget")
        KeyValueRow("weights", Formats.bytes(assessment.weightBytes))
        KeyValueRow("kv cache", Formats.bytes(assessment.kvBytes))
        KeyValueRow("overhead", Formats.bytes(assessment.overheadBytes))
        KeyValueRow("peak", Formats.bytes(assessment.peakRamBytes))
        KeyValueRow("budget", Formats.bytes(assessment.budgetBytes))
        KeyValueRow(
            "headroom",
            Formats.bytes(assessment.headroomBytes),
            valueColor = if (assessment.headroomBytes < 0) colors.onSurface else colors.onSurfaceVariant,
        )
        KeyValueRow("expected speed", assessment.speedLabel +
            if (assessment.tokensPerSecond > 0) " · ~${Formats.fixed(assessment.tokensPerSecond, 1)} tok/s" else "")
        KeyValueRow(
            "settings",
            "n_ctx ${assessment.settings.context} · ${assessment.settings.threads} threads · " +
                "batch ${assessment.settings.batch} · ${assessment.settings.kvType}",
        )

        if (assessment.warnings.isNotEmpty() || assessment.notes.isNotEmpty()) {
            Spacer(Modifier.height(spacing.sm))
            Hairline()
            Spacer(Modifier.height(spacing.sm))
            assessment.warnings.forEach { warning ->
                Row(modifier = Modifier.padding(bottom = 2.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = colors.onSurface,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            assessment.notes.forEach { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }

        if (advice.isNotBlank()) {
            Spacer(Modifier.height(spacing.sm))
            Text(text = advice, style = CodeText, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun TuningCard(
    tuning: ModelTuning,
    assessment: ModelFit.Assessment?,
    profileContext: Int,
    trainedContext: Int,
    engineAvailable: Boolean,
    onContext: (Int) -> Unit,
    onThreads: (Int) -> Unit,
    onBatch: (Int) -> Unit,
    onGpuLayers: (Int) -> Unit,
    onMmap: (Boolean) -> Unit,
    onKvType: (String) -> Unit,
    onSuggested: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    val maxContext = maxOf(
        4096,
        trainedContext.takeIf { it > 0 } ?: profileContext,
        tuning.nCtx,
    )

    Section(title = "Engine") {
        SliderRow(
            label = "context (n_ctx)",
            value = tuning.nCtx.toFloat(),
            range = 256f..maxContext.toFloat(),
            steps = ((maxContext - 256) / 256).coerceIn(0, 60),
            onValue = onContext,
            format = { Formats.contextTokens(it.toLong()) },
        )
        SliderRow(
            label = "threads",
            value = tuning.threads.toFloat(),
            range = 1f..8f,
            steps = 6,
            onValue = onThreads,
            format = { "${it.toInt()}" },
        )
        SliderRow(
            label = "batch (n_batch)",
            value = tuning.batch.toFloat(),
            range = 32f..2048f,
            steps = 15,
            onValue = onBatch,
            format = { it.toInt().toString() },
        )
        if (engineAvailable) {
            SliderRow(
                label = "gpu layers (offload)",
                value = tuning.gpuLayers.toFloat(),
                range = 0f..99f,
                steps = 98,
                onValue = onGpuLayers,
                format = { if (it.toInt() == 0) "cpu only" else "${it.toInt()} layers" },
            )
        }

        Spacer(Modifier.height(spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "kv cache quantization",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            KvTypes.forEach { type ->
                InkChip(text = type, selected = tuning.kvType == type, onClick = { onKvType(type) })
                Spacer(Modifier.width(4.dp))
            }
        }
        Spacer(Modifier.height(spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "mmap weights",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface,
                )
                Text(
                    text = "Pages in from storage instead of copying the file into RAM.",
                    style = MetaKeyText,
                    color = colors.tertiary,
                )
            }
            Switch(
                checked = tuning.useMmap,
                onCheckedChange = onMmap,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onPrimary,
                    checkedTrackColor = colors.primary,
                    uncheckedThumbColor = colors.surface,
                    uncheckedTrackColor = colors.surfaceContainerHigh,
                    uncheckedBorderColor = colors.outline,
                ),
            )
        }

        assessment?.let {
            Spacer(Modifier.height(spacing.sm))
            StatChip(
                label = "with these settings",
                value = "peak ${Formats.bytes(it.peakRamBytes)} · ${(it.pressure * 100).roundToInt()}%",
                emphasized = true,
            )
        }

        Spacer(Modifier.height(spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            InkButton(label = "Use suggested", onClick = onSuggested, filled = false, icon = R.drawable.ic_target)
            InkButton(label = "Save", onClick = onSave, icon = R.drawable.ic_check)
        }
    }
}

@Composable
private fun SamplingCard(
    tuning: ModelTuning,
    onTemperature: (Float) -> Unit,
    onTopP: (Float) -> Unit,
    onTopK: (Int) -> Unit,
    onMinP: (Float) -> Unit,
    onRepeat: (Float) -> Unit,
    onMaxTokens: (Int) -> Unit,
    onSave: () -> Unit,
) {
    val spacing = OfflineGgufTheme.spacing
    Section(title = "Sampling") {
        SliderRow("temperature", tuning.temperature, 0f..2f, onValue = onTemperature, format = { Formats.fixed(it.toDouble(), 2) })
        SliderRow("top-p", tuning.topP, 0.1f..1f, onValue = onTopP, format = { Formats.fixed(it.toDouble(), 2) })
        SliderRow("top-k", tuning.topK.toFloat(), 0f..200f, steps = 40, onValue = { onTopK(it.toInt()) }, format = { it.toInt().toString() })
        SliderRow("min-p", tuning.minP, 0f..0.5f, onValue = onMinP, format = { Formats.fixed(it.toDouble(), 3) })
        SliderRow(
            "repeat penalty",
            tuning.repeatPenalty,
            0.8f..2f,
            onValue = onRepeat,
            format = { Formats.fixed(it.toDouble(), 2) },
        )
        SliderRow(
            "max tokens",
            tuning.maxTokens.toFloat(),
            16f..4096f,
            steps = 63,
            onValue = { onMaxTokens(it.toInt()) },
            format = { it.toInt().toString() },
        )
        Spacer(Modifier.height(spacing.sm))
        InkButton(label = "Save sampling", onClick = onSave, icon = R.drawable.ic_check)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    format: (Float) -> String = { it.toInt().toString() },
) {
    val colors = MaterialTheme.colorScheme
    var draft by remember(label) { mutableStateOf(value) }
    // Adopt external changes (e.g. "use suggested") once the user lets go.
    LaunchedEffect(value) {
        draft = value
    }

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
                color = colors.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = draft.coerceIn(range.start, range.endInclusive),
            onValueChange = { draft = it },
            onValueChangeFinished = { onValue(draft) },
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

/** Card shell used by every section on this screen. */
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
private fun ExpandableSection(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(spacing.card))
            .clickable(onClick = onToggle)
            .padding(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(text = title)
            Spacer(Modifier.width(spacing.sm))
            Text(text = subtitle, style = MetaKeyText, color = colors.tertiary)
            Spacer(Modifier.weight(1f))
            Icon(
                painter = painterResource(
                    if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right,
                ),
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(spacing.sm))
            content()
        }
    }
}
