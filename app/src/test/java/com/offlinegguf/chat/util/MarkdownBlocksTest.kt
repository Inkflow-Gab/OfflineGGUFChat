package com.offlinegguf.chat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {

    private fun code(block: MarkdownBlocks.Block) = block as MarkdownBlocks.Block.Code
    private fun paragraph(block: MarkdownBlocks.Block) = block as MarkdownBlocks.Block.Paragraph
    private fun heading(block: MarkdownBlocks.Block) = block as MarkdownBlocks.Block.Heading
    private fun list(block: MarkdownBlocks.Block) = block as MarkdownBlocks.Block.BulletList
    private fun quote(block: MarkdownBlocks.Block) = block as MarkdownBlocks.Block.Quote
    private fun table(block: MarkdownBlocks.Block) = block as MarkdownBlocks.Block.Table

    @Test
    fun `empty input produces no blocks`() {
        assertTrue(MarkdownBlocks.parse("").isEmpty())
        assertTrue(MarkdownBlocks.inline("").isEmpty())
    }

    @Test
    fun `headings keep their level and drop the hashes`() {
        val blocks = MarkdownBlocks.parse("# Title\n\n## Sub\n\nbody")
        assertEquals(3, blocks.size)
        assertEquals(1, heading(blocks[0]).level)
        assertEquals("Title", heading(blocks[0]).text)
        assertEquals(2, heading(blocks[1]).level)
        assertEquals("Sub", heading(blocks[1]).text)
        assertEquals("body", paragraph(blocks[2]).text)
    }

    @Test
    fun `a hash without a space is a paragraph, not a heading`() {
        val blocks = MarkdownBlocks.parse("#hashtag")
        assertEquals(1, blocks.size)
        assertEquals("#hashtag", paragraph(blocks[0]).text)
    }

    @Test
    fun `seven hashes is not a heading level`() {
        val blocks = MarkdownBlocks.parse("####### too deep")
        assertTrue(blocks[0] is MarkdownBlocks.Block.Paragraph)
    }

    @Test
    fun `consecutive lines merge into one paragraph`() {
        val blocks = MarkdownBlocks.parse("line one\nline two")
        assertEquals(1, blocks.size)
        assertEquals("line one\nline two", paragraph(blocks[0]).text)
    }

    @Test
    fun `a fenced block reports its language, body and whether it closed`() {
        val blocks = MarkdownBlocks.parse("```kotlin\nval x = 1\nval y = 2\n```")
        assertEquals(1, blocks.size)
        val block = code(blocks[0])
        assertEquals("kotlin", block.language)
        assertEquals("val x = 1\nval y = 2", block.content)
        assertTrue(block.terminated)
        assertEquals(2, block.lineCount)
    }

    @Test
    fun `fence metadata captures a filename and keeps language separate`() {
        val positional = code(MarkdownBlocks.parse("```python app/main.py\nprint('hi')\n```").single())
        assertEquals("python", positional.language)
        assertEquals("app/main.py", positional.filename)
        assertEquals(1, positional.lineCount)

        val named = code(MarkdownBlocks.parse("```kotlin title=\"Main Screen.kt\"\nfun main() {}\n```").single())
        assertEquals("kotlin", named.language)
        assertEquals("Main Screen.kt", named.filename)

        val compact = code(MarkdownBlocks.parse("```js:src/app.js\nrun()\n```").single())
        assertEquals("javascript", compact.language)
        assertEquals("src/app.js", compact.filename)
    }

    @Test
    fun `an unterminated fence still renders as code while streaming`() {
        val blocks = MarkdownBlocks.parse("```py\nprint(1)")
        val block = code(blocks[0])
        assertEquals("python", block.language)
        assertEquals("print(1)", block.content)
        assertFalse(block.terminated)
    }

    @Test
    fun `language aliases are normalised`() {
        fun languageOf(fence: String) = code(MarkdownBlocks.parse("```$fence\nx\n```").single()).language
        assertEquals("kotlin", languageOf("KT"))
        assertEquals("kotlin", languageOf("Kotlin"))
        assertEquals("javascript", languageOf("js"))
        assertEquals("typescript", languageOf("ts"))
        assertEquals("python", languageOf("py"))
        assertEquals("bash", languageOf("sh"))
        assertEquals("bash", languageOf("shell"))
        assertEquals("markdown", languageOf("md"))
        assertEquals("yaml", languageOf("yml"))
        assertEquals("cpp", languageOf("c++"))
        assertEquals("csharp", languageOf("cs"))
        assertEquals("rust", languageOf("rs"))
        assertEquals("go", languageOf("golang"))
        assertEquals("json", languageOf("JSON5"))
        assertEquals("diff", languageOf("patch"))
        assertEquals("", languageOf(""))
        assertEquals("terraform", languageOf("terraform"))
    }

    @Test
    fun `tilde fences work too`() {
        val block = code(MarkdownBlocks.parse("~~~\ncode\n~~~").single())
        assertEquals("", block.language)
        assertEquals("code", block.content)
        assertTrue(block.terminated)
    }

    @Test
    fun `a fence closes a paragraph before it opens`() {
        val blocks = MarkdownBlocks.parse("text before\n```\ncode\n```")
        assertEquals(2, blocks.size)
        assertEquals("text before", paragraph(blocks[0]).text)
        assertEquals("code", code(blocks[1]).content)
    }

    @Test
    fun `lists keep their items and know whether they are ordered`() {
        val bullets = list(MarkdownBlocks.parse("- a\n- b\n- c").single())
        assertEquals(listOf("a", "b", "c"), bullets.items)
        assertFalse(bullets.ordered)

        val ordered = list(MarkdownBlocks.parse("1. first\n2) second").single())
        assertEquals(listOf("first", "second"), ordered.items)
        assertTrue(ordered.ordered)

        // A blank line does not split a list when the next line continues it.
        val spaced = list(MarkdownBlocks.parse("- a\n\n- b").single())
        assertEquals(listOf("a", "b"), spaced.items)
    }

    @Test
    fun `quotes join their lines and drop the markers`() {
        val block = quote(MarkdownBlocks.parse("> hello\n> world").single())
        assertEquals("hello\nworld", block.text)
    }

    @Test
    fun `rules are recognised in all three spellings`() {
        for (rule in listOf("---", "***", "___", "- - -")) {
            val blocks = MarkdownBlocks.parse(rule)
            assertEquals("for $rule", 1, blocks.size)
            assertTrue("for $rule", blocks[0] is MarkdownBlocks.Block.Rule)
        }
    }

    @Test
    fun `a table needs a separator row`() {
        val blocks = MarkdownBlocks.parse("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |")
        val block = table(blocks.single())
        assertEquals(listOf("a", "b"), block.header)
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), block.rows)

        // Without the separator it is just a paragraph that happens to have pipes.
        val notATable = MarkdownBlocks.parse("| a | b |\n| c | d |")
        assertTrue(notATable.single() is MarkdownBlocks.Block.Paragraph)
    }

    @Test
    fun `codeBlocks only returns fences, in order`() {
        val text = "intro\n\n```kotlin\nval a = 1\n```\n\nmiddle\n\n```diff\n-x\n+y\n```\n"
        val blocks = MarkdownBlocks.codeBlocks(text)
        assertEquals(2, blocks.size)
        assertEquals("kotlin", blocks[0].language)
        assertEquals("diff", blocks[1].language)
        assertEquals("-x\n+y", blocks[1].content)
    }

    // --------------------------------------------------------------- inline

    @Test
    fun `inline spans come out in reading order`() {
        assertEquals(
            listOf(
                MarkdownBlocks.Span.Text("a "),
                MarkdownBlocks.Span.Bold("b"),
                MarkdownBlocks.Span.Text(" c"),
            ),
            MarkdownBlocks.inline("a **b** c"),
        )
        assertEquals(
            listOf(MarkdownBlocks.Span.Bold("b")),
            MarkdownBlocks.inline("__b__"),
        )
        assertEquals(
            listOf(
                MarkdownBlocks.Span.Text("a "),
                MarkdownBlocks.Span.InlineCode("b"),
                MarkdownBlocks.Span.Text(" "),
                MarkdownBlocks.Span.Bold("c"),
                MarkdownBlocks.Span.Text(" "),
                MarkdownBlocks.Span.Italic("d"),
            ),
            MarkdownBlocks.inline("a `b` **c** *d*"),
        )
    }

    @Test
    fun `links carry their text and url`() {
        assertEquals(
            listOf(
                MarkdownBlocks.Span.Text("see "),
                MarkdownBlocks.Span.Link("the docs", "https://example.com/x"),
            ),
            MarkdownBlocks.inline("see [the docs](https://example.com/x)"),
        )
    }

    @Test
    fun `unmatched markers stay literal`() {
        assertEquals(
            listOf(MarkdownBlocks.Span.Text("unmatched * star")),
            MarkdownBlocks.inline("unmatched * star"),
        )
        assertEquals(
            listOf(MarkdownBlocks.Span.Text("half ` code")),
            MarkdownBlocks.inline("half ` code"),
        )
        assertEquals(
            listOf(MarkdownBlocks.Span.Text("[not a link")),
            MarkdownBlocks.inline("[not a link"),
        )
    }

    @Test
    fun `escaped markers inside emphasis do not end the span early`() {
        val spans = MarkdownBlocks.inline("*a\\*b*")
        assertEquals(1, spans.size)
        assertEquals("a\\*b", (spans[0] as MarkdownBlocks.Span.Italic).value)
    }
}
