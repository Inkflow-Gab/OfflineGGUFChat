package com.offlinegguf.chat.domain.model

data class SamplingParams(
    val nCtx: Int = 1024,
    val nGpuLayers: Int = 0,
    val nThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2).coerceAtMost(4),
    val nBatch: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val seed: Int = -1,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false
)
