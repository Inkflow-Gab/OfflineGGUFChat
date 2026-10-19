package com.offlinegguf.chat.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlinegguf.chat.R
import com.offlinegguf.chat.ui.theme.CodeGutterText
import com.offlinegguf.chat.ui.theme.CodeText
import com.offlinegguf.chat.ui.theme.DiffPalette
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme
import com.offlinegguf.chat.util.LineDiffer
import com.offlinegguf.chat.util.UnifiedDiff
import kotlinx.coroutines.delay

/** Lines past this are collapsed behind "show all", so a 900-line dump cannot stall a scroll. */
private const val MAX_VISIBLE_LINES = 50

private sealed class CodeRow {
    data class Plain(val number: Int, val text: String) : CodeRow()
    data class Changed(
        val kind: LineDiffer.Kind,
        val oldNumber: Int,
        val newNumber: Int,
        val text: String,
    ) : CodeRow()

    data class Skipped(val count: Int) : CodeRow()
}

/**
 * A fenced code block: monospace, line numbers, copy, and — when the app knows a
 * previous revision of the same snippet — a switch to an added/removed line view.
 *
 * It also understands a unified diff written by the model itself (the Patch mode
 * skill), so a "here is the change" answer renders as coloured rows rather than a
 * wall of `+` and `-` characters.
 */
@Composable
fun CodeBlock(
    content: String,
    modifier: Modifier = Modifier,
    language: String = "",
    filename: String? = null,
    previous: String? = null,
    showLineNumbers: Boolean = true,
    terminated: Boolean = true,
    /** Suppresses the line diff while the block is still growing. */
    streaming: Boolean = false,
    onSaveCode: (content: String, fileName: String) -> Unit = { _, _ -> },
    /** Present for web snippets: runs them in the sandbox. */
    onRunCode: ((content: String, fileName: String, language: String) -> Unit)? = null,
) {
    val palette = OfflineGgufTheme.diff
    val spacing = OfflineGgufTheme.spacing
    val motion = OfflineGgufTheme.motion
    val colors = MaterialTheme.colorScheme

    val isDiffOutput = language == "diff" || language == "patch" || UnifiedDiff.looksLikeDiff(content)
    val unified = remember(content, isDiffOutput) {
        if (isDiffOutput) UnifiedDiff.parse(content) else null
    }
    // The line diff is O(revision size) per update; while tokens are still
    // arriving it is recomputed on every flush, so it waits for the final text.
    val computed = remember(content, previous, streaming) {
        if (previous != null && !isDiffOutput && !streaming) LineDiffer.diff(previous, content) else null
    }
    var showChanges by remember(content, previous) { mutableStateOf(false) }
    var expanded by remember(content) { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }

    val rows = remember(content, unified, computed, showChanges) {
        buildRows(content, unified, if (showChanges) computed else null)
    }
    val totalLines = rows.count { it !is CodeRow.Skipped }
    val visible = if (expanded || totalLines <= MAX_VISIBLE_LINES) rows else rows.take(MAX_VISIBLE_LINES)

    val summary = when {
        showChanges && computed != null -> computed.summary
        unified != null -> unified.summary
        else -> null
    }

    Surface(
        // "Show all N lines" / "Collapse" and the reveal of a diff both change the
        // height of this block, so the height eases instead of jumping.
        modifier = modifier.fillMaxWidth().animateContentSize(tween(motion.standard)),
        shape = RoundedCornerShape(spacing.card),
        color = colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column {
            // ---- header ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = filename ?: language.ifBlank { if (isDiffOutput) "diff" else "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (filename != null && language.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(language, style = MaterialTheme.typography.labelSmall, color = colors.tertiary)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$totalLines lines",
                    style = CodeGutterText,
                    color = colors.onSurfaceVariant,
                )
                if (summary != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = summary,
                        style = CodeGutterText,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (computed != null) {
                    TextButton(onClick = { showChanges = !showChanges }) {
                        Text(
                            text = if (showChanges) "Whole file" else "Changes",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                val runnable = onRunCode != null && !isDiffOutput &&
                    com.offlinegguf.chat.data.workspace.SandboxDocument.isWebLanguage(language)
                if (runnable && onRunCode != null) {
                    IconButton(
                        onClick = {
                            val safeName = filename?.substringAfterLast('/')?.substringAfterLast('\\')
                                ?.takeIf { it.isNotBlank() }
                                ?: com.offlinegguf.chat.data.workspace.SandboxDocument.fileNameFor(language, null)
                            onRunCode(content, safeName, language)
                        },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_play),
                            contentDescription = "Run in sandbox",
                            modifier = Modifier.size(15.dp),
                            tint = colors.onSurface,
                        )
                    }
                }
                IconButton(
                    onClick = {
                        val safeName = filename?.substringAfterLast('/')?.substringAfterLast('\\')
                            ?.takeIf { it.isNotBlank() }
                        onSaveCode(content, safeName ?: "snippet.${fileExtension(language)}")
                    },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = "Save ${filename ?: language.ifBlank { "code" }} to a file",
                        modifier = Modifier.size(15.dp),
                        tint = colors.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(content))
                        copied = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        painter = painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_copy),
                        contentDescription = if (copied) "Copied" else "Copy code",
                        modifier = Modifier.size(15.dp),
                        tint = if (copied) colors.onSurface else colors.onSurfaceVariant,
                    )
                }
            }
            Hairline()

            // ---- body ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
            ) {
                visible.forEach { row ->
                    CodeRowView(
                        row = row,
                        showLineNumbers = showLineNumbers,
                        palette = palette,
                        textColor = colors.onSurface,
                        gutterColor = palette.gutter,
                    )
                }
                if (!terminated) {
                    // A stream that ends mid-block still shows the caret line.
                    Row(Modifier.padding(start = 10.dp, top = 2.dp)) {
                        Text("…", style = CodeText, color = colors.tertiary)
                    }
                }
            }

            if (totalLines > MAX_VISIBLE_LINES) {
                Hairline()
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (expanded) "Collapse" else "Show all $totalLines lines",
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun fileExtension(language: String): String = when (language.lowercase()) {
    "python" -> "py"
    "kotlin" -> "kt"
    "javascript" -> "js"
    "typescript" -> "ts"
    "java" -> "java"
    "c", "cpp" -> "cpp"
    "csharp" -> "cs"
    "rust" -> "rs"
    "go" -> "go"
    "json" -> "json"
    "yaml" -> "yaml"
    "html" -> "html"
    "css" -> "css"
    "bash" -> "sh"
    "diff", "patch" -> "diff"
    else -> "txt"
}

@Composable
private fun CodeRowView(
    row: CodeRow,
    showLineNumbers: Boolean,
    palette: DiffPalette,
    textColor: Color,
    gutterColor: Color,
) {
    when (row) {
        is CodeRow.Skipped -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 1.dp),
        ) {
            Text(
                text = "⋯ ${row.count} unchanged",
                style = CodeGutterText,
                color = gutterColor,
            )
        }

        is CodeRow.Plain -> Row(modifier = Modifier.fillMaxWidth()) {
            if (showLineNumbers) {
                Gutter(row.number.toString(), gutterColor)
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = row.text.ifEmpty { " " },
                style = CodeText,
                color = textColor,
                softWrap = false,
                modifier = Modifier.padding(end = 12.dp),
            )
        }

        is CodeRow.Changed -> {
            val background = when (row.kind) {
                LineDiffer.Kind.ADDED -> palette.added
                LineDiffer.Kind.REMOVED -> palette.removed
                LineDiffer.Kind.CONTEXT -> Color.Transparent
            }
            val mark = when (row.kind) {
                LineDiffer.Kind.ADDED -> palette.addedMark.toString()
                LineDiffer.Kind.REMOVED -> palette.removedMark.toString()
                LineDiffer.Kind.CONTEXT -> " "
            }
            Row(
                modifier = Modifier.fillMaxWidth().background(background),
            ) {
                Text(
                    text = mark,
                    style = CodeText,
                    color = gutterColor,
                    softWrap = false,
                    modifier = Modifier.width(16.dp).padding(start = 4.dp),
                )
                if (showLineNumbers) {
                    Gutter(if (row.oldNumber > 0) row.oldNumber.toString() else "", gutterColor)
                    Gutter(if (row.newNumber > 0) row.newNumber.toString() else "", gutterColor)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = row.text.ifEmpty { " " },
                    style = CodeText,
                    color = textColor,
                    softWrap = false,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Gutter(value: String, color: Color) {
    Text(
        text = value,
        style = CodeGutterText,
        color = color,
        softWrap = false,
        textAlign = TextAlign.End,
        modifier = Modifier.width(30.dp).padding(end = 4.dp),
    )
}

private fun buildRows(
    content: String,
    unified: UnifiedDiff.Parsed?,
    computed: LineDiffer.Result?,
): List<CodeRow> {
    if (computed != null) {
        return computed.rows.map { row ->
            when (row) {
                is LineDiffer.Row.Line -> CodeRow.Changed(row.kind, row.oldNumber, row.newNumber, row.text)
                is LineDiffer.Row.Gap -> CodeRow.Skipped(row.count)
            }
        }
    }
    if (unified != null && unified.valid) {
        return unified.entries.map { entry ->
            CodeRow.Changed(entry.kind, entry.oldNumber, entry.newNumber, entry.text)
        }
    }
    val lines = if (content.endsWith("\n")) content.dropLast(1) else content
    if (lines.isEmpty()) return listOf(CodeRow.Plain(1, ""))
    return lines.split('\n').mapIndexed { index, text -> CodeRow.Plain(index + 1, text) }
}

/**
 * Legend for the diff view, shown above a patch so the monochrome tints are
 * unambiguous.
 */
@Composable
fun DiffLegend(modifier: Modifier = Modifier) {
    val palette = OfflineGgufTheme.diff
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Swatch(palette.added)
        Text("${palette.addedMark} added", style = CodeGutterText, color = colors.tertiary)
        Swatch(palette.removed)
        Text("${palette.removedMark} removed", style = CodeGutterText, color = colors.tertiary)
        Box(Modifier.size(10.dp))
        Text("unchanged", style = CodeGutterText, color = colors.tertiary)
    }
}
