package com.offlinegguf.chat.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider JSON parsing. The wire shapes are copied from real OpenRouter and
 * Groq responses; nothing here touches a socket.
 */
class CloudModelsTest {

    private val openRouterBody = """
        {"data":[
          {"id":"deepseek/deepseek-chat-v3-0324:free","name":"DeepSeek V3 (free)","context_length":163840,
           "pricing":{"prompt":"0","completion":"0"}},
          {"id":"openai/gpt-4o","name":"GPT-4o","context_length":128000,
           "pricing":{"prompt":"0.0000025","completion":"0.00001"}},
          {"id":"meta-llama/llama-3.3-70b-instruct","name":"Llama 3.3 70B","context_length":131072,
           "pricing":{"prompt":"0.0000001","completion":"0.0000001"}}
        ]}
    """.trimIndent()

    @Test
    fun `openrouter models parse with free detection and free-first order`() {
        val models = CloudModels.parseModels(openRouterBody, "openrouter")
        assertEquals(3, models.size)
        assertEquals("deepseek/deepseek-chat-v3-0324:free", models.first().id)
        assertTrue(models.first().isFree)
        assertEquals(163840, models.first().contextLength)
        val gpt = models.first { it.id == "openai/gpt-4o" }
        assertFalse(gpt.isFree)
        assertEquals(0.0000025, gpt.promptPrice!!, 1e-12)
        assertTrue(gpt.priceSummary!!.contains("/ 1M tok"))
        assertEquals("Free", models.first().priceSummary)
    }

    @Test
    fun `groq models have no prices and count as free tier`() {
        val body = """{"data":[{"id":"llama-3.3-70b-versatile","object":"model","context_window":131072}]}"""
        val models = CloudModels.parseModels(body, "groq")
        assertEquals(1, models.size)
        assertTrue(models.single().isFree)
        assertEquals(131072, models.single().contextLength)
        assertNull(models.single().priceSummary)
        assertEquals("llama-3.3-70b-versatile", models.single().displayName)
    }

    @Test
    fun `custom hosts only call zero priced models free`() {
        val body = """{"data":[{"id":"local-model","context_length":8192}]}"""
        val models = CloudModels.parseModels(body, "custom")
        assertFalse(models.single().isFree)
    }

    @Test
    fun `malformed bodies parse to nothing instead of throwing`() {
        assertTrue(CloudModels.parseModels("not json", "openrouter").isEmpty())
        assertTrue(CloudModels.parseModels("{}", "openrouter").isEmpty())
        assertTrue(CloudModels.parseModels("""{"data":[{"name":"no id"}]}""", "openrouter").isEmpty())
    }

    @Test
    fun `stream chunks carry content reasoning and usage`() {
        val content = CloudModels.parseChunk(
            """{"choices":[{"delta":{"content":"Hello"},"finish_reason":null}]}""",
        )
        assertEquals("Hello", content?.content)
        assertEquals("", content?.reasoning)

        val reasoning = CloudModels.parseChunk(
            """{"choices":[{"delta":{"reasoning_content":"let me think"}}]}""",
        )
        assertEquals("let me think", reasoning?.reasoning)

        val openRouterReasoning = CloudModels.parseChunk(
            """{"choices":[{"delta":{"reasoning":"hmm"}}]}""",
        )
        assertEquals("hmm", openRouterReasoning?.reasoning)

        val usage = CloudModels.parseChunk(
            """{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":34}}""",
        )
        assertEquals(12, usage?.promptTokens)
        assertEquals(34, usage?.completionTokens)

        assertNull(CloudModels.parseChunk("[DONE]"))
        assertNull(CloudModels.parseChunk(": OPENROUTER PROCESSING"))
        assertNull(CloudModels.parseChunk("garbage"))
    }

    @Test
    fun `error bodies surface the provider message`() {
        assertEquals(
            "HTTP 401: No auth credentials found",
            CloudModels.parseError("""{"error":{"message":"No auth credentials found","code":401}}""", 401),
        )
        assertEquals("HTTP 500", CloudModels.parseError("<html>oops</html>", 500))
    }

    @Test
    fun `providers resolve and custom urls are trimmed`() {
        assertEquals(CloudProvider.OPENROUTER, CloudProvider.byId("openrouter"))
        assertEquals(CloudProvider.OPENROUTER, CloudProvider.byId("nonsense"))
        assertEquals(CloudProvider.GROQ, CloudProvider.byId("groq"))
        assertEquals(
            "http://192.168.1.10:11434/v1",
            CloudProvider.CUSTOM.resolveBaseUrl(" http://192.168.1.10:11434/v1/ "),
        )
        assertEquals(
            "https://openrouter.ai/api/v1",
            CloudProvider.OPENROUTER.resolveBaseUrl("http://ignored"),
        )
    }

    @Test
    fun `opencode zen free models are detected by id and name`() {
        val body = """
            {"data":[
              {"id":"big-pickle","object":"model","created":1770000000},
              {"id":"mimo-v2.5-free","object":"model","created":1770000001},
              {"id":"kimi-k2","object":"model","created":1770000002},
              {"id":"nemotron-3-ultra-free","object":"model","created":1770000003}
            ]}
        """.trimIndent()
        val models = CloudModels.parseModels(body, "opencode")
        assertEquals(4, models.size)
        assertTrue(models.first { it.id == "big-pickle" }.isFree)
        assertTrue(models.first { it.id == "mimo-v2.5-free" }.isFree)
        assertTrue(models.first { it.id == "nemotron-3-ultra-free" }.isFree)
        assertFalse(models.first { it.id == "kimi-k2" }.isFree)
        // Free first, then alphabetical.
        assertEquals("big-pickle", models.first().id)
    }

    @Test
    fun `opencode zen provider speaks the openai dialect`() {
        val zen = CloudProvider.byId("opencode")
        assertEquals("https://opencode.ai/zen/v1", zen.resolveBaseUrl(""))
        assertEquals("https://opencode.ai/auth", zen.keyUrl)
        assertTrue(zen.requiresKey)
    }

    @Test
    fun `finish reason is carried through chunks`() {
        val done = CloudModels.parseChunk(
            """{"choices":[{"delta":{},"finish_reason":"length"}]}""",
        )
        assertEquals("length", done?.finishReason)

        val stop = CloudModels.parseChunk(
            """{"choices":[{"delta":{"content":"."},"finish_reason":"stop"}]}""",
        )
        assertEquals("stop", stop?.finishReason)
    }

    @Test
    fun `whole non-streamed completions parse to one delta`() {
        val delta = CloudModels.parseCompletion(
            """
            {"choices":[{"message":{"role":"assistant","content":"Full answer"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":7,"completion_tokens":9}}
            """.trimIndent(),
        )
        assertEquals("Full answer", delta?.content)
        assertEquals("stop", delta?.finishReason)
        assertEquals(7, delta?.promptTokens)
        assertEquals(9, delta?.completionTokens)
        assertNull(CloudModels.parseCompletion("<html>gateway error</html>"))
    }
}
