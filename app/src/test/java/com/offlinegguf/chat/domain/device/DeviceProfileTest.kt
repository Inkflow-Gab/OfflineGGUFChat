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
    fun `the performance cluster is the top clock bin, not a percentage band`() {
        // Helio G85 (Galaxy A05 class): 2 x A75 @ 2.0 GHz + 6 x A55 @ 1.8 GHz.
        // The clusters are 10% apart in clock, so a "within 15% of the top"
        // rule would call all eight cores fast and hand inference six A55s.
        val held = List(2) { 2_000_000L } + List(6) { 1_800_000L }
        assertEquals(2, DeviceProbe.performanceCoresFromClocks(held))

        // A classic 4 + 4 split.
        assertEquals(4, DeviceProbe.performanceCoresFromClocks(List(4) { 2_400_000L } + List(4) { 1_800_000L }))

        // Uniform cores: there is no little cluster to avoid.
        assertEquals(0, DeviceProbe.performanceCoresFromClocks(List(8) { 2_000_000L }))

        // Hidden clocks (some kernels gate cpufreq to root): no claim either way.
        assertEquals(0, DeviceProbe.performanceCoresFromClocks(List(8) { 0L }))
        assertEquals(0, DeviceProbe.performanceCoresFromClocks(emptyList()))
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
