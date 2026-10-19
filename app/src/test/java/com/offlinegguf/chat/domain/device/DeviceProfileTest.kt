package com.offlinegguf.chat.domain.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceProfileTest {
    @Test
    fun `small cpu gets a single worker and a small prompt batch`() {
        val phone = DeviceProfile.Unknown.copy(
            ramBytes = 2L * 1024 * 1024 * 1024,
            cores = 2,
            lowRamDevice = true,
        )

        assertEquals(1, phone.suggestedThreads)
        assertEquals(64, phone.suggestedBatch)
        assertEquals(1024, phone.suggestedContext)
    }

    @Test
    fun `stronger devices keep spare CPU for the UI and cap workers`() {
        val phone = DeviceProfile.Unknown.copy(cores = 12, lowRamDevice = false)
        assertEquals(4, phone.suggestedThreads)
    }
}
