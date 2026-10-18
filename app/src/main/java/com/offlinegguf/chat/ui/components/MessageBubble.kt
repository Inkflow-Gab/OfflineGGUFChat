package com.offlinegguf.chat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.offlinegguf.chat.data.local.entity.Message
import com.offlinegguf.chat.ui.theme.codeFontFamily
import kotlinx.coroutines.flow.first

@Composable
fun MessageBubble(message: Message) {
    val isUser = message.role == "user"
    val isStreaming = message.isStreaming == 1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .widthIn(maxWidth = 700.dp)
                    .background(
                        if (isUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium.copy(
                            if (isUser) 12.dp else 12.dp,
                            if (isUser) 4.dp else 12.dp,
                            if (isUser) 12.dp else 12.dp,
                            if (isUser) 12.dp else 4.dp
                        )
                    )
                    .padding(12.dp)
                    .clickable { /* copy */ }
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Render message with basic markdown + code block support
                    RichMessage(
                        text = message.content + (if (isStreaming) " …" else ""),
                        isUser = isUser
                    )

                    // Thinking / reasoning trace (assistant only)
                    if (!isUser && message.thinking.isNotNull() && message.thinking.isNotEmpty()) {
                        ThinkingSection(thinking = message.thinking!!)
                    }
                }
            }
        }

        // Meta row: role badge + token count + time
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .align(if (isUser) Alignment.End else Alignment.Start)
        ) {
            if (!isUser) {
                Text(
                    text = "${message.tokenCount} tok",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Text(
                text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(message.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Action chips (copy, regenerate, etc.)
        if (!isUser) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .align(Alignment.End)
            ) {
                IconButton(onClick = { /* copy */ }) {
                    Icon(androidx.compose.material3.Icons.Default.ContentCopy, contentDescription = "Copy")
                }
                IconButton(onClick = { /* regenerate */ }) {
                    Icon(androidx.compose.material3.Icons.Default.Repeat, contentDescription = "Regenerate")
                }
            }
        }
    }
}

/**
 * Renders a message with basic markdown:
 * - **bold**, *italic*
 * - `inline code`
 * - ```fenced code blocks``` with syntax highlighting
 */
@Composable
private fun RichMessage(text: String, isUser: Boolean) {
    val codeColor = if (isUser) Color(0xFF000000) else MaterialTheme.colorScheme.onSurfaceVariant
    val baseStyle = MaterialTheme.typography.bodyMedium.copy(color = codeColor)

    // Split by fenced code blocks first
    val parts = splitCodeBlocks(text)

    Column(modifier = Modifier.fillMaxWidth()) {
        parts.forEachIndexed { index, part ->
            if (part.isCodeBlock) {
                CodeBlockDisplay(
                    code = part.content,
                    language = part.language
                )
            } else {
                // Inline markdown: **bold**, *italic*, `code`
                InlineMarkdown(
                    text = part.content,
                    baseStyle = baseStyle
                )
            }
            if (index < parts.size - 1) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/** Represents either plain text or a fenced code block. */
private data class MessagePart(
    val isCodeBlock: Boolean,
    val content: String,
    val language: String? = null
)

/**
 * Splits text by ```language\ncode\n``` fences.
 * Returns a list of MessagePart.
 */
private fun splitCodeBlocks(text: String): List<MessagePart> {
    val result = mutableListOf<MessagePart>()
    val fenceRegex = """```(\w*)\n(.*?)```""".toRegex(RegexOption.DOTALL)

    var lastEnd = 0
    fenceRegex.findAll(text).forEach { match ->
        val start = match.range.first
        val end = match.range.lastInclusive
        // Plain text before the fence
        if (start > lastEnd) {
            result.add(MessagePart(false, text.substring(lastEnd, start)))
        }
        val language = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: null
        val code = match.groupValues.getOrNull(2) ?: ""
        result.add(MessagePart(true, code, language))
        lastEnd = end + 1
    }
    if (lastEnd < text.length) {
        result.add(MessagePart(false, text.substring(lastEnd)))
    }
    return if (result.isEmpty()) listOf(MessagePart(false, text)) else result
}

@Composable
private fun CodeBlockDisplay(code: String, language: String? = null) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            language?.let { lang ->
                Text(
                    text = lang,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = androidx.compose.ui.text.FontWeight.Bold
                    ),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            SyntaxHighlighter.HighlightedCode(
                code = code,
                language = language,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Simple inline markdown: **bold**, *italic*, `code`.
 */
@Composable
private fun InlineMarkdown(text: String, baseStyle: TextStyle) {
    val segments = mutableListOf<Pair<String, SpanStyle>>()
    var remaining = text

    while (remaining.isNotEmpty()) {
        val boldIdx = remaining.indexOf("**")
        val italicIdx = remaining.indexOf('*')
        val codeIdx = remaining.indexOf('`')

        val candidates = mutableListOf<Int>()
        if (boldIdx >= 0) candidates.add(boldIdx)
        if (italicIdx >= 0) candidates.add(italicIdx)
        if (codeIdx >= 0) candidates.add(codeIdx)

        if (candidates.isEmpty()) {
            segments.add(remaining to SpanStyle())
            break
        }

        val next = candidates.minOrNull()!!
        val marker = when {
            next == boldIdx -> "**"
            next == codeIdx -> "`"
            else -> "*"
        }

        // Text before marker
        if (next > 0) {
            segments.add(remaining.substring(0, next) to SpanStyle())
        }

        // Consume marker pair
        val afterMarker = remaining.substring(next + marker.length)
        val closeIdx = afterMarker.indexOf(marker)
        if (closeIdx >= 0) {
            val inner = afterMarker.substring(0, closeIdx)
            val style = when (marker) {
                "**" -> SpanStyle(fontWeight = androidx.compose.ui.text.FontWeight.Bold)
                "*" -> SpanStyle(androidx.compose.ui.text.TextStyle(fontStyle = androidx.compose.ui.text.FontStyle.Italic))
                "`" -> SpanStyle(
                    color = Color(0xFFCE9178),
                    fontFamily = FontFamily(Font("monospace")),
                    background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
                else -> SpanStyle()
            }
            segments.add(inner to style)
            remaining = afterMarker.substring(closeIdx + marker.length)
        } else {
            // Unmatched marker — append literally
            segments.add(remaining.substring(next) to SpanStyle())
            remaining = ""
        }
    }

    // Build a single AnnotatedString with all styles
    val annotated = buildAnnotatedString {
        segments.forEach { (segment, style) ->
            val merged = baseStyle.copy(
                color = style.color ?: baseStyle.color,
                fontWeight = style.fontWeight ?: baseStyle.fontWeight,
                fontFamily = style.fontFamily ?: baseStyle.fontFamily,
                background = style.background ?: baseStyle.background,
                fontStyle = style.fontStyle ?: baseStyle.fontStyle
            )
            withStyle(merged) {
                append(segment)
            }
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun ThinkingSection(thinking: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = androidx.compose.material3.Icons.Default.Psychology,
                    contentDescription = "Thinking",
                    modifier = Modifier.size(16.dp).padding(end = 4.dp)
                )
                Text(
                    text = "Thinking",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = androidx.compose.ui.text.FontWeight.Bold
                    )
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = thinking,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    fontFamily = codeFontFamily
                )
            )
        }
    }
}

private val ColorOnContainer = Color(0xFF000000)
