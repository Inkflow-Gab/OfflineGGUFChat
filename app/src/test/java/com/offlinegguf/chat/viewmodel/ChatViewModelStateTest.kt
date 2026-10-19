package com.offlinegguf.chat.viewmodel

import com.offlinegguf.chat.data.local.LocalModelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewModelStateTest {

    @Test
    fun `a model loaded from another screen clears the pick-model onboarding`() {
        val model = LocalModelEntity(
            path = "/app-private/models/test.gguf",
            fileName = "test.gguf",
            displayName = "Test model",
        )

        val chat = ChatUiState(engineAvailable = true)
            .withLoadedModel(model, contextSize = 2048, contextUsed = 12)

        assertFalse(chat.showEngineOnboarding)
        assertEquals(model.path, chat.model?.path)
        assertEquals(2048, chat.contextSize)
        assertEquals(12, chat.contextUsed)
        assertFalse(chat.loadingModel)
    }

    @Test
    fun `unloading the shared engine clears the chat model state`() {
        val model = LocalModelEntity(
            path = "/app-private/models/test.gguf",
            fileName = "test.gguf",
            displayName = "Test model",
        )
        val chat = ChatUiState(engineAvailable = true)
            .withLoadedModel(model, contextSize = 2048, contextUsed = 12)
            .withLoadedModel(null)

        assertTrue(chat.showEngineOnboarding)
        assertNull(chat.model)
        assertEquals(0, chat.contextSize)
        assertEquals(0, chat.contextUsed)
    }
}
