package com.offlinegguf.chat.inference

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinegguf.chat.domain.model.Skill
import kotlinx.coroutines.*
import java.io.File

class LlamaEngine(private val context: Context) : ViewModel() {

    private const val TAG = "LlamaEngine"

    private var handle: Long = 0
    private var isLoaded = false
    private var currentPath: String? = null

    private var wakeLock: PowerManager.WakeLock? = null

    private val job = Job()
    private val scope = CoroutineScope(job + Dispatchers.Default)

    var onToken: ((String) -> Unit)? = null
    var onDone: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onLoadStateChanged: ((Boolean, String?) -> Unit)? = null

    fun loadModel(
        path: String,
        nCtx: Int = 1024,
        nGpuLayers: Int = 0,
        nThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2).coerceAtMost(4),
        nBatch: Int = 512,
        useMmap: Boolean = true,
        useMlock: Boolean = false,
        skills: List<Skill> = emptyList()
    ) {
        scope.launch {
            unloadModel()
            handle = LlamaNative.llamaCreateSession()
            val ok = LlamaNative.llamaLoadModel(
                handle,
                path,
                nCtx,
                nGpuLayers,
                nThreads,
                nBatch,
                useMmap,
                useMlock
            )
            if (ok) {
                isLoaded = true
                currentPath = path
                Log.i(TAG, "Model loaded: $path (n_ctx=$nCtx, threads=$nThreads, mmap=$useMmap)")
                onLoadStateChanged?.invoke(true, null)
            } else {
                val err = LlamaNative.llamaGetLastError(handle)
                Log.e(TAG, "Load failed: $err")
                unloadModel()
                onLoadStateChanged?.invoke(false, err)
            }
        }
    }

    fun unloadModel() {
        if (handle != 0L) {
            LlamaNative.llamaDestroySession(handle)
            handle = 0
        }
        isLoaded = false
        currentPath = null
        releaseWakeLock()
        onLoadStateChanged?.invoke(false, null)
    }

    fun isMultimodal(): Boolean = if (handle != 0L) LlamaNative.llamaIsMultimodal(handle) else false

    fun generate(
        prompt: String,
        nPredict: Int = 256,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        topK: Int = 40,
        repeatPenalty: Float = 1.1f,
        seed: Int = -1,
        grammar: String? = null
    ) {
        if (!isLoaded || handle == 0L) {
            onError?.invoke("Model not loaded")
            return
        }
        acquireWakeLock()

        scope.launch {
            try {
                val ok = LlamaNative.llamaGenerate(
                    handle,
                    prompt,
                    nPredict,
                    temperature,
                    topP,
                    topK,
                    repeatPenalty,
                    seed,
                    object : GenerationCallback {
                        override fun onToken(token: String) {
                            withContext(Dispatchers.Main) { onToken?.invoke(token) }
                        }
                        override fun onDone() {
                            releaseWakeLock()
                            withContext(Dispatchers.Main) { onDone?.invoke() }
                        }
                        override fun onError(error: String) {
                            releaseWakeLock()
                            withContext(Dispatchers.Main) { onError?.invoke(error) }
                        }
                    }
                )
                if (!ok) {
                    releaseWakeLock()
                    withContext(Dispatchers.Main) { onError?.invoke("Generation start failed") }
                }
            } catch (e: Exception) {
                releaseWakeLock()
                withContext(Dispatchers.Main) { onError?.invoke(e.message ?: "Unknown error") }
            }
        }
    }

    fun stop() {
        LlamaNative.llamaStop(handle)
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LlamaEngine:WakeLock")
        }
        wakeLock?.acquire(10_000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    fun getTokenCount(): Int = if (handle != 0L) LlamaNative.llamaGetTokenCount(handle) else 0

    override fun onCleared() {
        super.onCleared()
        job.cancel()
        stop()
        unloadModel()
    }
}
