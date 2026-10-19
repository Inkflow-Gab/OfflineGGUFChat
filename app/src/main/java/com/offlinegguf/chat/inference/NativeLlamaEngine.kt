package com.offlinegguf.chat.inference

import com.offlinegguf.chat.util.StreamingUtf8Decoder
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Events a generation emits, in order. */
sealed class EngineEvent {
    /**
     * Decoded, UTF-8-safe text. May still contain a template end marker — the
     * caller trims with [PromptFormatter.trimAtEndMarker] once the stream ends.
     */
    data class Token(val text: String) : EngineEvent()

    data class Finished(val stats: GenerationStats, val outcome: GenerationOutcome) : EngineEvent()

    data class Failed(val message: String) : EngineEvent()
}

data class LoadResult(
    val success: Boolean,
    val message: String?,
    val report: ModelReport,
    val elapsedMs: Long,
) {
    companion object {
        fun unavailable(message: String) = LoadResult(false, message, ModelReport.Empty, 0)
    }
}

/**
 * Owns exactly one native session.
 *
 * Deliberately a plain class, not a ViewModel: it is created once by the app
 * container and outlives every screen. All native calls are funnelled through a
 * single-threaded dispatcher plus a mutex, because the C++ bridge is written for
 * one generation at a time per session and llama.cpp itself is not reentrant.
 *
 * Text arrives as raw UTF-8 byte pieces ([LlamaNative.GenerationCallback]) and is
 * reframed here by [StreamingUtf8Decoder], so a multi-byte character split across
 * two tokens can never turn into a replacement character.
 */
class NativeLlamaEngine : Closeable {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gguf-engine").apply {
            // Slightly below normal: generation is long, and starving the UI
            // thread of CPU makes streaming look choppy.
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val mutex = Mutex()
    private val handle = AtomicLong(0L)
    private val generating = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    val isAvailable: Boolean get() = LlamaNative.isAvailable

    val version: String get() = LlamaNative.engineVersion

    val unavailableReason: String?
        get() = when {
            LlamaNative.libraryLoaded && !LlamaNative.isAvailable -> LlamaNative.engineVersion
            !LlamaNative.libraryLoaded -> LlamaNative.loadError
            else -> null
        }

    val isLoaded: Boolean
        get() {
            val h = handle.get()
            return h != 0L && LlamaNative.libraryLoaded &&
                runCatching { LlamaNative.nativeIsLoaded(h) }.getOrDefault(false)
        }

    val isGenerating: Boolean get() = generating.get()

    /**
     * Single source of truth for the model resident in the process-wide engine.
     * Screens have separate ViewModels; this stream keeps Chat in sync when a
     * model is loaded from Model Detail (or unloaded from Settings).
     */
    private val _loadedModelPath = MutableStateFlow<String?>(null)
    val loadedModelPath: StateFlow<String?> = _loadedModelPath.asStateFlow()

    val loadedPath: String? get() = _loadedModelPath.value

    /** Header check that does not require loading anything. */
    fun probe(path: String, budgetBytes: Long = 8L * 1024 * 1024): String? =
        LlamaNative.safeProbe(path, budgetBytes)

    private fun ensureSession(): Long {
        if (!LlamaNative.libraryLoaded) return 0L
        val existing = handle.get()
        if (existing != 0L) return existing
        val created = runCatching { LlamaNative.nativeCreateSession() }.getOrDefault(0L)
        handle.compareAndSet(0L, created)
        return handle.get()
    }

    suspend fun load(request: LoadRequest): LoadResult {
        if (!LlamaNative.libraryLoaded) {
            return LoadResult.unavailable(LlamaNative.loadError ?: "native library not loaded")
        }
        val started = System.nanoTime()
        return withContext(dispatcher) {
            mutex.withLock {
                val session = ensureSession()
                if (session == 0L) return@withLock LoadResult.unavailable("could not create an engine session")

                // A different model is resident: unload before loading, or the
                // previous weights stay mapped and the process gets killed.
                if (runCatching { LlamaNative.nativeIsLoaded(session) }.getOrDefault(false)) {
                    runCatching { LlamaNative.nativeUnloadModel(session) }
                    _loadedModelPath.value = null
                }

                val ok = runCatching {
                    LlamaNative.nativeLoadModel(
                        session,
                        request.path,
                        request.context,
                        request.gpuLayers,
                        request.threads,
                        request.batch,
                        request.useMmap,
                        request.useMlock,
                        request.loraPaths.toTypedArray(),
                        if (request.loraScales.isEmpty()) FloatArray(0) else request.loraScales.toFloatArray(),
                    )
                }.getOrElse { false }

                val elapsed = (System.nanoTime() - started) / 1_000_000L
                if (!ok) {
                    val message = runCatching { LlamaNative.nativeLastError(session) }
                        .getOrDefault("model failed to load")
                    return@withLock LoadResult(false, message.ifBlank { "model failed to load" }, ModelReport.Empty, elapsed)
                }
                _loadedModelPath.value = request.path
                LoadResult(true, null, reportLocked(session), elapsed)
            }
        }
    }

    suspend fun unload() {
        val session = handle.get()
        if (session == 0L) return
        withContext(dispatcher) {
            mutex.withLock {
                requestStop()
                runCatching { LlamaNative.nativeUnloadModel(session) }
                _loadedModelPath.value = null
            }
        }
    }

    suspend fun report(): ModelReport {
        val session = handle.get()
        if (session == 0L) return ModelReport.Empty
        return withContext(dispatcher) { reportLocked(session) }
    }

    private fun reportLocked(session: Long): ModelReport = runCatching {
        ModelReport(
            description = LlamaNative.nativeModelDescription(session),
            chatTemplate = LlamaNative.nativeChatTemplate(session),
            vocabSize = LlamaNative.nativeVocabSize(session),
            trainedContext = LlamaNative.nativeNctxTrain(session),
            contextSize = LlamaNative.nativeContextSize(session),
            contextUsed = LlamaNative.nativeContextUsed(session),
            paramCount = LlamaNative.nativeParamCount(session),
            weightBytes = LlamaNative.nativeWeightBytes(session),
            multimodal = LlamaNative.nativeHasMultimodal(session),
            supportsAdapters = LlamaNative.nativeSupportsAdapters(session),
        )
    }.getOrElse { ModelReport.Empty }

    suspend fun contextUsed(): Int {
        val session = handle.get()
        if (session == 0L) return 0
        return withContext(dispatcher) {
            runCatching { LlamaNative.nativeContextUsed(session) }.getOrDefault(0)
        }
    }

    suspend fun contextSize(): Int {
        val session = handle.get()
        if (session == 0L) return 0
        return withContext(dispatcher) {
            runCatching { LlamaNative.nativeContextSize(session) }.getOrDefault(0)
        }
    }

    /**
     * Forget cached KV beyond [keepTokens].
     *
     * Used after "edit and resend" or "regenerate": the prompt prefix is still
     * valid, so keeping it makes the retry start instantly instead of
     * re-processing the whole transcript. `keepTokens <= 0` clears everything.
     */
    suspend fun rewind(keepTokens: Int) {
        val session = handle.get()
        if (session == 0L) return
        withContext(dispatcher) {
            runCatching { LlamaNative.nativeRewindContext(session, keepTokens) }
        }
    }

    fun requestStop() {
        val session = handle.get()
        if (session == 0L || !LlamaNative.libraryLoaded) return
        runCatching { LlamaNative.nativeStop(session) }
    }

    /**
     * Streams one generation. Cancelling the collector asks the engine to stop at
     * the next token boundary, so no work is wasted after the user hits stop.
     */
    fun stream(prompt: String, params: SamplingParams): Flow<EngineEvent> = callbackFlow {
        val session = handle.get()
        if (!LlamaNative.libraryLoaded) {
            trySend(EngineEvent.Failed(LlamaNative.loadError ?: "native library not loaded"))
            close()
            return@callbackFlow
        }
        if (session == 0L) {
            trySend(EngineEvent.Failed("no engine session — load a model first"))
            close()
            return@callbackFlow
        }

        val decoder = StreamingUtf8Decoder()
        val callback = object : LlamaNative.GenerationCallback {
            override fun onTokenBytes(bytes: ByteArray) {
                val text = decoder.feed(bytes)
                if (text.isNotEmpty()) trySend(EngineEvent.Token(text))
            }

            override fun onError(message: String) {
                trySend(EngineEvent.Failed(message))
            }
        }

        generating.set(true)
        val effective = params.forGrammar()
        val stats = try {
            withContext(dispatcher) {
                mutex.withLock {
                    LlamaNative.nativeGenerate(
                        session,
                        prompt,
                        effective.toInts(),
                        effective.toFloats(),
                        effective.grammar,
                        if (effective.stopTokens.isEmpty()) null else effective.stopTokens.toIntArray(),
                        callback,
                    )
                }
            }
        } catch (t: Throwable) {
            null
        } finally {
            generating.set(false)
        }

        val tail = decoder.flush()
        if (tail.isNotEmpty()) trySend(EngineEvent.Token(tail))

        if (stats == null) {
            val message = runCatching { LlamaNative.nativeLastError(session) }
                .getOrDefault("generation did not start")
            trySend(EngineEvent.Failed(message.ifBlank { "generation did not start" }))
        } else {
            val parsed = GenerationStats.from(stats)
            val outcome = when {
                parsed.cancelled -> GenerationOutcome.CANCELLED
                parsed.truncated -> GenerationOutcome.TRUNCATED
                else -> GenerationOutcome.COMPLETED
            }
            trySend(EngineEvent.Finished(parsed, outcome))
        }
        close()

        awaitClose { requestStop() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        requestStop()
        val session = handle.getAndSet(0L)
        if (session != 0L && LlamaNative.libraryLoaded) {
            // Best effort on the engine thread; the native side waits for the
            // in-flight decode to finish before freeing anything.
            runCatching { LlamaNative.nativeDestroySession(session) }
        }
        _loadedModelPath.value = null
        executor.shutdown()
    }
}
