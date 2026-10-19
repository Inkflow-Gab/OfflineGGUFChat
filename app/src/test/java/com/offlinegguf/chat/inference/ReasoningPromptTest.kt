package com.offlinegguf.chat.inference

import com.offlinegguf.chat.data.settings.AppSettings
import com.offlinegguf.chat.domain.device.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for "one word takes three minutes".
 *
 * The cause was a thinking model (Qwen3-family, the 596M model is Qwen3-0.6B)
 * left in its default reasoning mode with no way to turn it off. The fix uses
 * the model's own template to ask for a direct answer. These tests pin the
 * three things that made that possible: detection, the exact prompt bytes, and
 * sane defaults.
 */
class ReasoningPromptTest {

    private val qwen3Template = """
        {%- if enable_thinking is defined and enable_thinking is false %}
        {{- ' thinking\n\n\n\n' }}
        {%- endif %}
    """.trimIndent()

    @Test
    fun `qwen3 is recognised from its architecture or its template`() {
        assertTrue(PromptFormatter.supportsThinking("qwen3", null))
        assertTrue(PromptFormatter.supportsThinking("Qwen3", ""))
        assertTrue(PromptFormatter.supportsThinking("qwen3moe", null))
        assertTrue(PromptFormatter.supportsThinking("qwen2", qwen3Template))
        assertTrue(PromptFormatter.supportsThinking(null, qwen3Template))
    }

    @Test
    fun `plain models are not given a thinking switch`() {
        assertFalse(PromptFormatter.supportsThinking("qwen2", "<|im_start|>user<|im_end|>"))
        assertFalse(PromptFormatter.supportsThinking("llama", "<|start_header_id|>"))
        assertFalse(PromptFormatter.supportsThinking(null, null))
    }

    @Test
    fun `reasoning off appends the template's empty think block`() {
        val prompt = PromptFormatter.render(
            style = TemplateStyle.CHATML,
            system = "",
            turns = listOf(ChatTurn("user", "hello")),
            reasoning = false,
        )
        assertEquals(
            "<|im_start|>user\nhello<|im_end|>\n<|im_start|>assistant\n" +
                PromptFormatter.EMPTY_THINKING_BLOCK,
            prompt,
        )
        // The marker is a real newline escape, not a literal backslash-n.
        assertTrue(prompt.contains(" thinking\n\n\n\n"))
        assertFalse(prompt.contains("\\n"))
    }

    @Test
    fun `reasoning on leaves the generation prompt open`() {
        val prompt = PromptFormatter.render(
            style = TemplateStyle.CHATML,
            system = "",
            turns = listOf(ChatTurn("user", "hello")),
            reasoning = true,
        )
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
        assertFalse(prompt.contains(" thinking"))
    }

    @Test
    fun `formats without a reasoning mode ignore the flag`() {
        val turns = listOf(ChatTurn("user", "hello"))
        assertEquals(
            PromptFormatter.render(TemplateStyle.LLAMA3, "", turns),
            PromptFormatter.render(TemplateStyle.LLAMA3, "", turns, reasoning = false),
        )
    }

    @Test
    fun `reasoning is off by default because phones are slow`() {
        assertFalse(AppSettings().localReasoning)
        // The transcript still shows reasoning if a user turns it on.
        assertTrue(AppSettings().showThinking)
    }

    @Test
    fun `a multi-core phone is never given a single inference thread`() {
        val a05 = DeviceProfile.Unknown.copy(
            ramBytes = 4L * 1024 * 1024 * 1024,
            cores = 8,
            lowRamDevice = true,
        )
        assertTrue("A 4 GB budget phone must decode on more than one core", a05.suggestedThreads >= 2)

        val normal = a05.copy(lowRamDevice = false)
        assertTrue(normal.suggestedThreads >= 2)
        assertEquals(1, a05.copy(cores = 2).suggestedThreads)
    }
}
