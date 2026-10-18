package com.offlinegguf.chat.domain.model

/**
 * A skill that modifies model behavior.
 * Types:
 *   - lora: LoRA adapter file (.gguf) applied on top of base model
 *   - grammar: GBNF grammar file constraining output (JSON, SQL, etc.)
 *   - system: Custom system prompt template
 */
data class Skill(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val type: SkillType,
    val path: String? = null,
    val content: String? = null,
    val scale: Float = 1.0f,
    val enabled: Boolean = true
)

enum class SkillType {
    LORA, GRAMMAR, SYSTEM
}
