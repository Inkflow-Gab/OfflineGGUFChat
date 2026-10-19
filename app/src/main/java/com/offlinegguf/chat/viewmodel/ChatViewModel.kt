package com.offlinegguf.chat.viewmodel

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.data.local.ConversationEntity
import com.offlinegguf.chat.data.local.LocalModelEntity
import com.offlinegguf.chat.data.local.MessageEntity
import com.offlinegguf.chat.data.export.buildTranscriptShare
import com.offlinegguf.chat.data.local.Roles
import com.offlinegguf.chat.data.local.SkillEntity
import com.offlinegguf.chat.data.repository.toSpec
import com.offlinegguf.chat.data.settings.AppSettings
import com.offlinegguf.chat.data.settings.ChatMode
import com.offlinegguf.chat.domain.device.DeviceProfile
import com.offlinegguf.chat.domain.fit.ModelFit
import com.offlinegguf.chat.inference.ChatTurn
import com.offlinegguf.chat.inference.EngineEvent
import com.offlinegguf.chat.inference.GenerationOutcome
import com.offlinegguf.chat.inference.GenerationStats
import com.offlinegguf.chat.inference.LoadRequest
import com.offlinegguf.chat.inference.PromptFormatter
import com.offlinegguf.chat.inference.PromptWindow
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
import kotlinx.coroutines.flow.collectLatest
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
    /** Last answer that hit its per-pass token cap and can be continued. */
    val continuableMessageId: Long? = null,
    val model: LocalModelEntity? = null,
    /** When set, generation goes to this cloud model instead of the local engine. */
    val cloudTarget: com.offlinegguf.chat.data.cloud.CloudTarget? = null,
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
    val showEngineOnboarding: Boolean
        get() = cloudTarget == null && (!engineAvailable || model == null)

    /** True when a send can actually run somewhere: local engine or cloud. */
    val canGenerate: Boolean get() = cloudTarget != null || (engineAvailable && model != null)

    val title: String get() = conversation?.title?.takeIf { it.isNotBlank() } ?: "New chat"
}

/** Mirrors the process-wide engine into this screen's independent ViewModel. */
internal fun ChatUiState.withLoadedModel(
    model: LocalModelEntity?,
    contextSize: Int = 0,
    contextUsed: Int = 0,
): ChatUiState = copy(
    model = model,
    loadingModel = false,
    contextSize = contextSize.coerceAtLeast(0),
    contextUsed = contextUsed.coerceIn(0, contextSize.coerceAtLeast(0)),
    error = if (model == null) error else null,
    fit = if (model == null) null else fit,
)

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
            _state.update { it.copy(settings = settings, cloudTarget = settings.cloudTarget()) }
            viewModelScope.launch {
                container.settings.settings.collect { latest ->
                    _state.update { it.copy(settings = latest, cloudTarget = latest.cloudTarget()) }
                }
            }

            val id = requestedConversationId
                ?: settings.lastConversationId.takeIf { last -> last > 0 && chat.conversation(last) != null }
                ?: chat.createConversation(systemPrompt = settings.systemPrompt)

            bindConversation(id)
            container.settings.setLastConversation(id)
            if (settings.autoLoadLastModel) restoreModel(settings)
        }
        drainPendingSkill()
        drainPendingPrompt()
        observeLoadedModel()
    }

    /**
     * Text another app shared into this one. The SEND filter accepts every mime
     * type, so the app is in the text share sheet too; that text becomes the draft
     * prompt rather than being dropped on the floor.
     */
    private fun drainPendingPrompt() {
        viewModelScope.launch {
            container.pendingPrompt.collect { shared ->
                if (shared.isNullOrBlank()) return@collect
                container.clearPendingPrompt()
                _state.update { current ->
                    val draft = current.input
                    val merged = if (draft.isBlank()) shared else "${draft.trimEnd()}\n\n$shared"
                    current.copy(input = merged)
                }
            }
        }
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

    /**
     * A model can be loaded from the separate Model Detail screen while this
     * ChatViewModel remains alive in the tab back stack. Previously the native
     * engine loaded successfully but this screen's local `model` stayed null,
     * leaving the "Pick a model" onboarding in place and blocking Send.
     */
    private fun observeLoadedModel() {
        viewModelScope.launch {
            var previousPath = engine.loadedPath
            engine.loadedModelPath.collectLatest { path ->
                if (path.isNullOrBlank()) {
                    val unloadedPath = previousPath
                    previousPath = null
                    if (unloadedPath != null) {
                        _state.update { current ->
                            if (current.model?.path == unloadedPath) current.withLoadedModel(null) else current
                        }
                    }
                    return@collectLatest
                }

                previousPath = path
                val model = models.byPath(path) ?: return@collectLatest
                if (!File(path).isFile) return@collectLatest
                val probe = runCatching { models.probe(File(path)) }.getOrNull()
                val report = engine.report()
                // Ignore a slow DB/header read if the user switched models meanwhile.
                if (engine.loadedPath != path || !engine.isLoaded) return@collectLatest
                val fit = probe?.let { ModelFit.assess(container.deviceProfile, it.toSpec()) }
                _state.update { current ->
                    if (engine.loadedPath != path) current
                    else current.withLoadedModel(
                        model = model,
                        contextSize = report.contextSize,
                        contextUsed = report.contextUsed,
                    ).copy(
                        fit = fit,
                        templateStyle = PromptFormatter.detect(
                            architecture = probe?.architecture ?: model.architecture,
                            chatTemplate = report.chatTemplate.ifBlank { probe?.chatTemplate.orEmpty() },
                        ),
                    )
                }
            }
        }
    }

    private fun bindConversation(conversationId: Long) {
        conversationJob?.cancel()
        messagesJob?.cancel()
        _state.update { it.copy(conversationId = conversationId, skill = null, continuableMessageId = null) }

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

    /** For failures or successes the UI hits directly, such as code-file export. */
    fun reportError(message: String) = _state.update { it.copy(error = message) }

    fun reportNotice(message: String) = _state.update { it.copy(notice = message) }

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
        val settings = _state.value.settings
        val parts = listOf(
            settings.systemPrompt.trim(),
            settings.chatMode.instruction,
            skill?.systemPrompt?.trim().orEmpty(),
        ).filter { it.isNotBlank() }
        return parts.joinToString("\n\n")
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
            val profile = container.deviceProfile
            // Re-assess saved per-model settings rather than blindly passing an
            // old context/batch value to native code. Phone capability can be
            // lower now (or the old settings may never have fit).
            val assessment = ModelFit.assess(
                profile = profile,
                spec = probe.toSpec(),
                request = ModelFit.Request(
                    context = model.nCtx.takeIf { it > 0 },
                    threads = model.threads.takeIf { it > 0 }?.coerceAtMost(profile.suggestedThreads),
                    batch = model.batch.takeIf { it > 0 }?.coerceAtMost(profile.suggestedBatch),
                    storageFreeBytes = profile.freeStorageBytes,
                ),
            )
            _state.update {
                it.copy(
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
                    context = tuned.context,
                    threads = tuned.threads,
                    batch = tuned.batch,
                    // CPU-only in this build; never let a stale GPU setting
                    // request layers that the native backend cannot provide.
                    gpuLayers = 0,
                    useMmap = true,
                ),
            )

            if (result.success) {
                models.markUsed(model.path)
                container.settings.setLastModelPath(model.path)
                chat.setModel(_state.value.conversationId, model.path, model.displayName)
                val report = result.report
                _state.update {
                    it.copy(
                        model = model,
                        loadingModel = false,
                        contextSize = if (report.contextSize > 0) report.contextSize else tuned.context,
                        contextUsed = report.contextUsed,
                        templateStyle = PromptFormatter.detect(
                            architecture = probe.architecture,
                            chatTemplate = report.chatTemplate.ifBlank { probe.chatTemplate },
                        ),
                        notice = if (!announce) null else if ((model.nCtx.takeIf { it > 0 } ?: profile.suggestedContext) > tuned.context) {
                            "Loaded ${model.displayName} with context reduced to " +
                                "${Formats.contextTokens(tuned.context.toLong())} to protect this phone's memory."
                        } else {
                            "Loaded ${model.displayName} in ${Formats.fixed(result.elapsedMs / 1000.0, 1)}s"
                        },
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
            current.canGenerate -> {
                val attachments = current.attachments
                _state.update { it.copy(input = "", attachments = emptyList()) }
                startGeneration(userText = text, attachments = attachments)
            }
            !current.engineAvailable -> _state.update {
                it.copy(
                    error = (it.engineNotice ?: "This build has no inference engine") +
                        " — or pick a free cloud model in the Cloud tab.",
                )
            }
            else -> _state.update {
                it.copy(error = "Pick a model first — a local GGUF, or a free one in the Cloud tab.")
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
            if (removed > 0 && _state.value.cloudTarget == null) {
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
            if (_state.value.cloudTarget == null) {
                engine.rewind((lastPromptTokens - lastAnswerTokens).coerceAtLeast(0))
            }
            startGeneration(userText = null, attachments = emptyList(), transcript = window.transcript)
        }
    }

    /**
     * Extend a capped answer in-place. Each pass is bounded by the model/context,
     * but the user can keep going without a hard app-level total-token ceiling.
     * The continuation instruction is added to the inference prompt only; it is
     * not inserted as a fake user message in the visible transcript.
     */
    fun continueMessage(messageId: Long) {
        val current = _state.value
        if (current.generating || current.continuableMessageId != messageId || !current.canGenerate) return
        viewModelScope.launch {
            val previous = chat.message(messageId) ?: return@launch
            if (previous.role != Roles.ASSISTANT || previous.content.isBlank()) return@launch
            val history = chat.messages(current.conversationId)
            if (history.lastOrNull()?.id != messageId) return@launch
            val promptOnlyContinuation = MessageEntity(
                conversationId = current.conversationId,
                role = Roles.USER,
                content = "Continue the answer directly from where it stopped. Do not repeat any text already given. Complete the remaining explanation or code.",
            )
            startGeneration(
                userText = null,
                attachments = emptyList(),
                transcript = history + promptOnlyContinuation,
                existingMessageId = messageId,
                previousText = previous.content,
                previousThinking = previous.thinkingText.orEmpty(),
                previousStats = GenerationStats(
                    tokensGenerated = previous.tokensGenerated,
                    promptTokens = previous.promptTokens,
                    microseconds = previous.microseconds,
                ),
            )
        }
    }

    private fun startGeneration(
        userText: String?,
        attachments: List<Attachment>,
        transcript: List<MessageEntity>? = null,
        existingMessageId: Long? = null,
        previousText: String = "",
        previousThinking: String = "",
        previousStats: GenerationStats? = null,
    ) {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            val snapshot = _state.value
            val conversationId = snapshot.conversationId
            // Exactly one of these drives the generation: a picked cloud model
            // wins over a resident GGUF (the top bar says which, and the Cloud
            // tab is where the choice is undone).
            val cloud = snapshot.cloudTarget
            val model = snapshot.model
            if (cloud == null && model == null) return@launch
            val skill = snapshot.skill

            val attachmentText = attachments
                .joinToString("\n\n") { "### ${it.name}\n${it.text}" }
                .ifBlank { null }

            if (userText != null) {
                // Compose via the shared formatter so attachments are included for
                // ordinary messages and skill templates alike (previously a null
                // template silently discarded attached file contents).
                val composed = PromptFormatter.composeUserMessage(
                    input = userText,
                    attachmentText = attachmentText,
                    template = skill?.userTemplate,
                )
                chat.appendUser(conversationId, composed, attachments.map { it.name })
            }

            val history = transcript ?: chat.messages(conversationId)
            val turns = history
                .filter { (it.role == Roles.USER || it.role == Roles.ASSISTANT) && it.content.isNotBlank() }
                .map { ChatTurn(it.role, it.content) }

            val settings = snapshot.settings
            val sampling = if (cloud != null) {
                samplingForCloud(skill, settings)
            } else {
                samplingFor(model!!, skill, settings)
            }
            val system = systemPromptFor(skill)
            val promptContext = when {
                // Cloud models advertise huge contexts; cap what we re-send so a
                // long chat cannot balloon the request (and the bill) forever.
                cloud != null -> cloud.contextLength.takeIf { it > 0 }?.coerceAtMost(65_536) ?: 32_768
                else -> snapshot.contextSize.takeIf { it > 0 }
                    ?: model?.nCtx?.takeIf { it > 0 }
                    ?: container.deviceProfile.suggestedContext
            }
            val window = PromptWindow.fit(system, turns, promptContext, sampling.maxTokens)
            val prompt = PromptFormatter.render(
                style = snapshot.templateStyle,
                system = system,
                turns = window.turns,
            )
            val windowNotice = when {
                window.droppedTurns > 0 && window.clippedLatestTurn ->
                    "Using recent turns; older chat and part of the newest turn are omitted to fit this model's context. The full transcript is saved."
                window.droppedTurns > 0 ->
                    "Using the ${window.turns.size} most recent turns to keep prompt evaluation fast. The full transcript is saved."
                window.clippedLatestTurn ->
                    "Part of the newest turn was omitted to fit this model's context. The full transcript is saved."
                else -> null
            }

            val assistantId = existingMessageId ?: chat.startAssistant(
                conversationId = conversationId,
                modelName = cloud?.label ?: model?.displayName ?: "model",
                skillId = skill?.slug,
            )
            if (existingMessageId != null) chat.resumeAssistant(existingMessageId)

            // Render short batches about 10 times a second: visibly progressive
            // instead of arriving in large 250 ms jumps, while still avoiding a
            // recomposition and Room write for every tiny token fragment.
            val coalescer = StreamCoalescer(
                flushMillis = if (settings.reduceMotion) 60L else 96L,
                flushChars = 16,
            )
            val thinkingCoalescer = StreamCoalescer(
                flushMillis = if (settings.reduceMotion) 60L else 96L,
                flushChars = 16,
            )
            val thinking = ThinkingParser()
            val answer = StringBuilder(previousText)
            val reasoning = StringBuilder(previousThinking)

            _state.update {
                it.copy(
                    generating = true,
                    streamingMessageId = assistantId,
                    streamingText = previousText,
                    streamingThinking = previousThinking,
                    continuableMessageId = null,
                    notice = windowNotice,
                    error = null,
                )
            }

            var stats = GenerationStats()
            var outcome = GenerationOutcome.COMPLETED
            var failure: String? = null
            var lastPersistNanos = System.nanoTime()

            // Both engines speak EngineEvent, so everything below — coalescing,
            // thinking split, persistence, stats — is shared verbatim.
            val events: kotlinx.coroutines.flow.Flow<EngineEvent> = if (cloud != null) {
                val apiKey = container.settings.cloudApiKeys.first()[cloud.providerId].orEmpty()
                container.cloudEngine.stream(
                    provider = cloud.provider,
                    customBaseUrl = settings.cloudCustomBaseUrl,
                    apiKey = apiKey,
                    modelId = cloud.modelId,
                    system = system,
                    turns = window.turns,
                    sampling = sampling,
                )
            } else {
                engine.stream(prompt, sampling)
            }

            events.collect { event ->
                when (event) {
                    is EngineEvent.Token -> {
                        val out = thinking.offer(event.text)
                        if (out.thinking.isNotEmpty()) {
                            reasoning.append(out.thinking)
                            if (thinkingCoalescer.offer(out.thinking) != null) {
                                _state.update {
                                    if (it.streamingMessageId == assistantId) {
                                        it.copy(streamingThinking = reasoning.toString())
                                    } else it
                                }
                            }
                        }
                        if (out.answer.isNotEmpty()) {
                            answer.append(out.answer)
                            // Repaint at the smooth UI cadence, but persist separately
                            // below. Re-querying an entire long transcript on every
                            // tiny stream update is costly on low-end phones.
                            if (coalescer.offer(out.answer) != null) {
                                _state.update {
                                    if (it.streamingMessageId == assistantId) {
                                        it.copy(streamingText = answer.toString())
                                    } else it
                                }
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
                if (event is EngineEvent.Token && System.nanoTime() - lastPersistNanos >= 500_000_000L) {
                    publishStream(assistantId, answer.toString(), reasoning.toString())
                    lastPersistNanos = System.nanoTime()
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
            val hitOutputCap = failureText == null && outcome == GenerationOutcome.COMPLETED &&
                !stats.truncated && sampling.grammar == null && stats.tokensGenerated >= sampling.maxTokens
            val storedStats = previousStats?.let { previous ->
                stats.copy(
                    tokensGenerated = previous.tokensGenerated + stats.tokensGenerated,
                    microseconds = previous.microseconds + stats.microseconds,
                    truncated = previous.truncated || stats.truncated,
                    cancelled = previous.cancelled || stats.cancelled,
                )
            } ?: stats
            if (failureText != null) {
                if (existingMessageId != null) {
                    // Preserve the already-generated answer and any continuation
                    // text if a later extension pass fails.
                    chat.finishMessage(assistantId, GenerationOutcome.COMPLETED, storedStats)
                } else {
                    chat.failMessage(assistantId, failureText)
                }
                _state.update { it.copy(error = failureText) }
            } else {
                chat.finishMessage(assistantId, outcome, storedStats)
            }

            // A continuation changes the effective prompt tail, so future edits
            // safely rebuild the KV cache instead of rewinding to stale token counts.
            lastPromptTokens = if (existingMessageId != null) 0 else stats.promptTokens
            lastAnswerTokens = if (existingMessageId != null) 0 else stats.tokensGenerated

            _state.update {
                it.copy(
                    generating = false,
                    streamingMessageId = -1L,
                    streamingText = "",
                    streamingThinking = "",
                    continuableMessageId = if (hitOutputCap) assistantId else null,
                    lastSpeed = stats.tokensPerSecond,
                    contextUsed = stats.contextUsed.takeIf { used -> used > 0 }
                        ?: it.contextUsed,
                    notice = when {
                        failureText != null -> null
                        stats.truncated || outcome == GenerationOutcome.TRUNCATED ->
                            "The transcript reached its context limit; older turns were dropped."
                        outcome == GenerationOutcome.CANCELLED -> "Stopped after ${stats.tokensGenerated} tokens."
                        hitOutputCap -> "Response chunk complete. Tap the play icon under the answer to continue; the model context still sets the limit."
                        else -> windowNotice
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
        // Modes affect actual decoding as well as the system instruction. Skills
        // remain authoritative for their output shape; grammar gets the final say.
        val tuned = if (skill != null) params else when (settings.chatMode) {
            ChatMode.CAREFUL, ChatMode.CODING -> params.copy(temperature = minOf(params.temperature, 0.3f))
            ChatMode.FAST -> params.copy(
                maxTokens = minOf(params.maxTokens, 320),
                temperature = minOf(params.temperature, 0.6f),
            )
            ChatMode.BALANCED, ChatMode.CHAT -> params
        }
        return if (tuned.grammar != null) tuned.forGrammar() else tuned
    }

    /**
     * Cloud sampling: the global defaults with skill overrides and the same
     * chat-mode tuning as local generation. Per-GGUF knobs (KV type, GBNF
     * grammar, repeat penalty) do not exist in the OpenAI dialect and are
     * simply not sent.
     */
    private fun samplingForCloud(
        skill: SkillEntity?,
        settings: AppSettings,
    ): SamplingParams {
        val params = SamplingParams(
            maxTokens = skill?.maxTokens?.takeIf { it > 0 } ?: settings.maxTokens,
            temperature = skill?.temperature ?: settings.temperature,
            topP = skill?.topP ?: settings.topP,
            topK = settings.topK,
            minP = settings.minP,
            repeatPenalty = settings.repeatPenalty,
        )
        return if (skill != null) params else when (settings.chatMode) {
            ChatMode.CAREFUL, ChatMode.CODING -> params.copy(temperature = minOf(params.temperature, 0.3f))
            ChatMode.FAST -> params.copy(
                maxTokens = minOf(params.maxTokens, 320),
                temperature = minOf(params.temperature, 0.6f),
            )
            ChatMode.BALANCED, ChatMode.CHAT -> params
        }
    }

    fun stop() {
        engine.requestStop()
        generationJob?.cancel()
        val streamingId = _state.value.streamingMessageId
        if (streamingId > 0) {
            viewModelScope.launch {
                val saved = chat.message(streamingId)
                chat.finishMessage(
                    streamingId,
                    GenerationOutcome.CANCELLED,
                    GenerationStats(
                        tokensGenerated = saved?.tokensGenerated ?: 0,
                        promptTokens = saved?.promptTokens ?: 0,
                        microseconds = saved?.microseconds ?: 0L,
                        cancelled = true,
                    ),
                )
                _state.update {
                    it.copy(
                        generating = false,
                        streamingMessageId = -1L,
                        streamingText = "",
                        streamingThinking = "",
                        continuableMessageId = null,
                    )
                }
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

    /**
     * Both exports build the transcript and write the shared file off the main
     * thread, then hand back a ready-to-launch chooser intent. A null intent means
     * the file could not be written, which the screen reports.
     */
    fun exportMarkdown(onReady: (Intent?) -> Unit) =
        export("md", "text/markdown", { id -> chat.exportMarkdown(id) }, onReady)

    fun exportJson(onReady: (Intent?) -> Unit) =
        export("json", "application/json", { id -> chat.exportJson(id) }, onReady)

    private fun export(
        extension: String,
        mime: String,
        render: suspend (Long) -> String,
        onReady: (Intent?) -> Unit,
    ) {
        viewModelScope.launch {
            val conversationId = _state.value.conversationId
            val title = _state.value.title
            val intent = withContext(Dispatchers.IO) {
                buildTranscriptShare(
                    context = container.applicationContext,
                    text = render(conversationId),
                    title = title,
                    extension = extension,
                    mime = mime,
                )
            }
            onReady(intent)
        }
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
