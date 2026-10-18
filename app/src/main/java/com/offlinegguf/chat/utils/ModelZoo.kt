package com.offlinegguf.chat.utils

import com.offlinegguf.chat.domain.model.ModelInfo

/**
 * Curated list of GGUF-compatible models from the llama.cpp ecosystem.
 * These are NOT bundled with the app — users download them separately
 * and place them in their phone storage.
 */
object ModelZoo {

    val models: List<ModelInfo> = listOf(
        ModelInfo(
            id = "tinyllama-1.1b",
            name = "TinyLlama 1.1B",
            description = "Tiny, fast model for low-RAM phones. Good for simple tasks.",
            sizeGB = 0.7,
            ramNeededGB = 2.0,
            quant = "Q4_K_M",
            tags = listOf("fast", "low-ram", "text"),
            huggingFaceUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF",
            llamaCppUrl = "https://github.com/ggml-org/llama.cpp/tree/master/models"
        ),
        ModelInfo(
            id = "llama-3.2-1b",
            name = "Llama 3.2 1B",
            description = "Meta's lightweight 1B parameter model. Good general chat.",
            sizeGB = 0.6,
            ramNeededGB = 2.0,
            quant = "Q4_K_M",
            tags = "meta", "lightweight", "text",
            huggingFaceUrl = "https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct-GGUF",
            llamaCppUrl = "https://github.com/ggml-org/llama.cpp/tree/master/models"
        ),
        ModelInfo(
            id = "llama-3.2-3b",
            name = "Llama 3.2 3B",
            description = "Balanced 3B model for mid-range phones.",
            sizeGB = 1.8,
            ramNeededGB = 4.0,
            quant = "Q4_K_M",
            tags = listOf("meta", "balanced", "text"),
            huggingFaceUrl = "https://huggingface.co/meta-llama/Llama-3.2-3B-Instruct-GGUF",
            llamaCppUrl = "https://github.com/ggml-org/llama.cpp/tree/master/models"
        ),
        ModelInfo(
            id = "phi-3-mini-3.8b",
            name = "Phi-3 Mini 3.8B",
            description = "Microsoft's small but capable model. Great reasoning for its size.",
            sizeGB = 2.3,
            ramNeededGB = 4.0,
            quant = "Q4_K_M",
            tags = listOf("microsoft", "reasoning", "text"),
            huggingFaceUrl = "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-GGUF",
            llamaCppUrl = "https://github.com/ggml-org/llama.cpp/tree/master/models"
        ),
        ModelInfo(
            id = "llama-3.1-8b",
            name = "Llama 3.1 8B",
            description = "Capable 8B assistant. Needs 8 GB+ RAM phone.",
            sizeGB = 4.9,
            ramNeededGB = 8.0,
            quant = "Q4_K_M",
            tags = listOf("meta", "capable", "text"),
            huggingFaceUrl = "https://huggingface.co/meta-llama/Llama-3.1-8B-Instruct-GGUF",
            llamaCppUrl = "https://github.com/ggml-org/llama.cpp/tree/master/models"
        ),
        ModelInfo(
            id = "qwen2.5-7b",
            name = "Qwen 2.5 7B",
            description = "Strong multilingual and coding model.",
            sizeGB = 4.4,
            ramNeededGB = 8.0,
            quant = "Q4_K_M",
            tags = listOf("qwen", "multilingual", "code", "text"),
            huggingFaceUrl = "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF",
            llamaCppUrl = "https://github.com/ggml-org/llama.cpp/tree/master/models"
        ),
        ModelInfo(
            id = "mistral-7b",
            name = "Mistral 7B",
            description = "Strong reasoning and general assistant.",
            sizeGB = 4.4,
            ramNeededGB = 8.0,
            quant = "Q4_K_M",
            tags = listOf("mistral", "reasoning", "text"),
            huggingFaceUrl = "https://huggingface.co/TheBloke/Mistral-7B-Instruct-v0.2-GGUF",
            llamaCppUrl = "https://github.com/ggml-org/llama.cpp/tree/master/models"
        ),
        ModelInfo(
            id = "llava-1.5-7b",
            name = "LLaVA 1.5 7B",
            description = "**Vision model** — can understand images. Attach photos in chat.",
            sizeGB = 4.6,
            ramNeededGB = 8.0,
            quant = "Q4_K_M",
            isMultimodal = true,
            tags = listOf("vision", "images", "multimodal"),
            huggingFaceUrl = "https://huggingface.co/jartine/LLaVA-1.5-7B-GGUF",
            llamaCppUrl = "https://github.com/ggml-org/llama.cpp/tree/master/models"
        ),
        ModelInfo(
            id = "bakllava-1b",
            name = "BakLLaVA 1B",
            description = "**Tiny vision model** for low-RAM phones. Can see images.",
            sizeGB = 0.6,
            ramNeededGB = 2.0,
            quant = "Q4_K_M",
            isMultimodal = true,
            tags = listOf("vision", "tiny", "multimodal"),
            huggingFaceUrl = "https://huggingface.co/liuhaotian/BakLLaVA-1B-GGUF",
            llamaCppUrl = "https://github.com/ggml-org/llama.cpp/tree/master/models"
        )
    )

    fun getById(id: String): ModelInfo? = models.firstOrNull { it.id == id }

    fun getMultimodalModels(): List<ModelInfo> = models.filter { it.isMultimodal }

    fun getTextModels(): List<ModelInfo> = models.filter { !it.isMultimodal }
}
