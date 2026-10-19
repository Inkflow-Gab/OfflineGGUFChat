package com.offlinegguf.chat.inference

/** One message in the prompt being built. */
data class ChatTurn(val role: String, val content: String) {
    val isUser: Boolean get() = role == Roles.USER
    val isAssistant: Boolean get() = role == Roles.ASSISTANT
    val isSystem: Boolean get() = role == Roles.SYSTEM

    private object Roles {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val SYSTEM = "system"
    }
}

/**
 * Chat prompt formats this app knows how to write by hand.
 *
 * When a GGUF ships a chat template, the engine's own templating wins and these
 * are not used. They matter for the many files that have none, and for the
 * "template" selector in settings.
 */
enum class TemplateStyle(val label: String) {
    CHATML("ChatML"),
    LLAMA3("Llama 3"),
    MISTRAL("Mistral [INST]"),
    ZEPHYR("Zephyr"),
    GEMMA("Gemma"),
    PHI3("Phi-3"),
    VICUNA("Vicuna"),
    GENERIC("Generic");

    companion object {
        fun fromName(name: String?): TemplateStyle =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: GENERIC
    }
}

object PromptFormatter {

    /**
     * Generation prompt Qwen3's own chat template emits when thinking is
     * disabled: an *empty* thinking block. The model then answers directly.
     *
     * This is deliberately not "strip <think> tokens afterwards": the text the
     * model produces is steered by its template, the way the model was trained.
     * Models without a thinking switch never see it ([supportsThinking]).
     */
    const val EMPTY_THINKING_BLOCK = " thinking\n\n\n\n"

    /**
     * True when the file exposes a reasoning mode we can actually control: a
     * template that mentions `enable_thinking`, or the Qwen3 family, which is
     * trained to reason by default.
     */
    fun supportsThinking(architecture: String?, chatTemplate: String?): Boolean {
        val arch = architecture?.lowercase().orEmpty()
        val template = chatTemplate.orEmpty().lowercase()
        return template.contains("enable_thinking") || arch.startsWith("qwen3") ||
            arch == "qwen3moe" || arch == "qwen3-next"
    }

    /**
     * Picks a template from what the file told us. The embedded chat template is
     * the strongest signal; the architecture is the fallback.
     */
    fun detect(
        architecture: String?,
        chatTemplate: String?,
        tokenizerModel: String? = null,
    ): TemplateStyle {
        val template = chatTemplate?.lowercase().orEmpty()
        when {
            template.contains("<|im_start|>") || template.contains("im_start") -> return TemplateStyle.CHATML
            template.contains("<|start_header_id|>") || template.contains("<|begin_of_text|>") ->
                return TemplateStyle.LLAMA3
            template.contains("<start_of_turn>") -> return TemplateStyle.GEMMA
            template.contains("<|end|>") && template.contains("<|assistant|>") -> return TemplateStyle.PHI3
            template.contains("<|user|>") || template.contains("<|system|>") -> return TemplateStyle.ZEPHYR
            template.contains("[inst]") -> return TemplateStyle.MISTRAL
        }
        return when (architecture?.lowercase()) {
            "qwen2", "qwen", "qwen2moe", "yi", "chatglm", "internlm2" -> TemplateStyle.CHATML
            "llama3", "llama-3" -> TemplateStyle.LLAMA3
            "gemma", "gemma2", "gemma3" -> TemplateStyle.GEMMA
            "phi3", "phi-3", "phi2" -> TemplateStyle.PHI3
            "mistral", "mixtral", "llama" -> TemplateStyle.MISTRAL
            else -> if (tokenizerModel?.lowercase()?.contains("bert") == true) TemplateStyle.GENERIC
            else TemplateStyle.GENERIC
        }
    }

    /**
     * Builds the string handed to `nativeGenerate`. The assistant turn is left
     * open at the end: the model continues from there.
     */
    fun render(
        style: TemplateStyle,
        system: String,
        turns: List<ChatTurn>,
        addBos: Boolean = false,
        /**
         * false = ask a thinking-capable model to answer directly. Ignored by
         * formats and models that have no reasoning mode at all.
         */
        reasoning: Boolean = true,
    ): String {
        val cleaned = turns.filter { it.content.isNotBlank() || it.isAssistant }
        val sys = system.trim()
        val builder = StringBuilder(1024)

        when (style) {
            TemplateStyle.CHATML -> {
                if (sys.isNotEmpty()) builder.append("<|im_start|>system\n").append(sys).append("<|im_end|>\n")
                for (turn in cleaned) {
                    builder.append("<|im_start|>").append(turn.role).append('\n')
                        .append(turn.content).append("<|im_end|>\n")
                }
                builder.append("<|im_start|>assistant\n")
                // Qwen3: an empty think block is the template's own way of
                // saying "no reasoning", so the model starts the answer instead
                // of spending hundreds of tokens thinking first.
                if (!reasoning) builder.append(EMPTY_THINKING_BLOCK)
            }

            TemplateStyle.LLAMA3 -> {
                if (addBos) builder.append("<|begin_of_text|>")
                if (sys.isNotEmpty()) header(builder, "system").append(sys).append("<|eot_id|>")
                for (turn in cleaned) {
                    header(builder, turn.role).append(turn.content).append("<|eot_id|>")
                }
                header(builder, "assistant")
            }

            TemplateStyle.MISTRAL -> {
                // Mistral has no system role: fold it into the first user turn.
                var first = true
                for (turn in cleaned.filter { !it.isSystem }) {
                    if (turn.isUser) {
                        builder.append("[INST] ")
                        if (first && sys.isNotEmpty()) builder.append(sys).append("\n\n")
                        builder.append(turn.content).append(" [/INST]")
                        first = false
                    } else if (turn.isAssistant) {
                        builder.append(' ').append(turn.content).append("</s> ")
                    }
                }
                if (cleaned.lastOrNull()?.isAssistant != true) builder.append(' ')
            }

            TemplateStyle.ZEPHYR -> {
                builder.append("<|system|>\n").append(sys).append("</s>\n")
                for (turn in cleaned.filter { !it.isSystem }) {
                    val tag = if (turn.isAssistant) "assistant" else "user"
                    builder.append("<|").append(tag).append("|>\n").append(turn.content).append("</s>\n")
                }
                builder.append("<|assistant|>\n")
            }

            TemplateStyle.GEMMA -> {
                if (sys.isNotEmpty()) builder.append("<start_of_turn>system\n").append(sys).append("<end_of_turn>\n")
                for (turn in cleaned.filter { !it.isSystem }) {
                    val tag = if (turn.isAssistant) "model" else "user"
                    builder.append("<start_of_turn>").append(tag).append('\n')
                        .append(turn.content).append("<end_of_turn>\n")
                }
                builder.append("<start_of_turn>model\n")
            }

            TemplateStyle.PHI3 -> {
                if (sys.isNotEmpty()) builder.append("<|system|>\n").append(sys).append("<|end|>\n")
                for (turn in cleaned.filter { !it.isSystem }) {
                    builder.append("<|").append(turn.role).append("|>\n")
                        .append(turn.content).append("<|end|>\n")
                }
                builder.append("<|assistant|>\n")
            }

            TemplateStyle.VICUNA -> {
                if (sys.isNotEmpty()) builder.append(sys).append("\n\n")
                for (turn in cleaned.filter { !it.isSystem }) {
                    if (turn.isUser) builder.append("USER: ").append(turn.content).append(" ")
                    else builder.append("ASSISTANT: ").append(turn.content).append("</s>")
                }
                builder.append("ASSISTANT:")
            }

            TemplateStyle.GENERIC -> {
                if (sys.isNotEmpty()) builder.append("System: ").append(sys).append("\n\n")
                for (turn in cleaned.filter { !it.isSystem }) {
                    val label = if (turn.isAssistant) "Assistant" else "User"
                    builder.append(label).append(": ").append(turn.content).append("\n\n")
                }
                builder.append("Assistant:")
            }
        }
        return builder.toString()
    }

    private fun header(builder: StringBuilder, role: String): StringBuilder =
        builder.append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n")

    /**
     * Strings that mark the end of an assistant turn. The engine only stops on
     * *token* boundaries it was told about, so a model that keeps going past its
     * own end marker gets trimmed here — otherwise the transcript fills with
     * hallucinated second answers.
     */
    fun endMarkers(style: TemplateStyle): List<String> = when (style) {
        TemplateStyle.CHATML -> listOf("<|im_end|>", "<|im_start|>", "<|endoftext|>")
        TemplateStyle.LLAMA3 -> listOf("<|eot_id|>", "<|end_of_text|>", "<|start_header_id|>")
        TemplateStyle.MISTRAL -> listOf("</s>", "[INST]", "<|im_end|>")
        TemplateStyle.ZEPHYR -> listOf("</s>", "<|user|>", "<|assistant|>")
        TemplateStyle.GEMMA -> listOf("<end_of_turn>", "<start_of_turn>user")
        TemplateStyle.PHI3 -> listOf("<|end|>", "<|user|>", "<|assistant|>")
        TemplateStyle.VICUNA -> listOf("</s>", "USER:", "Human:")
        TemplateStyle.GENERIC -> listOf("\nUser:", "\nHuman:", "\nSystem:")
    }

    /** Cuts [text] at the first end marker, if any. */
    fun trimAtEndMarker(style: TemplateStyle, text: String): String {
        var cut = text.length
        for (marker in endMarkers(style)) {
            val index = text.indexOf(marker)
            if (index in 0 until cut) cut = index
        }
        return if (cut >= text.length) text else text.substring(0, cut)
    }

    /** True when [text] already contains an end marker. */
    fun containsEndMarker(style: TemplateStyle, text: String): Boolean =
        endMarkers(style).any { text.contains(it) }

    /**
     * Builds the user-visible message body, folding in attachments and applying a
     * skill template. `{{input}}` and `{{attachments}}` are the only placeholders.
     */
    fun composeUserMessage(input: String, attachmentText: String?, template: String?): String {
        val body = input.trim()
        val attachments = attachmentText?.trim().orEmpty()
        if (template.isNullOrBlank()) {
            return when {
                attachments.isEmpty() -> body
                else -> "$body\n\n--- Attached files ---\n$attachments"
            }
        }
        var out = template.replace("{{input}}", body)
        out = out.replace("{{attachments}}", attachments)
        if (!template.contains("{{input}}")) {
            out = "$out\n\n$body"
        }
        // Attachments must not disappear merely because a skill's user template
        // only declares {{input}}. Explicit placeholders still let a skill place
        // file contents exactly where its instructions expect them.
        if (attachments.isNotEmpty() && !template.contains("{{attachments}}")) {
            out = "$out\n\n--- Attached files ---\n$attachments"
        }
        return out
    }
}
