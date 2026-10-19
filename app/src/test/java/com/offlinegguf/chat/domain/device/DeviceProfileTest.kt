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
        assertEquals(128, phone.suggestedBatch)
        assertEquals(1024, phone.suggestedContext)
    }

    @Test
    fun `stronger devices keep spare CPU for the UI and cap workers`() {
        val phone = DeviceProfile.Unknown.copy(cores = 12, lowRamDevice = false)
        assertEquals(4, phone.suggestedThreads)
    }

    @Test
    fun `inference runs on the performance cluster of a big little phone`() {
        // 2 x A75 + 6 x A55, like the Helio-class phones this app targets.
        val phone = DeviceProfile.Unknown.copy(cores = 8, performanceCores = 2)
        assertEquals(2, phone.suggestedThreads)

        // Unknown cluster layout falls back to all cores minus one.
        val opaque = DeviceProfile.Unknown.copy(cores = 8, performanceCores = 0)
        assertEquals(4, opaque.suggestedThreads)

        // A single performance core still gets a companion worker: one thread
        // was measurably the slowest configuration in the field.
        val single = DeviceProfile.Unknown.copy(cores = 8, performanceCores = 1)
        assertEquals(4, single.suggestedThreads)
    }
}
