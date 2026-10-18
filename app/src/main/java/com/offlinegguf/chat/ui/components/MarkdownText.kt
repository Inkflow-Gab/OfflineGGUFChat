package com.offlinegguf.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntrinsicSize
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.offlinegguf.chat.ui.theme.ChatProse
import com.offlinegguf.chat.ui.theme.Code
import com.offlinegguf.chat.ui.theme.CodeText
import com.offlinegguf.chat.ui.theme.MetaKeyText
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme
import com.offlinegguf.chat.util.MarkdownBlocks

/**
 * Renders model output.
 *
 * Blocks come from [MarkdownBlocks]; inline formatting becomes an
 * [AnnotatedString] so a paragraph is still a single Text node (one layout pass,
 * no nested composables per word — which matters when the transcript is long and
 * tokens are still arriving).
 *
 * [previousCode] maps a code block index to the earlier revision of that snippet,
 * which is what turns on the added/removed line view in [CodeBlock].
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = ChatProse,
    previousCode: Map<Int, String> = emptyMap(),
    showLineNumbers: Boolean = true,
) {
    val blocks = remember(text) { MarkdownBlocks.parse(text) }
    val spacing = OfflineGgufTheme.spacing
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        var codeIndex = -1
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlocks.Block.Paragraph -> Text(
                    text = inline(block.text, colors),
                    style = style,
                    color = colors.onSurface,
                )

                is MarkdownBlocks.Block.Heading -> {
                    val headingStyle = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        text = inline(block.text, colors),
                        style = headingStyle,
                        color = colors.onSurface,
                        modifier = Modifier.padding(top = if (block.level <= 2) spacing.xs else 0.dp),
                    )
                }

                is MarkdownBlocks.Block.Code -> {
                    codeIndex++
                    CodeBlock(
                        content = block.content,
                        language = block.language,
                        previous = previousCode[codeIndex],
                        showLineNumbers = showLineNumbers,
                        terminated = block.terminated,
                    )
                }

                is MarkdownBlocks.Block.BulletList -> Column(
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    block.items.forEachIndexed { index, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            Text(
                                text = if (block.ordered) "${index + 1}." else "—",
                                style = style,
                                color = colors.tertiary,
                            )
                            Text(
                                text = inline(item, colors),
                                style = style,
                                color = colors.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is MarkdownBlocks.Block.Quote -> Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(colors.outline, RoundedCornerShape(1.dp)),
                    )
                    Text(
                        text = inline(block.text, colors),
                        style = style.copy(fontStyle = FontStyle.Italic),
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = spacing.md).weight(1f),
                    )
                }

                is MarkdownBlocks.Block.Table -> TableBlock(block)

                MarkdownBlocks.Block.Rule -> Hairline()
            }
        }
    }
}

@Composable
private fun TableBlock(table: MarkdownBlocks.Block.Table) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(spacing.chip))
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            table.header.forEach { cell ->
                Text(
                    text = cell,
                    style = MetaKeyText,
                    color = colors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Hairline()
        table.rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                table.header.indices.forEach { index ->
                    Text(
                        text = row.getOrElse(index) { "" },
                        style = MetaKeyText,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Inline spans → one AnnotatedString. */
@Composable
private fun inline(text: String, colors: ColorScheme): AnnotatedString {
    val spans = remember(text) { MarkdownBlocks.inline(text) }
    val codeBackground = colors.surfaceContainerHigh
    val muted = colors.tertiary
    return buildAnnotatedString {
        spans.forEach { span ->
            when (span) {
                is MarkdownBlocks.Span.Text -> append(span.value)
                is MarkdownBlocks.Span.Bold -> withStyle(
                    SpanStyle(fontWeight = FontWeight.SemiBold),
                ) { append(span.value) }

                is MarkdownBlocks.Span.Italic -> withStyle(
                    SpanStyle(fontStyle = FontStyle.Italic),
                ) { append(span.value) }

                is MarkdownBlocks.Span.InlineCode -> withStyle(
                    SpanStyle(
                        fontFamily = Code,
                        background = codeBackground,
                        color = colors.onSurface,
                    ),
                ) { append(span.value) }

                is MarkdownBlocks.Span.Link -> withStyle(
                    SpanStyle(
                        textDecoration = TextDecoration.Underline,
                        color = muted,
                        fontWeight = FontWeight.Medium,
                    ),
                ) { append(span.text) }
            }
        }
    }
}

/** Plain-text fallback for list previews and notifications. */
fun plainText(markdown: String): String {
    val blocks = MarkdownBlocks.parse(markdown)
    return blocks.joinToString("\n") { block ->
        when (block) {
            is MarkdownBlocks.Block.Paragraph -> block.text
            is MarkdownBlocks.Block.Heading -> block.text
            is MarkdownBlocks.Block.Code -> block.content
            is MarkdownBlocks.Block.BulletList -> block.items.joinToString("\n") { "• $it" }
            is MarkdownBlocks.Block.Quote -> block.text
            is MarkdownBlocks.Block.Table ->
                (listOf(block.header) + block.rows).joinToString("\n") { it.joinToString(" | ") }
            MarkdownBlocks.Block.Rule -> ""
        }
    }
}

/** Convenience for callers that only need the code blocks of a message. */
fun codeSnippets(markdown: String): List<Pair<String, String>> =
    MarkdownBlocks.codeBlocks(markdown).map { it.language to it.content }

/** Text style used for inline code inside prose. */
val InlineCodeStyle: TextStyle = CodeText
