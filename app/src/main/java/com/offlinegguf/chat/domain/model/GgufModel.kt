package com.offlinegguf.chat.domain.model

data class GgufModel(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val isValid: Boolean,
    val architecture: String? = null,
    val nVocab: Int? = null,
    val error: String? = null
)
