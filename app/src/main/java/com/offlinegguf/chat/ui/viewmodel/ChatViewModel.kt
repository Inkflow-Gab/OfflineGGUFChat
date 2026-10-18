package com.offlinegguf.chat.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinegguf.chat.data.repository.ChatRepository
import com.offlinegguf.chat.data.local.entity.Message
import com.offlinegguf.chat.domain.model.SamplingParams
import com.offlinegguf.chat.domain.model.Skill
import com.offlinegguf.chat.domain.model.SkillType
import com.offlinegguf.chat.inference.LlamaEngine
import com.offlinegguf.chat.utils.SkillsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.milliseconds

class ChatViewModel(
    private val context: Context,
    private val repository: ChatRepository,
    private val conversationId: String
) : ViewModel() {

    private val engine = LlamaEngine(context)

    val messages = repository.getMessages(conversationId)
    val isLoading = MutableStateFlow(false)
    val modelStatus = MutableStateFlow("Idle")
    val error = MutableStateFlow<String?>(null)

    var samplingParams by mutableStateOf(SamplingParams())
    var currentModelPath: String? = null
        private set
    var activeSkills by mutableStateOf(emptyList<Skill>())

    val tokenCount = MutableStateFlow(0)
    val generationSpeed = MutableStateFlow("0 tok/s")
    val contextUsage = MutableStateFlow(0.0f)

    private var generationStart: TimeMark? = null
    private var generatedTokens: Int = 0

    init {
        engine.onLoadStateChanged = { loaded, err ->
            modelStatus.value = if (loaded) "Loaded" else "Error: $err"
            error.value = err
            isLoading.value = false
            viewModelScope.launch { refreshContextUsage() }
        }
        engine.onToken = { token ->
            viewModelScope.launch {
                repository.appendStreamingToken(conversationId, token)
                generatedTokens++
                generationStart?.let { start ->
                    val elapsed = (TimeSource.monotonic.markNow() - start).inMilliseconds
                    if (elapsed > 0) {
                        val speed = (generatedTokens.toFloat() / elapsed) * 1000f
                        generationSpeed.value = "${speed.toInt()} tok/s"
                    }
                }
                tokenCount.value = engine.getTokenCount()
                refreshContextUsageSuspend()
            }
        }
        engine.onDone = {
            viewModelScope.launch {
                repository.finalizeStreaming(conversationId)
                isLoading.value = false
                modelStatus.value = "Ready"
                tokenCount.value = engine.getTokenCount()
            }
        }
        engine.onError = { err ->
            error.value = err
            isLoading.value = false
            modelStatus.value = "Error"
        }
    }

    fun loadModel(path: String, params: SamplingParams, skills: List<Skill> = emptyList()) {
        viewModelScope.launch {
            isLoading.value = true
            currentModelPath = path
            samplingParams = params
            activeSkills = skills
            val grammar = skills
                .filter { it.type == SkillType.GRAMMAR && it.enabled }
                .mapNotNull { it.content }
                .firstOrNull()
            engine.loadModel(
                path = path,
                nCtx = params.nCtx,
                nGpuLayers = params.nGpuLayers,
                nThreads = params.nThreads,
                nBatch = params.nBatch,
                useMmap = params.useMmap,
                useMlock = params.useMlock,
                skills = skills
            )
        }
    }

    fun sendMessage(text: String, imagePaths: List<String> = emptyList(), filePaths: List<String> = emptyList()) {
        viewModelScope.launch {
            val userMsg = Message(
                conversationId = conversationId,
                role = "user",
                content = text,
                images = if (imagePaths.isEmpty()) null else imagePaths.toString(),
                attachments = if (filePaths.isEmpty()) null else filePaths.toString()
            )
            repository.addMessage(userMsg)

            val prompt = buildPrompt(text, imagePaths, filePaths)

            generatedTokens = 0
            generationStart = TimeSource.monotonic.markNow()
            tokenCount.value = 0
            generationSpeed.value = "0 tok/s"

            isLoading.value = true
            val grammar = activeSkills
                .filter { it.type == SkillType.GRAMMAR && it.enabled }
                .mapNotNull { it.content }
                .firstOrNull()
            engine.generate(
                prompt = prompt,
                nPredict = 512,
                temperature = samplingParams.temperature,
                topP = samplingParams.topP,
                topK = samplingParams.topK,
                repeatPenalty = samplingParams.repeatPenalty,
                seed = samplingParams.seed,
                grammar = grammar
            )
        }
    }

    private fun buildPrompt(text: String, images: List<String>, files: List<String>): String {
        val sb = StringBuilder()
        activeSkills.filter { it.type == SkillType.SYSTEM && it.enabled }
            .mapNotNull { it.content }
            .firstOrNull()?.let { systemPrompt ->
                sb.append("System: ").append(systemPrompt).append("\n")
            }
        sb.append(text)
        if (images.isNotEmpty()) {
            sb.append("\n[Attached images: ").append(images.joinToString(", ")).append("]")
        }
        if (files.isNotEmpty()) {
            sb.append("\n[Attached files: ").append(files.joinToString(", ")).append("]")
        }
        sb.append("\nAssistant: ")
        return sb.toString()
    }

    fun stopGeneration() {
        engine.stop()
        isLoading.value = false
        modelStatus.value = "Stopped"
    }

    fun isMultimodal(): Boolean = engine.isMultimodal()

    private suspend fun refreshContextUsage() {
        val msgList = repository.getMessages(conversationId).first()
        val total = msgList.sumOf { it.tokenCount }
        val ctx = samplingParams.nCtx
        contextUsage.value = if (ctx > 0) total.toFloat() / ctx else 0f
    }

    private suspend fun refreshContextUsageSuspend() {
        val msgList = repository.getMessages(conversationId).first()
        val total = msgList.sumOf { it.tokenCount }
        val ctx = samplingParams.nCtx
        contextUsage.value = if (ctx > 0) total.toFloat() / ctx else 0f
    }

    override fun onCleared() {
        super.onCleared()
        engine.stop()
    }
}
