package com.offlinegguf.chat.domain.fit

import com.offlinegguf.chat.domain.device.DeviceProfile
import com.offlinegguf.chat.domain.device.DeviceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fit engine decides what a phone can run before the user burns a 4 GB
 * download on finding out. These tests pin the arithmetic.
 */
class ModelFitTest {

    private val GB = 1024L * 1024 * 1024

    private fun device(
        ramGb: Long = 8,
        cores: Int = 8,
        freeGb: Long = 60,
        pageSize: Long = 4096,
        engine: Boolean = true,
        lowRam: Boolean = false,
    ) = DeviceProfile(
        ramBytes = ramGb * GB,
        javaHeapBytes = 256L * 1024 * 1024,
        largeJavaHeapBytes = 512L * 1024 * 1024,
        cores = cores,
        maxCpuMhz = 2800.0,
        abiList = listOf("arm64-v8a", "armeabi-v7a"),
        apiLevel = 34,
        freeStorageBytes = freeGb * GB,
        totalStorageBytes = 128 * GB,
        pageSizeBytes = pageSize,
        lowRamDevice = lowRam,
        engineAvailable = engine,
    )

    /** A 3B-class model with a grouped-query attention layout. */
    private fun spec(
        weightBytes: Long = 2 * GB,
        paramCount: Long = 3_000_000_000,
        contextLength: Long = 8192,
        blockCount: Long = 32,
        embeddingLength: Long = 4096,
        headCount: Long = 32,
        headCountKv: Long = 8,
        quantization: String = "Q4_K_M",
        hasChatTemplate: Boolean = true,
        version: Int = 3,
        valid: Boolean = true,
        error: String? = null,
        vocabSize: Long = 32000,
        tokenCount: Long = 32000,
    ) = ModelFit.Spec(
        weightBytes = weightBytes,
        paramCount = paramCount,
        contextLength = contextLength,
        blockCount = blockCount,
        embeddingLength = embeddingLength,
        headCount = headCount,
        headCountKv = headCountKv,
        quantization = quantization,
        hasChatTemplate = hasChatTemplate,
        multimodal = false,
        vocabSize = vocabSize,
        tokenCount = tokenCount,
        version = version,
        valid = valid,
        error = error,
    )

    // ---------------------------------------------------------- device tiers

    @Test
    fun `tiers follow ram, cores and the low-ram flag`() {
        assertEquals(DeviceTier.FLAGSHIP, DeviceTier.of(12 * GB, 8))
        assertEquals(DeviceTier.STRONG, DeviceTier.of(8 * GB, 8))
        assertEquals(DeviceTier.MEDIUM, DeviceTier.of(4 * GB, 8))
        assertEquals(DeviceTier.LIGHT, DeviceTier.of(2 * GB, 8))
        assertEquals(DeviceTier.LIGHT, DeviceTier.of(12 * GB, 4))
        assertEquals(DeviceTier.LIGHT, DeviceTier.of(12 * GB, 8, lowRamDevice = true))
    }

    @Test
    fun `the memory budget is a fraction of ram, clamped at both ends`() {
        assertEquals((8 * GB * 0.58).toLong(), device(8).modelRamBudgetBytes)
        assertEquals((12 * GB * 0.62).toLong(), device(12).modelRamBudgetBytes)
        assertEquals((4 * GB * 0.52).toLong(), device(4).modelRamBudgetBytes)
        // A 256 GB server phone still only gets the ceiling.
        assertEquals(DeviceProfile.MAX_BUDGET, device(256).modelRamBudgetBytes)
        // 1 GB * 0.45 is under the floor, so the floor is what a model gets.
        assertEquals(DeviceProfile.MIN_BUDGET, device(1, cores = 2).modelRamBudgetBytes)
    }

    @Test
    fun `suggested threads leave a core for Android and the UI`() {
        assertEquals(4, device(cores = 8).suggestedThreads)
        assertEquals(3, device(cores = 4).suggestedThreads)
        assertEquals(1, device(cores = 2).suggestedThreads)
        assertEquals(512, device(8).suggestedBatch)
        assertEquals(256, device(3).suggestedBatch)
    }

    @Test
    fun `suggested context follows ram`() {
        assertEquals(1024, device(2).suggestedContext)
        assertEquals(2048, device(3).suggestedContext)
        assertEquals(4096, device(4).suggestedContext)
        assertEquals(8192, device(8).suggestedContext)
        assertEquals(16384, device(12).suggestedContext)
    }

    // --------------------------------------------------------------- kv cost

    @Test
    fun `kv cost comes from the attention layout when the header has one`() {
        // 2 (K and V) * 32 layers * (8 kv heads * 128 head dim) * 2 bytes (f16)
        assertEquals(131072, ModelFit.kvBytesPerToken(spec()))
        // q8_0 is 1.0625 bytes per value instead of 2.
        assertEquals(69632, ModelFit.kvBytesPerToken(spec(), "q8_0"))
        // q4_0 packs 32 values into 18 bytes, so 0.5625 per value: 2 * 32 * 1024 * 0.5625.
        assertEquals(36864, ModelFit.kvBytesPerToken(spec(), "q4_0"))
        // An unknown cache type falls back to f16 rather than guessing small.
        assertEquals(131072, ModelFit.kvBytesPerToken(spec(), "nonsense"))
    }

    @Test
    fun `fit estimate accounts for the requested KV cache quantization`() {
        val assessment = ModelFit.assess(
            device(8),
            spec(),
            ModelFit.Request(context = 8192, kvType = "q8_0"),
        )
        assertEquals(69_632L * 8192L, assessment.kvBytes)
        assertEquals("q8_0", assessment.settings.kvType)
    }

    @Test
    fun `kv cost falls back to a per-parameter ratio for catalogue entries`() {
        val thin = spec(blockCount = 0, embeddingLength = 0, headCount = 0, headCountKv = 0)
        assertEquals(300000, ModelFit.kvBytesPerToken(thin.copy(paramCount = 3_000_000_000)))
        // Bigger models use grouped-query attention, so the ratio is lower.
        assertEquals(560000, ModelFit.kvBytesPerToken(thin.copy(paramCount = 8_000_000_000)))
        assertEquals(0, ModelFit.kvBytesPerToken(thin.copy(paramCount = 0)))
    }

    // ------------------------------------------------------------------ fit

    @Test
    fun `a small model on a strong phone is comfortable`() {
        val assessment = ModelFit.assess(device(8), spec(weightBytes = 1 * GB, paramCount = 1_500_000_000))
        assertEquals(ModelFit.Verdict.COMFORTABLE, assessment.verdict)
        assertTrue(assessment.usable)
        assertTrue(assessment.pressure < 0.62f)
        assertTrue(assessment.headroomBytes > 0)
        assertTrue(assessment.tokensPerSecond > 0)
        assertEquals(8192, assessment.settings.context)
        assertEquals(4, assessment.settings.threads)
        assertEquals(512, assessment.settings.batch)
        assertEquals(ModelFit.DEFAULT_KV_TYPE, assessment.settings.kvType)
    }

    @Test
    fun `peak memory is weights times the mmap factor, plus kv, plus overhead`() {
        val assessment = ModelFit.assess(device(8), spec())
        val weights = (2 * GB * 1.12).toLong()
        assertEquals(weights, assessment.peakRamBytes - assessment.kvBytes - assessment.overheadBytes)
        assertEquals(256L * 1024 * 1024, assessment.overheadBytes)
        assertEquals(8192L * 131072L, assessment.kvBytes)
        assertEquals(2 * GB, assessment.weightBytes)
        assertEquals(assessment.budgetBytes, device(8).modelRamBudgetBytes)
        assertEquals(assessment.budgetBytes - assessment.peakRamBytes, assessment.headroomBytes)
        assertEquals(ModelFit.Verdict.WORKABLE, assessment.verdict)
    }

    @Test
    fun `a model that cannot fit is refused with the numbers behind it`() {
        val assessment = ModelFit.assess(device(8), spec(weightBytes = 200 * GB, paramCount = 70_000_000_000))
        assertEquals(ModelFit.Verdict.WILL_NOT_FIT, assessment.verdict)
        assertFalse(assessment.usable)
        assertTrue(assessment.pressure > 1f)
        assertTrue(assessment.warnings.any { it.startsWith("Needs about") })
    }

    @Test
    fun `context is halved until it fits, and the user is told`() {
        // 3500 MiB of weights leaves little room on an 8 GB phone, so the
        // 8192-token KV cache cannot stay and is halved until it fits.
        val assessment = ModelFit.assess(device(8), spec(weightBytes = 3_500L * 1024 * 1024))
        assertTrue(assessment.settings.context < 8192)
        assertTrue(assessment.warnings.any { it.contains("Context dropped to ${assessment.settings.context}") })
        assertEquals(ModelFit.Verdict.TIGHT, assessment.verdict)
        assertTrue(assessment.warnings.any { it.contains("Fits, but with only") })
    }

    @Test
    fun `an unreadable file short-circuits everything else`() {
        val assessment = ModelFit.assess(
            device(8),
            spec(valid = false, error = "not a GGUF file (bad magic)"),
        )
        assertEquals(ModelFit.Verdict.UNREADABLE, assessment.verdict)
        assertFalse(assessment.usable)
        assertEquals("not a GGUF file (bad magic)", assessment.warnings.first())
        assertEquals(0, assessment.kvBytes)
        assertEquals(0.0, assessment.tokensPerSecond, 0.0)
        assertEquals(device(8).suggestedContext, assessment.settings.context)
    }

    @Test
    fun `header inconsistencies are surfaced`() {
        assertTrue(
            ModelFit.assess(device(8), spec(version = 1)).warnings.any { it.contains("GGUF v1") },
        )
        assertTrue(
            ModelFit.assess(device(8), spec(vocabSize = 32000, tokenCount = 31998))
                .warnings.any { it.contains("does not match the tokenizer") },
        )
    }

    @Test
    fun `requests are clamped to what the file and the phone allow`() {
        val trained = ModelFit.assess(
            device(8),
            spec(contextLength = 4096),
            ModelFit.Request(context = 32768),
        )
        assertEquals(4096, trained.settings.context)

        val clamped = ModelFit.assess(
            device(8, cores = 8),
            spec(),
            ModelFit.Request(threads = 64, batch = 8),
        )
        assertEquals(8, clamped.settings.threads)
        assertEquals(32, clamped.settings.batch)

        val capped = ModelFit.assess(device(8), spec(), ModelFit.Request(batch = 99999))
        assertEquals(4096, capped.settings.batch)

        val floor = ModelFit.assess(device(8), spec(contextLength = 0), ModelFit.Request(context = 8))
        assertEquals(256, floor.settings.context)
    }

    @Test
    fun `a cheaper kv cache is suggested when it is what makes the model fit`() {
        // Constructed to miss by exactly the KV cache: with f16 the context is
        // halved all the way to 512 and still overflows a 4 GB phone's budget,
        // while q8_0 (1.0625 bytes per value instead of 2) brings it under.
        val assessment = ModelFit.assess(
            device(4),
            spec(weightBytes = 1_710_000_000, paramCount = 3_000_000_000),
            ModelFit.Request(context = 4096),
        )
        assertEquals(512, assessment.settings.context)
        assertTrue(assessment.notes.any { it.contains("Quantised KV cache") })
        assertEquals(512L * 69632L, assessment.kvBytes)
    }

    @Test
    fun `storage, engine and quant warnings are attached`() {
        val tightStorage = ModelFit.assess(
            device(8, freeGb = 2),
            spec(weightBytes = 2 * GB),
            ModelFit.Request(storageFreeBytes = 3 * GB),
        )
        assertTrue(tightStorage.warnings.any { it.contains("Importing needs roughly") })

        val noEngine = ModelFit.assess(device(8, engine = false), spec())
        assertTrue(noEngine.warnings.any { it.contains("no inference engine") })

        val aggressive = ModelFit.assess(device(8), spec(quantization = "Q2_K_S"))
        assertTrue(aggressive.warnings.any { it.contains("aggressive quantization") })

        val noTemplate = ModelFit.assess(device(8), spec(hasChatTemplate = false))
        assertTrue(noTemplate.notes.any { it.contains("No chat template") })

        val bigPages = ModelFit.assess(device(8, pageSize = 16384, engine = false), spec())
        assertTrue(bigPages.notes.any { it.contains("16 KB-page device") })
    }

    // ---------------------------------------------------------------- speed

    @Test
    fun `speed falls with size and rises with the device`() {
        val strong = ModelFit.estimateTokensPerSecond(DeviceTier.STRONG, 1 * GB, 4)
        val sameDeviceBigger = ModelFit.estimateTokensPerSecond(DeviceTier.STRONG, 8 * GB, 4)
        val flagship = ModelFit.estimateTokensPerSecond(DeviceTier.FLAGSHIP, 1 * GB, 4)
        val light = ModelFit.estimateTokensPerSecond(DeviceTier.LIGHT, 1 * GB, 4)
        assertTrue(strong > sameDeviceBigger)
        assertTrue(flagship > strong)
        assertTrue(strong > light)
        assertEquals(18.0, strong, 1e-9)
        assertEquals(0.0, ModelFit.estimateTokensPerSecond(DeviceTier.STRONG, 0, 4), 0.0)
        // Clamped at both ends so the UI never shows an absurd number.
        assertEquals(80.0, ModelFit.estimateTokensPerSecond(DeviceTier.FLAGSHIP, 1024, 4), 0.0)
        assertEquals(0.3, ModelFit.estimateTokensPerSecond(DeviceTier.LIGHT, 400 * GB, 1), 1e-9)
    }

    @Test
    fun `speed labels bucket the estimate`() {
        assertEquals("—", ModelFit.assess(device(8), spec(valid = false)).speedLabel)
        // A flagship-tier phone reading a 1 GB model: 26 GB/s of bandwidth.
        val comfortable = ModelFit.assess(device(12), spec(weightBytes = 1 * GB, paramCount = 1_000_000_000))
        assertEquals(26.0, comfortable.tokensPerSecond, 1e-9)
        assertEquals("very fast", comfortable.speedLabel)
        assertEquals(ModelFit.Verdict.COMFORTABLE, comfortable.verdict)
    }
}
