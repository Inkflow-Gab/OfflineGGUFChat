package com.offlinegguf.chat.inference

/**
 * JNI bindings to llama.cpp.
 *
 * Large GGUF support (1 GB+) on weak phones:
 *   - useMmap = true  -> OS demand-pages the file; only touched pages resident
 *   - useMlock = false -> do not pin model in RAM (leaves room for system)
 *   - small n_ctx     -> keeps KV cache small on low-RAM devices
 */
external fun LlamaNative.llamaCreateSession(): Long
external fun LlamaNative.llamaDestroySession(handle: Long)
external fun LlamaNative.llamaLoadModel(
    handle: Long,
    path: String,
    nCtx: Int,
    nGpuLayers: Int,
    nThreads: Int,
    nBatch: Int,
    useMmap: Boolean,
    useMlock: Boolean
): Boolean
external fun LlamaNative.llamaIsMultimodal(handle: Long): Boolean
external fun LlamaNative.llamaGetLastError(handle: Long): String
external fun LlamaNative.llamaNVocab(handle: Long): Int
external fun LlamaNative.llamaNCtxTrain(handle: Long): Int
external fun LlamaNative.llamaArch(handle: Long): String
external fun LlamaNative.llamaGenerate(
    handle: Long,
    prompt: String,
    nPredict: Int,
    temperature: Float,
    topP: Float,
    topK: Int,
    repeatPenalty: Float,
    seed: Int,
    callback: GenerationCallback
): Boolean
external fun LlamaNative.llamaStop(handle: Long)
external fun LlamaNative.llamaGetTokenCount(handle: Long): Int
external fun LlamaNative.llamaSupportsMmap(): Boolean

interface GenerationCallback {
    fun onToken(token: String)
    fun onDone()
    fun onError(error: String)
}

object LlamaNative {
    init {
        System.loadLibrary("llama")
        System.loadLibrary("llama_jni")
    }
}
