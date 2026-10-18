package com.offlinegguf.chat.inference

/**
 * JNI binding for `libllama_jni.so`.
 *
 * Two rules keep this file honest:
 *
 * 1. Every `external` declaration must have a matching `Java_...` symbol in
 *    `app/cpp/llama_jni.cpp`. The list is checked by `scripts/check-native.sh`
 *    (it compares these names against `nm` output of the compiled object).
 * 2. Loading the library may legitimately fail — an x86 emulator, a device whose
 *    ABI was not built, or a build without the engine. That is not a crash: the
 *    failure is captured into [loadError], [isAvailable] reports false, and the
 *    UI explains what to do. Nothing here is called unless [isAvailable] is true.
 */
object LlamaNative {

    private const val LIBRARY = "llama_jni"

    /** True when `System.loadLibrary` succeeded. */
    val libraryLoaded: Boolean

    /** Human-readable reason when [libraryLoaded] is false. */
    val loadError: String?

    init {
        var loaded = false
        var error: String? = null
        try {
            System.loadLibrary(LIBRARY)
            loaded = true
        } catch (t: Throwable) {
            error = when (t) {
                is UnsatisfiedLinkError ->
                    "lib$LIBRARY.so is not available for this device/ABI. " +
                        (t.message ?: "No details from the loader.")
                else -> "lib$LIBRARY.so failed to load: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        libraryLoaded = loaded
        loadError = error
    }

    /**
     * True when the library loaded *and* a real engine was compiled in. The
     * default build ships the null bridge, which loads fine but reports no
     * engine — see `app/cpp/llama/llama_bridge_none.cpp`.
     */
    val isAvailable: Boolean
        get() = libraryLoaded && runCatching { nativeEngineAvailable() }.getOrDefault(false)

    /** What the engine says about itself, including the "build me" message. */
    val engineVersion: String
        get() = if (!libraryLoaded) (loadError ?: "native library not loaded")
        else runCatching { nativeEngineVersion() }.getOrElse { "unavailable: ${it.message}" }

    /** Guards every other call. */
    fun safeProbe(path: String, budgetBytes: Long): String? =
        if (!libraryLoaded) "native library not loaded"
        else runCatching { nativeProbeGguf(path, budgetBytes) }
            .getOrElse { "probe failed: ${it.message}" }

    // ---------------------------------------------------------------- engine
    external fun nativeEngineAvailable(): Boolean
    external fun nativeEngineVersion(): String

    /**
     * Returns null when the header is fine; otherwise a message describing what
     * is wrong (bad magic, truncated tensor table, unsupported version…).
     */
    external fun nativeProbeGguf(path: String, budgetBytes: Long): String?

    // --------------------------------------------------------------- session
    external fun nativeCreateSession(): Long
    external fun nativeDestroySession(handle: Long)

    external fun nativeLoadModel(
        handle: Long,
        path: String,
        nCtx: Int,
        nGpuLayers: Int,
        nThreads: Int,
        nBatch: Int,
        useMmap: Boolean,
        useMlock: Boolean,
        loraPaths: Array<String>,
        loraScales: FloatArray,
    ): Boolean

    external fun nativeUnloadModel(handle: Long)
    external fun nativeIsLoaded(handle: Long): Boolean
    external fun nativeLastError(handle: Long): String
    external fun nativeIsBusy(handle: Long): Boolean

    // --------------------------------------------------------------- report
    external fun nativeVocabSize(handle: Long): Int
    external fun nativeNctxTrain(handle: Long): Int
    external fun nativeContextSize(handle: Long): Int
    external fun nativeContextUsed(handle: Long): Int
    external fun nativeParamCount(handle: Long): Long
    external fun nativeWeightBytes(handle: Long): Long
    external fun nativeModelDescription(handle: Long): String
    external fun nativeChatTemplate(handle: Long): String
    external fun nativeHasMultimodal(handle: Long): Boolean
    external fun nativeSupportsAdapters(handle: Long): Boolean

    // ----------------------------------------------------------- generation
    /** Ask the in-flight generation to stop at the next token boundary. */
    external fun nativeStop(handle: Long)

    /** Drop cached KV beyond `keepTokens` so a prefix can be reused. */
    external fun nativeRewindContext(handle: Long, keepTokens: Int)

    /**
     * Blocking generate. Returns null when nothing started (read
     * [nativeLastError]); otherwise the stats array laid out as
     * `{tokensGenerated, promptTokens, contextUsed, microseconds, flags}` where
     * bit 0 of flags means "context truncated the prompt" and bit 1 means
     * "cancelled by the user".
     *
     * [ints] and [floats] are positional — see [SamplingParams.toInts].
     */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        ints: IntArray,
        floats: FloatArray,
        grammar: String?,
        stopTokens: IntArray?,
        callback: GenerationCallback,
    ): LongArray?

    /**
     * Raw UTF-8 pieces, exactly as the tokenizer produced them: a piece may end
     * in the middle of a multi-byte character, so the caller must reframe with
     * [com.offlinegguf.chat.util.StreamingUtf8Decoder]. Never a Java String per
     * token — that path would corrupt astral characters and allocate per token.
     */
    interface GenerationCallback {
        fun onTokenBytes(bytes: ByteArray)
        fun onError(message: String)
    }
}
