package com.offlinegguf.chat.data.cloud

import com.offlinegguf.chat.inference.ChatTurn
import com.offlinegguf.chat.inference.EngineEvent
import com.offlinegguf.chat.inference.GenerationOutcome
import com.offlinegguf.chat.inference.GenerationStats
import com.offlinegguf.chat.inference.SamplingParams
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI-compatible chat client on plain [HttpURLConnection].
 *
 * Deliberately no HTTP library: the app ships no third-party networking code,
 * and a streamed POST + line reader is all this needs. Output is the same
 * [EngineEvent] flow the native engine produces, so the chat screen's
 * coalescing, thinking-splitting and persistence are shared verbatim.
 * Reasoning deltas are wrapped in `<think>…</think>` per chunk, which the
 * existing ThinkingParser routes into the reasoning channel.
 *
 * Cancelling the collecting coroutine disconnects the socket (see [awaitClose]),
 * so the Stop button works mid-stream.
 */
class CloudChatEngine {

    /** GET `/models`. Never throws: failures come back as a failed [Result]. */
    suspend fun fetchModels(
        provider: CloudProvider,
        customBaseUrl: String,
        apiKey: String,
    ): Result<List<CloudModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = provider.resolveBaseUrl(customBaseUrl)
            require(base.isNotBlank()) { "Set the server URL first" }
            val connection = open("$base/models", apiKey, provider)
            try {
                val code = connection.responseCode
                val body = readBody(connection, code)
                if (code !in 200..299) error(CloudModels.parseError(body, code))
                CloudModels.parseModels(body, provider.id).also {
                    if (it.isEmpty()) error("The server answered, but listed no models")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Stream one chat completion. [turns] are the already-windowed conversation
     * (oldest first); [system] is prepended as the system message when non-blank.
     */
    fun stream(
        provider: CloudProvider,
        customBaseUrl: String,
        apiKey: String,
        modelId: String,
        system: String,
        turns: List<ChatTurn>,
        sampling: SamplingParams,
    ): Flow<EngineEvent> = callbackFlow {
        val connectionRef = AtomicReference<HttpURLConnection?>(null)
        val job = CoroutineScope(Dispatchers.IO).launch {
            val started = System.nanoTime()
            var answerChars = 0
            var promptTokens = 0
            var completionTokens = 0
            try {
                val base = provider.resolveBaseUrl(customBaseUrl)
                require(base.isNotBlank()) { "Set the server URL in the Cloud tab first" }
                if (provider.requiresKey && apiKey.isBlank()) {
                    error("Add your ${provider.label} API key in the Cloud tab first")
                }
                val connection = open("$base/chat/completions", apiKey, provider).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Accept", "text/event-stream")
                }
                connectionRef.set(connection)
                connection.outputStream.use { output ->
                    output.write(requestBody(modelId, system, turns, sampling).toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    trySend(EngineEvent.Failed(CloudModels.parseError(readBody(connection, code), code)))
                    close()
                    return@launch
                }
                connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("data:")) continue
                        val delta = CloudModels.parseChunk(line.removePrefix("data:")) ?: continue
                        if (delta.promptTokens > 0) promptTokens = delta.promptTokens
                        if (delta.completionTokens > 0) completionTokens = delta.completionTokens
                        if (delta.reasoning.isNotEmpty()) {
                            trySend(EngineEvent.Token("<think>${delta.reasoning}</think>"))
                        }
                        if (delta.content.isNotEmpty()) {
                            answerChars += delta.content.length
                            trySend(EngineEvent.Token(delta.content))
                        }
                    }
                }
                val elapsedMicros = (System.nanoTime() - started) / 1_000
                trySend(
                    EngineEvent.Finished(
                        stats = GenerationStats(
                            // Hosts that omit usage get a chars/4 estimate — close
                            // enough for the speed readout, never shown as billing.
                            tokensGenerated = completionTokens.takeIf { it > 0 } ?: (answerChars / 4),
                            promptTokens = promptTokens,
                            microseconds = elapsedMicros,
                        ),
                        outcome = GenerationOutcome.COMPLETED,
                    ),
                )
            } catch (stopped: kotlinx.coroutines.CancellationException) {
                throw stopped
            } catch (failure: Exception) {
                // A disconnect from awaitClose (the Stop button) also surfaces
                // here as an IOException; only report when someone is listening.
                if (!isClosedForSend) {
                    trySend(EngineEvent.Failed(friendlyError(failure)))
                }
            } finally {
                runCatching { connectionRef.get()?.disconnect() }
                close()
            }
        }
        awaitClose {
            job.cancel()
            runCatching { connectionRef.get()?.disconnect() }
        }
    }

    private fun open(url: String, apiKey: String, provider: CloudProvider): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            if (provider.id == CloudProvider.OPENROUTER.id) {
                // Optional attribution headers OpenRouter asks apps to send.
                setRequestProperty("X-Title", "Offline GGUF Chat")
                setRequestProperty("HTTP-Referer", "https://github.com/Inkflow-Gab/OfflineGGUFChat")
            }
        }

    private fun readBody(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private fun requestBody(
        modelId: String,
        system: String,
        turns: List<ChatTurn>,
        sampling: SamplingParams,
    ): String = buildJsonObject {
        put("model", modelId)
        put("stream", true)
        put("temperature", sampling.temperature.toDouble())
        put("top_p", sampling.topP.toDouble())
        put("max_tokens", sampling.maxTokens)
        if (sampling.presencePenalty != 0f) put("presence_penalty", sampling.presencePenalty.toDouble())
        if (sampling.frequencyPenalty != 0f) put("frequency_penalty", sampling.frequencyPenalty.toDouble())
        put("messages", buildJsonArray {
            if (system.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
            }
            turns.forEach { turn ->
                add(buildJsonObject {
                    put("role", turn.role)
                    put("content", turn.content)
                })
            }
        })
        // Ask for a final usage chunk where supported (ignored elsewhere).
        putJsonObject("stream_options") { put("include_usage", true) }
    }.toString()

    private fun friendlyError(failure: Throwable): String {
        val message = failure.message ?: failure.javaClass.simpleName
        return when {
            failure is java.net.UnknownHostException ->
                "No network route to the server. Check Wi-Fi/data and the URL. ($message)"
            failure is java.net.SocketTimeoutException ->
                "The cloud server took too long to answer. Try again, or pick a faster model."
            failure is java.net.ConnectException ->
                "Could not connect. For a PC server, check it is running and on the same Wi-Fi. ($message)"
            else -> message
        }
    }
}
