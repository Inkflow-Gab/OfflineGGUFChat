package com.offlinegguf.chat.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.data.local.ConversationEntity
import com.offlinegguf.chat.data.local.LocalModelEntity
import com.offlinegguf.chat.data.local.MessageEntity
import com.offlinegguf.chat.data.local.Roles
import com.offlinegguf.chat.data.local.SkillEntity
import com.offlinegguf.chat.data.repository.toSpec
import com.offlinegguf.chat.data.settings.AppSettings
import com.offlinegguf.chat.domain.device.DeviceProfile
import com.offlinegguf.chat.domain.fit.ModelFit
import com.offlinegguf.chat.domain.skills.BuiltinSkills
import com.offlinegguf.chat.inference.ChatTurn
import com.offlinegguf.chat.inference.EngineEvent
import com.offlinegguf.chat.inference.GenerationOutcome
import com.offlinegguf.chat.inference.GenerationStats
import com.offlinegguf.chat.inference.LoadRequest
import com.offlinegguf.chat.inference.PromptFormatter
import com.offlinegguf.chat.inference.SamplingParams
import com.offlinegguf.chat.inference.StreamCoalescer
import com.offlinegguf.chat.inference.TemplateStyle
import com.offlinegguf.chat.inference.ThinkingParser
import com.offlinegguf.chat.util.Formats
import com.offlinegguf.chat.util.MarkdownBlocks
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A text file attached to the next message. */
data class Attachment(val name: String, val text: String, val bytes: Long)

data class ChatUiState(
    val conversationId: Long = -1L,
    val conversation: ConversationEntity? = null,
    val messages: List<MessageEntity> = emptyList(),
    val input: String = "",
    val generating: Boolean = false,
    val streamingMessageId: Long = -1L,
    val streamingText: String = "",
    val streamingThinking: String = "",
    val model: LocalModelEntity? = null,
    val engineAvailable: Boolean = false,
    val engineNotice: String? = null,
    val engineVersion: String = "",
    val loadingModel: Boolean = false,
    val contextUsed: Int = 0,
    val contextSize: Int = 0,
    val skill: SkillEntity? = null,
    val attachments: List<Attachment> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
    val lastSpeed: Double = 0.0,
    val likes: Int = 0,
    val settings: AppSettings = AppSettings(),
    val deviceProfile: DeviceProfile? = null,
    val fit: ModelFit.Assessment? = null,
    val templateStyle: TemplateStyle = TemplateStyle.GENERIC,
    val editingMessageId: Long? = null,
) {
    /** Shown instead of the transcript when there is nothing to talk to yet. */
    val showEngineOnboarding: Boolean get() = !engineAvailable || model == null

    val title: String get() = conversation?.title?.takeIf { it.isNotBlank() } ?: "New chat"
}

/**
 * Drives the chat screen: transcript, generation, feedback and context hygiene.
 *
 * A generation is one [Job] in [viewModelScope]. Bytes arrive over JNI as raw
 * UTF-8, the engine reframes them into characters, [StreamCoalescer] batches them
 * so the UI and the database update a few times a second instead of twenty,
 * [ThinkingParser] splits reasoning from the answer, and the row is written with
 * column-scoped updates so a long answer stays O(n) rather than O(n²).
 */
class ChatViewModel(
    private val container: AppContainer,
    private val requestedConversationId: Long?,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val chat = container.chatRepository
    private val models = container.modelRepository
    private val engine = container.engine

    private var generationJob: Job? = null
    private var conversationJob: Job? = null
    private var messagesJob: Job? = null

    /** Prompt tokens of the last generation, used to keep the KV prefix on retry. */
    private var lastPromptTokens = 0
    private var lastAnswerTokens = 0

    init {
        _state.update {
            it.copy(
                engineAvailable = engine.isAvailable,
                engineNotice = engine.unavailableReason,
                engineVersion = engine.version,
                deviceProfile = container.deviceProfile,
            )
        }
        viewModelScope.launch {
            val settings = container.settings.settings.first()
            _state.update { it.copy(settings = settings) }

            val id = requestedConversationId
                ?: settings.lastConversationId.takeIf { last -> last > 0 && chat.conversation(last) != null }
                ?: chat.createConversation(systemPrompt = settings.systemPrompt)

            bindConversation(id)
            container.settings.setLastConversation(id)
            if (settings.autoLoadLastModel) restoreModel(settings)
        }
        drainPendingSkill()
    }

    /** A skill chosen on another screen; applied here and then cleared. */
    private fun drainPendingSkill() {
        viewModelScope.launch {
            container.pendingSkill.collect { slug ->
                if (slug == null) return@collect
                container.clearPendingSkill()
                val skill = container.skillRepository.bySlug(slug)
                if (skill != null) selectSkill(skill)
            }
        }
    }

    private fun bindConversation(conversationId: Long) {
        conversationJob?.cancel()
        messagesJob?.cancel()
        _state.update { it.copy(conversationId = conversationId, skill = null) }

        messagesJob = viewModelScope.launch {
            chat.observeMessages(conversationId).collect { rows ->
                _state.update { current ->
                    current.copy(messages = rows, likes = rows.sumOf { it.likeCount })
                }
            }
        }
        conversationJob = viewModelScope.launch {
            chat.observeConversation(conversationId).collect { conversation ->
                _state.update { current -> current.copy(conversation = conversation) }
                val skillId = conversation?.skillId ?: return@collect
                if (_state.value.skill?.slug != skillId) {
                    val skill = container.skillRepository.bySlug(skillId)
                    if (skill != null) _state.update { it.copy(skill = skill) }
                }
            }
        }
    }

    private suspend fun restoreModel(settings: AppSettings) {
        val path = settings.lastModelPath ?: return
        val model = models.byPath(path) ?: return
        if (!File(path).exists()) return
        loadModel(model, announce = false)
    }

    // ------------------------------------------------------------- composer

    fun onInputChange(value: String) = _state.update { it.copy(input = value) }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun startEditing(messageId: Long, text: String) =
        _state.update { it.copy(editingMessageId = messageId, input = text) }

    fun cancelEditing() = _state.update { it.copy(editingMessageId = null, input = "") }

    fun selectSkill(skill: SkillEntity?) {
        _state.update { it.copy(skill = skill) }
        viewModelScope.launch {
            chat.setPrompt(_state.value.conversationId, systemPromptFor(skill), skill?.slug)
        }
    }

    private fun systemPromptFor(skill: SkillEntity?): String {
        val base = _state.value.settings.systemPrompt.trim()
        val extra = skill?.systemPrompt?.trim().orEmpty()
        return when {
            extra.isEmpty() -> base
            base.isEmpty() -> extra
            else -> "$base\n\n$extra"
        }
    }

    // ---------------------------------------------------------------- model

    fun loadModel(model: LocalModelEntity, announce: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(loadingModel = true, error = null) }

            val file = File(model.path)
            if (!file.exists()) {
                _state.update { it.copy(loadingModel = false, error = "That file is gone: ${model.fileName}") }
                return@launch
            }

            val probe = models.probe(file)
            val assessment = ModelFit.assess(container.deviceProfile, probe.toSpec())
            _state.update {
                it.copy(
                    model = model,
                    fit = assessment,
                    templateStyle = PromptFormatter.detect(probe.architecture, probe.chatTemplate),
                )
            }

            if (!assessment.usable) {
                _state.update {
                    it.copy(
                        loadingModel = false,
                        error = assessment.warnings.firstOrNull() ?: "This model will not fit on this device.",
                    )
                }
                return@launch
            }
            if (!engine.isAvailable) {
                _state.update {
                    it.copy(loadingModel = false, engineNotice = engine.unavailableReason ?: "No engine in this build")
                }
                return@launch
            }

            val tuned = assessment.settings
            val result = engine.load(
                LoadRequest(
                    path = model.path,
                    context = if (model.nCtx > 0) model.nCtx else tuned.context,
                    threads = if (model.threads > 0) model.threads else tuned.threads,
                    batch = if (model.batch > 0) model.batch else tuned.batch,
                    gpuLayers = model.gpuLayers,
                    useMmap = model.useMmap,
                ),
            )

            if (result.success) {
                models.markUsed(model.path)
                container.settings.setLastModelPath(model.path)
                chat.setModel(_state.value.conversationId, model.path, model.displayName)
                val report = result.report
                _state.update {
                    it.copy(
                        loadingModel = false,
                        contextSize = if (report.contextSize > 0) report.contextSize else tuned.context,
                        contextUsed = report.contextUsed,
                        templateStyle = PromptFormatter.detect(
                            architecture = probe.architecture,
                            chatTemplate = report.chatTemplate.ifBlank { probe.chatTemplate },
                        ),
                        notice = if (announce) {
                            "Loaded ${model.displayName} in ${Formats.fixed(result.elapsedMs / 1000.0, 1)}s"
                        } else null,
                    )
                }
            } else {
                _state.update {
                    it.copy(loadingModel = false, error = result.message ?: "The model failed to load")
                }
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            engine.unload()
            _state.update {
                it.copy(model = null, contextSize = 0, contextUsed = 0, notice = "Model unloaded")
            }
        }
    }

    fun refreshContext() {
        viewModelScope.launch {
            _state.update { it.copy(contextUsed = engine.contextUsed(), contextSize = engine.contextSize()) }
        }
    }

    fun clearContext() {
        viewModelScope.launch {
            engine.rewind(0)
            _state.update { it.copy(contextUsed = 0, notice = "Context cleared") }
        }
    }

    // ----------------------------------------------------------- generation

    fun send() {
        val current = _state.value
        val text = current.input.trim()
        if (text.isEmpty() || current.generating) return

        val editing = current.editingMessageId
        if (editing != null) {
            resendEdit(editing, text)
            return
        }
        when {
            !current.engineAvailable -> _state.update {
                it.copy(error = it.engineNotice ?: "This build has no inference engine")
            }
            current.model == null -> _state.update { it.copy(error = "Pick a model first") }
            else -> {
                val attachments = current.attachments
                _state.update { it.copy(input = "", attachments = emptyList()) }
                startGeneration(userText = text, attachments = attachments)
            }
        }
    }

    /**
     * "Edit and resend": the transcript is cut from the edited message, the KV
     * cache is rewound to the prefix that is still valid, then the edit is sent.
     */
    private fun resendEdit(messageId: Long, text: String) {
        _state.update { it.copy(input = "", editingMessageId = null) }
        viewModelScope.launch {
            val removed = chat.deleteFrom(messageId)
            if (removed > 0) {
                engine.rewind((lastPromptTokens - lastAnswerTokens).coerceAtLeast(0))
                _state.update { it.copy(contextUsed = engine.contextUsed()) }
            }
            startGeneration(userText = text, attachments = emptyList())
        }
    }

    fun regenerate() {
        if (_state.value.generating) return
        viewModelScope.launch {
            val window = chat.prepareRegenerate(_state.value.conversationId)
            engine.rewind((lastPromptTokens - lastAnswerTokens).coerceAtLeast(0))
            startGeneration(userText = null, attachments = emptyList(), transcript = window.transcript)
        }
    }

    private fun startGeneration(
        userText: String?,
        attachments: List<Attachment>,
        transcript: List<MessageEntity>? = null,
    ) {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            val snapshot = _state.value
            val conversationId = snapshot.conversationId
            val model = snapshot.model ?: return@launch
            val skill = snapshot.skill

            val attachmentText = attachments
                .joinToString("\n\n") { "### ${it.name}\n${it.text}" }
                .ifBlank { null }

            if (userText != null) {
                val composed = BuiltinSkills.expand(skill?.userTemplate, userText, attachmentText.orEmpty())
                chat.appendUser(conversationId, composed, attachments.map { it.name })
            }

            val history = transcript ?: chat.messages(conversationId)
            val turns = history
                .filter { (it.role == Roles.USER || it.role == Roles.ASSISTANT) && it.content.isNotBlank() }
                .map { ChatTurn(it.role, it.content) }

            val prompt = PromptFormatter.render(
                style = snapshot.templateStyle,
                system = systemPromptFor(skill),
                turns = turns,
            )

            val assistantId = chat.startAssistant(
                conversationId = conversationId,
                modelName = model.displayName,
                skillId = skill?.slug,
            )

            val settings = snapshot.settings
            val coalescer = StreamCoalescer(
                flushMillis = if (settings.reduceMotion) 120L else 256L,
                flushChars = 48,
            )
            val thinking = ThinkingParser()
            val answer = StringBuilder()
            val reasoning = StringBuilder()

            _state.update {
                it.copy(
                    generating = true,
                    streamingMessageId = assistantId,
                    streamingText = "",
                    streamingThinking = "",
                    error = null,
                )
            }

            var stats = GenerationStats()
            var outcome = GenerationOutcome.COMPLETED
            var failure: String? = null

            engine.stream(prompt, samplingFor(model, skill, settings)).collect { event ->
                when (event) {
                    is EngineEvent.Token -> {
                        val out = thinking.offer(event.text)
                        if (out.thinking.isNotEmpty()) reasoning.append(out.thinking)
                        if (out.answer.isNotEmpty()) {
                            answer.append(out.answer)
                            // Persist and repaint at the coalescer's cadence, not per token.
                            if (coalescer.offer(out.answer) != null) {
                                publishStream(assistantId, answer.toString(), reasoning.toString())
                            }
                        }
                    }

                    is EngineEvent.Finished -> {
                        stats = event.stats
                        outcome = event.outcome
                    }

                    is EngineEvent.Failed -> {
                        failure = event.message
                        outcome = GenerationOutcome.FAILED
                    }
                }
            }

            // Whatever the parser was still holding back is definitely not a marker.
            val tail = thinking.finish()
            reasoning.append(tail.thinking)
            answer.append(tail.answer)

            // Models often keep going past their own end token; cut it there.
            val finalText = PromptFormatter.trimAtEndMarker(snapshot.templateStyle, answer.toString()).trim()
            publishStream(assistantId, finalText, reasoning.toString().trim())

            // `failure` is mutated from inside the collector below, so the compiler
            // will not smart-cast it; copy it into a stable local first.
            val failureText = failure
            if (failureText != null) {
                chat.failMessage(assistantId, failureText)
                _state.update { it.copy(error = failureText) }
            } else {
                chat.finishMessage(assistantId, outcome, stats)
            }

            lastPromptTokens = stats.promptTokens
            lastAnswerTokens = stats.tokensGenerated

            _state.update {
                it.copy(
                    generating = false,
                    streamingMessageId = -1L,
                    streamingText = "",
                    streamingThinking = "",
                    lastSpeed = stats.tokensPerSecond,
                    contextUsed = stats.contextUsed.takeIf { used -> used > 0 }
                        ?: it.contextUsed,
                    notice = when {
                        failure != null -> null
                        stats.truncated || outcome == GenerationOutcome.TRUNCATED ->
                            "The transcript outgrew the context; older turns were dropped."
                        outcome == GenerationOutcome.CANCELLED -> "Stopped after ${stats.tokensGenerated} tokens."
                        else -> null
                    },
                )
            }
        }
    }

    private suspend fun publishStream(messageId: Long, answer: String, reasoning: String) =
        withContext(Dispatchers.IO) {
            chat.appendStream(messageId, answer, reasoning.ifBlank { null })
            _state.update { it.copy(streamingText = answer, streamingThinking = reasoning) }
        }

    private fun samplingFor(
        model: LocalModelEntity,
        skill: SkillEntity?,
        settings: AppSettings,
    ): SamplingParams {
        val params = SamplingParams(
            maxTokens = skill?.maxTokens?.takeIf { it > 0 }
                ?: model.maxTokens.takeIf { it > 0 }
                ?: settings.maxTokens,
            temperature = skill?.temperature ?: model.temperature,
            topP = skill?.topP ?: model.topP,
            topK = model.topK,
            minP = model.minP,
            repeatPenalty = model.repeatPenalty,
            repeatLastN = model.repeatLastN,
            seed = model.seed,
            grammar = skill?.grammar?.takeIf { it.isNotBlank() },
        )
        // Grammar-constrained decoding needs greedy sampling or it fights itself.
        return if (params.grammar != null) params.forGrammar() else params
    }

    fun stop() {
        engine.requestStop()
        generationJob?.cancel()
        val streamingId = _state.value.streamingMessageId
        if (streamingId > 0) {
            viewModelScope.launch {
                chat.finishMessage(streamingId, GenerationOutcome.CANCELLED, GenerationStats())
                _state.update { it.copy(generating = false, streamingMessageId = -1L) }
            }
        } else {
            _state.update { it.copy(generating = false) }
        }
    }

    // ------------------------------------------------------------- feedback

    fun toggleLike(messageId: Long, liked: Boolean) {
        viewModelScope.launch {
            val total = chat.setLiked(messageId, liked)
            _state.update { it.copy(likes = total) }
        }
    }

    fun toggleFlag(messageId: Long, flagged: Boolean) {
        viewModelScope.launch { chat.setFlagged(messageId, flagged) }
    }

    fun deleteFrom(messageId: Long) {
        viewModelScope.launch {
            val removed = chat.deleteFrom(messageId)
            engine.rewind(0)
            _state.update {
                it.copy(
                    contextUsed = 0,
                    notice = "$removed message${if (removed == 1) "" else "s"} removed, context reset",
                )
            }
        }
    }

    fun deleteMessage(messageId: Long) {
        viewModelScope.launch { chat.deleteMessage(messageId) }
    }

    fun newConversation() {
        viewModelScope.launch {
            val id = chat.createConversation(systemPrompt = _state.value.settings.systemPrompt)
            container.settings.setLastConversation(id)
            bindConversation(id)
            engine.rewind(0)
            _state.update { it.copy(contextUsed = 0, notice = "New chat") }
        }
    }

    fun renameConversation(title: String) {
        viewModelScope.launch { chat.rename(_state.value.conversationId, title) }
    }

    // ---------------------------------------------------------- attachments

    private sealed class ReadResult {
        data class Ok(val text: String, val bytes: Long) : ReadResult()
        object Binary : ReadResult()
        data class TooBig(val bytes: Long) : ReadResult()
        data class Failed(val message: String) : ReadResult()
    }

    fun attach(uri: Uri, nameHint: String? = null) {
        viewModelScope.launch {
            val name = models.displayName(uri) ?: nameHint ?: "attachment.txt"
            when (val result = readText(uri)) {
                is ReadResult.Ok -> _state.update {
                    it.copy(attachments = it.attachments + Attachment(name, result.text, result.bytes))
                }

                ReadResult.Binary -> _state.update {
                    it.copy(
                        notice = "Offline means text only — attach code, markdown, JSON or CSV. " +
                            "Images need a multimodal model and a projector file.",
                    )
                }

                is ReadResult.TooBig -> _state.update {
                    it.copy(
                        error = "${Formats.bytes(result.bytes)} is over the " +
                            "${Formats.bytes(MAX_ATTACHMENT_BYTES)} attachment limit.",
                    )
                }

                is ReadResult.Failed -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun removeAttachment(name: String) = _state.update { current ->
        current.copy(attachments = current.attachments.filterNot { it.name == name })
    }

    private suspend fun readText(uri: Uri): ReadResult = withContext(Dispatchers.IO) {
        runCatching {
            val input = container.contentResolver.openInputStream(uri)
                ?: return@runCatching ReadResult.Failed("Could not open that file")
            input.use { stream ->
                val limit = (MAX_ATTACHMENT_BYTES + 1).toInt()
                val buffer = ByteArray(limit)
                var total = 0
                while (total < limit) {
                    val read = stream.read(buffer, total, limit - total)
                    if (read < 0) break
                    total += read
                }
                when {
                    total > MAX_ATTACHMENT_BYTES -> ReadResult.TooBig(total.toLong())
                    looksBinary(buffer, total) -> ReadResult.Binary
                    else -> ReadResult.Ok(String(buffer, 0, total, Charsets.UTF_8), total.toLong())
                }
            }
        }.getOrElse { ReadResult.Failed(it.message ?: "Could not read that file") }
    }

    // ---------------------------------------------------------------- export

    fun exportMarkdown(onResult: (String) -> Unit) {
        viewModelScope.launch { onResult(chat.exportMarkdown(_state.value.conversationId)) }
    }

    fun exportJson(onResult: (String) -> Unit) {
        viewModelScope.launch { onResult(chat.exportJson(_state.value.conversationId)) }
    }

    /**
     * The previous answer's code blocks, by index.
     *
     * A model that re-emits a file it already wrote gets its block diffed against
     * the earlier revision, which is what makes added/removed lines possible.
     */
    fun previousCodeFor(messageId: Long): Map<Int, String> {
        val rows = _state.value.messages
        val index = rows.indexOfFirst { it.id == messageId }
        if (index <= 0) return emptyMap()
        val earlier = rows.subList(0, index).lastOrNull { it.role == Roles.ASSISTANT } ?: return emptyMap()
        return MarkdownBlocks.codeBlocks(earlier.content)
            .mapIndexed { position, block -> position to block.content }
            .toMap()
    }

    override fun onCleared() {
        generationJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** ~200 KB: generous for source, small enough to keep in the prompt. */
        const val MAX_ATTACHMENT_BYTES = 200_000L

        /** NUL bytes in the first 4 KB mean it is not text we should inline. */
        fun looksBinary(bytes: ByteArray, count: Int = bytes.size): Boolean {
            val limit = minOf(count, 4096)
            for (i in 0 until limit) if (bytes[i].toInt() == 0) return true
            return false
        }

        fun factory(container: AppContainer, conversationId: Long?) = viewModelFactory {
            initializer { ChatViewModel(container, conversationId) }
        }
    }
}
