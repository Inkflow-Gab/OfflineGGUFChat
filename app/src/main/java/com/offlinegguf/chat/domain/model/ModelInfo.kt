package com.offlinegguf.chat.domain.model

/**
 * Information about a publicly available GGUF model from the llama.cpp ecosystem.
 * Used by the Model Zoo screen to show users what models exist and where to get them.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val sizeGB: Double,
    val ramNeededGB: Double,
    val quant: String,
    val isMultimodal: Boolean = false,
    val huggingFaceUrl: String = "",
    val llamaCppUrl: String = "",
    val tags: List<String> = emptyList()
)
