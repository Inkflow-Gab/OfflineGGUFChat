package com.offlinegguf.chat.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Simple offline syntax highlighter for code blocks.
 * No external dependencies — runs entirely on-device.
 * Highlights keywords, strings, comments, numbers, and functions.
 */
object SyntaxHighlighter {

    private val keywords = setOf(
        "fn", "def", "class", "if", "else", "elif", "for", "while", "return",
        "let", "var", "const", "val", "true", "false", "null", "void", "int",
        "float", "double", "string", "bool", "char", "import", "from", "as",
        "async", "await", "try", "catch", "finally", "throw", "new", "this",
        "super", "extends", "implements", "interface", "enum", "struct",
        "match", "case", "pub", "private", "protected", "static", "final",
        "abstract", "volatile", "synchronized", "native", "strictfp", "transient",
        "sizeof", "typedef", "unsigned", "signed", "long", "short", "byte"
    )

    private fun isIdentifierStart(c: Char): Boolean = c.isLetter() || c == '_'
    private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    @Composable
    fun HighlightedCode(
        code: String,
        language: String? = null,
        modifier: Modifier = Modifier
    ) {
        Text(
            text = buildAnnotatedString {
                appendStyled(code)
            },
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = SyntaxHighlighter.codeFontFamily
            ),
            modifier = modifier.fillMaxWidth()
        )
    }

    private fun buildAnnotatedString.appendStyled(code: String) {
        var i = 0
        while (i < code.length) {
            val c = code[i]

            // Multi-line comment /* ... */
            if (i + 1 < code.length && c == '/' && code[i + 1] == '*') {
                val end = code.indexOf("*/", i + 2)
                val endIdx = if (end == -1) code.length else end + 2
                withStyle(SpanStyle(color = Color(0xFF6A9955), textDecoration = TextDecoration.NoUnderline)) {
                    append(code.substring(i, endIdx))
                }
                i = endIdx
                continue
            }

            // Single-line comment // ...
            if (i + 1 < code.length && c == '/' && code[i + 1] == '/') {
                val end = code.indexOf('\n', i)
                val endIdx = if (end == -1) code.length else end
                withStyle(SpanStyle(color = Color(0xFF6A9955), textDecoration = TextDecoration.NoUnderline)) {
                    append(code.substring(i, endIdx))
                }
                i = endIdx
                continue
            }

            // String "..." or '...'
            if (c == '"' || c == '\'') {
                val quote = c
                var j = i + 1
                while (j < code.length && code[j] != quote) {
                    if (code[j] == '\\' && j + 1 < code.length) j++ // skip escaped char
                    j++
                }
                val endIdx = if (j < code.length) j + 1 else code.length
                withStyle(SpanStyle(color = Color(0xFFCE9178), textDecoration = TextDecoration.NoUnderline)) {
                    append(code.substring(i, endIdx))
                }
                i = endIdx
                continue
            }

            // Number
            if (c.isDigit() || (c == '.' && i + 1 < code.length && code[i + 1].isDigit())) {
                var j = i
                while (j < code.length && (code[j].isDigit() || code[j] == '.' || code[j] == 'x' || code[j] == 'X')) j++
                withStyle(SpanStyle(color = Color(0xFFB5CEA8), textDecoration = TextDecoration.NoUnderline)) {
                    append(code.substring(i, j))
                }
                i = j
                continue
            }

            // Keyword / identifier
            if (isIdentifierStart(c)) {
                var j = i
                while (j < code.length && isIdentifierPart(code[j])) j++
                val word = code.substring(i, j)
                if (keywords.contains(word)) {
                    withStyle(SpanStyle(color = Color(0xFF569CD6), textDecoration = TextDecoration.NoUnderline)) {
                        append(word)
                    }
                } else {
                    append(word)
                }
                i = j
                continue
            }

            // Operator / punctuation
            if ("+-*/%=<>!&|^~?:.,;()[]{}".contains(c)) {
                withStyle(SpanStyle(color = Color(0xFFD4D4D4), textDecoration = TextDecoration.NoUnderline)) {
                    append(c)
                }
                i++
                continue
            }

            // Whitespace / other
            append(c)
            i++
        }
    }

    // Monospace font family — falls back to system monospace
    val codeFontFamily = androidx.compose.ui.text.FontFamily(
        androidx.compose.ui.text.font.Font("monospace")
    )
}
