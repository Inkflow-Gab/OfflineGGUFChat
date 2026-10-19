package com.offlinegguf.chat.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt formatter is the last line of defence against a transcript that
 * reads like gibberish, so its exact output is pinned here character for
 * character. Special tokens are written with `\u003c` escapes so this file stays
 * readable in tools that treat "<|" as markup.
 */
class PromptFormatterTest {

    private fun user(text: String) = ChatTurn("user", text)
    private fun assistant(text: String) = ChatTurn("assistant", text)
    private fun system(text: String) = ChatTurn("system", text)

    // ----------------------------------------------------------------- turns

    @Test
    fun `chat turns know their role`() {
        assertTrue(user("a").isUser)
        assertTrue(assistant("a").isAssistant)
        assertTrue(system("a").isSystem)
        assertFalse(user("a").isAssistant)
        assertFalse(ChatTurn("tool", "a").isUser)
    }

    // ------------------------------------------------------------- detection

    @Test
    fun `the embedded template beats the architecture name`() {
        assertEquals(
            TemplateStyle.CHATML,
            PromptFormatter.detect("llama", "\u003c|im_start|>system\n{{ content }}\u003c|im_end|>"),
        )
        assertEquals(
            TemplateStyle.LLAMA3,
            PromptFormatter.detect("qwen2", "\u003c|begin_of_text|>\u003c|start_header_id|>user"),
        )
        assertEquals(
            TemplateStyle.GEMMA,
            PromptFormatter.detect("llama", "\u003cstart_of_turn>user\n{{ content }}\u003cend_of_turn>"),
        )
        assertEquals(
            TemplateStyle.PHI3,
            PromptFormatter.detect("llama", "\u003c|assistant|>\n{{ content }}\u003c|end|>"),
        )
        assertEquals(
            TemplateStyle.ZEPHYR,
            PromptFormatter.detect("llama", "\u003c|user|>\n{{ content }}\u003c/s>"),
        )
        assertEquals(
            TemplateStyle.MISTRAL,
            PromptFormatter.detect("qwen2", "[INST] {{ content }} [/INST]"),
        )
    }

    @Test
    fun `architecture names map to a template`() {
        assertEquals(TemplateStyle.CHATML, PromptFormatter.detect("qwen2", null))
        assertEquals(TemplateStyle.CHATML, PromptFormatter.detect("ChatGLM", null))
        assertEquals(TemplateStyle.LLAMA3, PromptFormatter.detect("llama3", null))
        assertEquals(TemplateStyle.GEMMA, PromptFormatter.detect("gemma2", null))
        assertEquals(TemplateStyle.PHI3, PromptFormatter.detect("phi-3", null))
        assertEquals(TemplateStyle.MISTRAL, PromptFormatter.detect("mixtral", null))
        assertEquals(TemplateStyle.MISTRAL, PromptFormatter.detect("llama", null))
    }

    @Test
    fun `unknown models fall back to the generic template`() {
        assertEquals(TemplateStyle.GENERIC, PromptFormatter.detect("mystery-arch", null))
        assertEquals(TemplateStyle.GENERIC, PromptFormatter.detect(null, null))
        assertEquals(TemplateStyle.GENERIC, PromptFormatter.detect(null, ""))
        assertEquals(TemplateStyle.GENERIC, PromptFormatter.detect(null, null, "bert-base"))
    }

    @Test
    fun `template styles parse from their stored name`() {
        assertEquals(TemplateStyle.CHATML, TemplateStyle.fromName("chatml"))
        assertEquals(TemplateStyle.CHATML, TemplateStyle.fromName("CHATML"))
        assertEquals(TemplateStyle.LLAMA3, TemplateStyle.fromName("llama3"))
        assertEquals(TemplateStyle.GENERIC, TemplateStyle.fromName("typo"))
        assertEquals(TemplateStyle.GENERIC, TemplateStyle.fromName(null))
        assertEquals("Mistral [INST]", TemplateStyle.MISTRAL.label)
    }

    // ---------------------------------------------------------------- render

    @Test
    fun `chatml wraps every turn and leaves the assistant open`() {
        val rendered = PromptFormatter.render(
            TemplateStyle.CHATML,
            "You are terse.",
            listOf(user("Hello")),
        )
        assertEquals(
            "\u003c|im_start|>system\nYou are terse.\u003c|im_end|>\n" +
                "\u003c|im_start|>user\nHello\u003c|im_end|>\n" +
                "\u003c|im_start|>assistant\n",
            rendered,
        )
    }

    @Test
    fun `chatml without a system prompt skips the system block`() {
        val rendered = PromptFormatter.render(TemplateStyle.CHATML, "   ", listOf(user("Hello")))
        assertFalse(rendered.contains("system"))
        assertTrue(rendered.endsWith("\u003c|im_start|>assistant\n"))
    }

    @Test
    fun `llama3 emits headers and honours the bos flag`() {
        val withBos = PromptFormatter.render(
            TemplateStyle.LLAMA3,
            "S",
            listOf(user("Hi")),
            addBos = true,
        )
        assertEquals(
            "\u003c|begin_of_text|>" +
                "\u003c|start_header_id|>system\u003c|end_header_id|>\n\nS\u003c|eot_id|>" +
                "\u003c|start_header_id|>user\u003c|end_header_id|>\n\nHi\u003c|eot_id|>" +
                "\u003c|start_header_id|>assistant\u003c|end_header_id|>\n\n",
            withBos,
        )
        val withoutBos = PromptFormatter.render(TemplateStyle.LLAMA3, "S", listOf(user("Hi")))
        assertFalse(withoutBos.contains("begin_of_text"))
        assertTrue(withoutBos.startsWith("\u003c|start_header_id|>system"))
    }

    @Test
    fun `mistral folds the system prompt into the first user turn`() {
        val rendered = PromptFormatter.render(
            TemplateStyle.MISTRAL,
            "Be brief.",
            listOf(user("Hi"), assistant("Hello"), user("Again")),
        )
        assertEquals(
            "[INST] Be brief.\n\nHi [/INST] Hello\u003c/s> [INST] Again [/INST] ",
            rendered,
        )
    }

    @Test
    fun `mistral does not repeat the system prompt on later turns`() {
        val rendered = PromptFormatter.render(
            TemplateStyle.MISTRAL,
            "Be brief.",
            listOf(user("One"), assistant("A"), user("Two")),
        )
        assertEquals(1, rendered.split("Be brief.").size - 1)
    }

    @Test
    fun `zephyr always opens with a system block`() {
        val rendered = PromptFormatter.render(TemplateStyle.ZEPHYR, "", listOf(user("Hi")))
        assertEquals(
            "\u003c|system|>\n\u003c/s>\n\u003c|user|>\nHi\u003c/s>\n\u003c|assistant|>\n",
            rendered,
        )
    }

    @Test
    fun `gemma renames the assistant role to model`() {
        val rendered = PromptFormatter.render(
            TemplateStyle.GEMMA,
            "S",
            listOf(user("Hi"), assistant("Yo")),
        )
        assertEquals(
            "\u003cstart_of_turn>system\nS\u003cend_of_turn>\n" +
                "\u003cstart_of_turn>user\nHi\u003cend_of_turn>\n" +
                "\u003cstart_of_turn>model\nYo\u003cend_of_turn>\n" +
                "\u003cstart_of_turn>model\n",
            rendered,
        )
    }

    @Test
    fun `phi3 and vicuna and generic render their own shapes`() {
        assertEquals(
            "\u003c|user|>\nHi\u003c|end|>\n\u003c|assistant|>\n",
            PromptFormatter.render(TemplateStyle.PHI3, "", listOf(user("Hi"))),
        )
        assertEquals(
            "Sys\n\nUSER: Hi ASSISTANT:",
            PromptFormatter.render(TemplateStyle.VICUNA, "Sys", listOf(user("Hi"))),
        )
        assertEquals(
            "System: Sys\n\nUser: Hi\n\nAssistant:",
            PromptFormatter.render(TemplateStyle.GENERIC, "Sys", listOf(user("Hi"))),
        )
    }

    @Test
    fun `system turns in the list never reach the body`() {
        val rendered = PromptFormatter.render(
            TemplateStyle.GENERIC,
            "From parameter",
            listOf(system("From list"), user("Hi")),
        )
        assertFalse(rendered.contains("From list"))
        assertTrue(rendered.contains("From parameter"))
    }

    @Test
    fun `blank user turns are dropped but blank assistant turns survive`() {
        val rendered = PromptFormatter.render(
            TemplateStyle.GENERIC,
            "",
            listOf(user("   "), assistant(""), user("Hi")),
        )
        assertEquals("Assistant: \n\nUser: Hi\n\nAssistant:", rendered)
    }

    @Test
    fun `every style ends with an open assistant turn`() {
        val expectedTail = mapOf(
            TemplateStyle.CHATML to "\u003c|im_start|>assistant\n",
            TemplateStyle.LLAMA3 to "\u003c|start_header_id|>assistant\u003c|end_header_id|>\n\n",
            TemplateStyle.MISTRAL to "[/INST] ",
            TemplateStyle.ZEPHYR to "\u003c|assistant|>\n",
            TemplateStyle.GEMMA to "\u003cstart_of_turn>model\n",
            TemplateStyle.PHI3 to "\u003c|assistant|>\n",
            TemplateStyle.VICUNA to "ASSISTANT:",
            TemplateStyle.GENERIC to "Assistant:",
        )
        assertEquals(TemplateStyle.values().size, expectedTail.size)
        for ((style, tail) in expectedTail) {
            val rendered = PromptFormatter.render(style, "S", listOf(user("Hi")))
            assertTrue("$style should end with an open turn", rendered.endsWith(tail))
        }
    }

    // ------------------------------------------------------------ end markers

    @Test
    fun `each style advertises the markers that end a turn`() {
        assertTrue(PromptFormatter.endMarkers(TemplateStyle.CHATML).contains("\u003c|im_end|>"))
        assertTrue(PromptFormatter.endMarkers(TemplateStyle.LLAMA3).contains("\u003c|eot_id|>"))
        assertTrue(PromptFormatter.endMarkers(TemplateStyle.MISTRAL).contains("\u003c/s>"))
        assertTrue(PromptFormatter.endMarkers(TemplateStyle.GEMMA).contains("\u003cend_of_turn>"))
        assertTrue(PromptFormatter.endMarkers(TemplateStyle.PHI3).contains("\u003c|end|>"))
        assertTrue(PromptFormatter.endMarkers(TemplateStyle.VICUNA).contains("USER:"))
        assertTrue(PromptFormatter.endMarkers(TemplateStyle.GENERIC).contains("\nUser:"))
        for (style in TemplateStyle.values()) {
            assertTrue("$style has no markers", PromptFormatter.endMarkers(style).isNotEmpty())
        }
    }

    @Test
    fun `trimming cuts at the earliest marker`() {
        assertEquals(
            "Answer",
            PromptFormatter.trimAtEndMarker(
                TemplateStyle.CHATML,
                "Answer\u003c|im_end|>junk\u003c|endoftext|>",
            ),
        )
        assertEquals(
            "A",
            PromptFormatter.trimAtEndMarker(TemplateStyle.CHATML, "A\u003c|im_start|>B\u003c|im_end|>C"),
        )
    }

    @Test
    fun `trimming leaves clean text alone`() {
        assertEquals(
            "Just an answer.",
            PromptFormatter.trimAtEndMarker(TemplateStyle.CHATML, "Just an answer."),
        )
        assertEquals(
            "",
            PromptFormatter.trimAtEndMarker(TemplateStyle.CHATML, "\u003c|im_end|>"),
        )
        assertEquals(
            "Hello\u003c/s>",
            PromptFormatter.trimAtEndMarker(TemplateStyle.CHATML, "Hello\u003c/s>"),
        )
    }

    @Test
    fun `containsEndMarker only matches the active style`() {
        assertTrue(PromptFormatter.containsEndMarker(TemplateStyle.CHATML, "x\u003c|im_end|>y"))
        assertFalse(PromptFormatter.containsEndMarker(TemplateStyle.GEMMA, "x\u003c|im_end|>y"))
        assertTrue(PromptFormatter.containsEndMarker(TemplateStyle.GEMMA, "x\u003cend_of_turn>y"))
        assertFalse(PromptFormatter.containsEndMarker(TemplateStyle.GENERIC, "plain answer"))
    }

    // ------------------------------------------------------------ composition

    @Test
    fun `plain input is trimmed and returned as-is`() {
        assertEquals("hi", PromptFormatter.composeUserMessage("  hi  ", null, null))
        assertEquals("hi", PromptFormatter.composeUserMessage("hi", "   ", "   "))
    }

    @Test
    fun `attachments are appended under a divider`() {
        assertEquals(
            "hi\n\n--- Attached files ---\nnotes.txt:\nbody",
            PromptFormatter.composeUserMessage("hi", "  notes.txt:\nbody  ", null),
        )
    }

    @Test
    fun `templates substitute both placeholders`() {
        assertEquals(
            "Summarise: hi",
            PromptFormatter.composeUserMessage("hi", null, "Summarise: {{input}}"),
        )
        assertEquals(
            "hi\n\nFILES\nnotes.txt",
            PromptFormatter.composeUserMessage("hi", "notes.txt", "{{input}}\n\nFILES\n{{attachments}}"),
        )
        assertEquals(
            "Context:\nnotes.txt\n\nhi",
            PromptFormatter.composeUserMessage("hi", "notes.txt", "Context:\n{{attachments}}"),
        )
    }

    @Test
    fun `a template without the input placeholder still keeps the input`() {
        assertEquals(
            "Always answer in bullet points.\n\nhi",
            PromptFormatter.composeUserMessage("hi", null, "Always answer in bullet points."),
        )
    }
}
