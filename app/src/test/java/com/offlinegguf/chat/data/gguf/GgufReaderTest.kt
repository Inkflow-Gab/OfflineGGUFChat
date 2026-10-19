package com.offlinegguf.chat.data.gguf

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden fixtures for the Kotlin GGUF reader.
 *
 * The bytes are built here rather than checked in, so the expectations are visible
 * next to the assertions. `app/cpp/tests/gguf_probe_test.cpp` builds the same
 * fixtures and asserts the same numbers on the C++ side: the two parsers are
 * deliberately interchangeable, and this is what keeps them that way.
 */
class GgufReaderTest {

    /** Little-endian GGUF writer, matching what llama.cpp produces. */
    private class Writer(private val version: Int = 3) {
        private val out = java.io.ByteArrayOutputStream()

        val size: Int get() = out.size

        fun u8(value: Int) = apply { out.write(value and 0xFF) }

        fun u32(value: Long) = apply {
            for (shift in 0..3) out.write(((value ushr (8 * shift)) and 0xFF).toInt())
        }

        fun u64(value: Long) = apply {
            for (shift in 0..7) out.write(((value ushr (8 * shift)) and 0xFF).toInt())
        }

        /** String lengths are u32 in v1 and u64 in v2/v3 — the rule llama.cpp uses. */
        fun str(value: String) = apply {
            val bytes = value.toByteArray(Charsets.UTF_8)
            if (version <= 1) u32(bytes.size.toLong()) else u64(bytes.size.toLong())
            out.write(bytes)
        }

        fun kvString(key: String, value: String) = apply { str(key); u32(TYPE_STRING); str(value) }

        fun kvU32(key: String, value: Long) = apply { str(key); u32(TYPE_UINT32); u32(value) }

        fun kvBool(key: String, value: Boolean) = apply { str(key); u32(TYPE_BOOL); u8(if (value) 1 else 0) }

        fun kvStringArray(key: String, values: List<String>) = apply {
            str(key)
            u32(TYPE_ARRAY)
            u32(TYPE_STRING)
            u64(values.size.toLong())
            values.forEach { str(it) }
        }

        fun tensor(name: String, dims: List<Long>, dtype: Int, offset: Long = 0) = apply {
            str(name)
            u32(dims.size.toLong())
            dims.forEach { if (version <= 1) u32(it) else u64(it) }
            u32(dtype.toLong())
            u64(offset)
        }

        fun header(tensorCount: Long, kvCount: Long) = apply {
            out.write(MAGIC)
            u32(version.toLong())
            if (version <= 1) {
                u32(tensorCount)
                u32(kvCount)
            } else {
                u64(tensorCount)
                u64(kvCount)
            }
        }

        fun bytes(): ByteArray = out.toByteArray()

        companion object {
            val MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
            const val TYPE_UINT32 = 4
            const val TYPE_BOOL = 7
            const val TYPE_STRING = 8
            const val TYPE_ARRAY = 9
        }
    }

    /** Q4_K, Q4_K, F16 — the majority type is what people call "the quant". */
    private val Q4_K = 12
    private val F16 = 1

    private fun standardFixture(version: Int = 3): ByteArray {
        val w = Writer(version)
        w.header(tensorCount = 3, kvCount = 12)
        w.kvString("general.architecture", "llama")
        w.kvString("general.name", "Tiny Test")
        w.kvU32("llama.context_length", 4096)
        w.kvU32("llama.block_count", 4)
        w.kvU32("llama.embedding_length", 64)
        w.kvU32("llama.attention.head_count", 8)
        w.kvU32("llama.attention.head_count_kv", 2)
        w.kvU32("llama.vocab_size", 32)
        w.kvBool("tokenizer.ggml.add_bos_token", true)
        w.kvString("tokenizer.ggml.model", "llama")
        w.kvStringArray("tokenizer.ggml.tokens", listOf("<s>", "</s>", "hi"))
        w.kvString("tokenizer.chat_template", "<|im_start|>user\n{{content}}<|im_end|>\n")
        w.tensor("blk.0.attn_q.weight", listOf(64, 64), Q4_K, 0)
        w.tensor("blk.1.attn_q.weight", listOf(32, 32), Q4_K, 2304)
        w.tensor("output_norm.weight", listOf(32), F16, 2880)
        return w.bytes()
    }

    private fun probe(bytes: ByteArray, budget: Long = 8L * 1024 * 1024) =
        GgufReader(metadataBudgetBytes = budget).read(ByteArrayInputStream(bytes), bytes.size.toLong())

    // ---------------------------------------------------------------- happy path

    @Test
    fun `a complete file is parsed end to end`() {
        val bytes = standardFixture()
        val result = probe(bytes)

        assertTrue("error was ${result.error}", result.valid)
        assertNull(result.error)
        assertTrue(result.loadable)
        assertTrue(result.magicPresent)
        assertEquals(3, result.version)
        assertEquals(3, result.tensorCount)
        assertEquals(12, result.kvCount)
        assertEquals(3, result.tensorsScanned)
        assertFalse(result.metadataTruncated)
        assertFalse(result.tensorsTruncated)

        assertEquals("llama", result.architecture)
        assertEquals("Tiny Test", result.name)
        assertEquals(4096, result.contextLength)
        assertEquals(4, result.blockCount)
        assertEquals(64, result.embeddingLength)
        assertEquals(8, result.headCount)
        assertEquals(32, result.vocabSize)
        assertEquals(3, result.tokenCount)
        assertEquals("llama", result.tokenizerModel)
        assertTrue(result.addBosToken)
        assertNotNull(result.chatTemplate)
        assertTrue(result.chatTemplate!!.contains("<|im_start|>"))
        assertFalse(result.multimodal)
    }

    @Test
    fun `the tensor table gives the quant, parameter count and weight size`() {
        val result = probe(standardFixture())

        assertEquals(Q4_K, result.dominantDtype)
        assertEquals("Q4_K", result.quantization)
        // 64*64 + 32*32 + 32 elements.
        assertEquals(5152, result.paramCount)
        // Q4_K: 16 blocks * 144 B, 4 blocks * 144 B. F16: 32 * 2 B.
        assertEquals(2944, result.weightBytes)
        // 2944 weight bytes over 5152 parameters.
        assertEquals(736.0 / 161.0, result.bitsPerWeight, 1e-9)
        assertEquals(3, result.tensorDtypes.size)
        assertEquals("blk.0.attn_q.weight", result.tensorDtypes[0].first)
        assertEquals(Q4_K, result.tensorDtypes[0].second)
        assertEquals(F16, result.tensorDtypes[2].second)
    }

    @Test
    fun `data starts at the aligned end of the header`() {
        val bytes = standardFixture()
        val result = probe(bytes)
        assertEquals(32, result.alignment)
        assertEquals(GgufReader.alignUp(bytes.size.toLong(), 32), result.dataStart)
        assertTrue(result.tensorTableBytes > 0)
        // The tensor table is only part of the header, so it stays well under the file size.
        assertTrue(result.tensorTableBytes < bytes.size)
    }

    @Test
    fun `an explicit alignment is honoured, a non power of two is not`() {
        val aligned = Writer()
        aligned.header(1, 2)
        aligned.kvString("general.architecture", "llama")
        aligned.kvU32("general.alignment", 64)
        aligned.tensor("t", listOf(8), F16)
        val alignedBytes = aligned.bytes()
        val alignedProbe = probe(alignedBytes)
        assertEquals(64, alignedProbe.alignment)
        assertEquals(GgufReader.alignUp(alignedBytes.size.toLong(), 64), alignedProbe.dataStart)

        val broken = Writer()
        broken.header(1, 2)
        broken.kvString("general.architecture", "llama")
        broken.kvU32("general.alignment", 7)
        broken.tensor("t", listOf(8), F16)
        assertEquals(GgufReader.DEFAULT_ALIGNMENT, probe(broken.bytes()).alignment)
    }

    @Test
    fun `a declared vocab size wins over the token array in either order`() {
        // Declared first, array second.
        val declaredFirst = Writer()
        declaredFirst.header(1, 3)
        declaredFirst.kvString("general.architecture", "llama")
        declaredFirst.kvU32("llama.vocab_size", 32000)
        declaredFirst.kvStringArray("tokenizer.ggml.tokens", listOf("a", "b", "c"))
        declaredFirst.tensor("t", listOf(8), F16)
        val first = probe(declaredFirst.bytes())
        assertEquals(32000, first.vocabSize)
        assertEquals(3, first.tokenCount)

        // Array first, declared second: the declared value still wins.
        val declaredLast = Writer()
        declaredLast.header(1, 3)
        declaredLast.kvString("general.architecture", "llama")
        declaredLast.kvStringArray("tokenizer.ggml.tokens", listOf("a", "b", "c"))
        declaredLast.kvU32("llama.vocab_size", 32000)
        declaredLast.tensor("t", listOf(8), F16)
        val last = probe(declaredLast.bytes())
        assertEquals(32000, last.vocabSize)
        assertEquals(3, last.tokenCount)
    }

    @Test
    fun `the token array is the fallback vocabulary size`() {
        val w = Writer()
        w.header(1, 2)
        w.kvString("general.architecture", "llama")
        w.kvStringArray("tokenizer.ggml.tokens", listOf("a", "b", "c", "d"))
        w.tensor("t", listOf(8), F16)
        val result = probe(w.bytes())
        assertEquals(4, result.tokenCount)
        assertEquals(4, result.vocabSize)
    }

    @Test
    fun `v1 files use 32-bit counts, strings and dimensions`() {
        val bytes = standardFixture(version = 1)
        val result = probe(bytes)
        assertEquals(1, result.version)
        assertEquals(3, result.tensorCount)
        assertEquals(12, result.kvCount)
        assertEquals("Tiny Test", result.name)
        assertEquals(5152, result.paramCount)
        assertEquals("Q4_K", result.quantization)
    }

    @Test
    fun `architecture scoped keys are read, foreign ones are skipped`() {
        val w = Writer()
        w.header(1, 4)
        w.kvString("general.architecture", "gemma")
        w.kvU32("gemma.context_length", 8192)
        // Belongs to a different architecture: skipped, and must not pollute ours.
        w.kvU32("llama.context_length", 2048)
        w.kvString("some.vendor.note", "ignored")
        w.tensor("t", listOf(8), F16)
        val result = probe(w.bytes())
        assertEquals("gemma", result.architecture)
        assertEquals(8192, result.contextLength)
        assertFalse(result.metadata.containsKey("llama.context_length"))
        assertFalse(result.metadata.containsKey("some.vendor.note"))
        assertTrue(result.metadata.containsKey("gemma.context_length"))
    }

    @Test
    fun `metadata values render themselves`() {
        val result = probe(standardFixture())
        val name = result.metadata["general.name"]
        assertNotNull(name)
        assertEquals("\"Tiny Test\"", name!!.render())
        assertEquals("string", name.typeName)
        assertEquals(4096L, result.int("llama.context_length"))
        assertEquals("Tiny Test", result.string("general.name"))

        val tokens = result.metadata["tokenizer.ggml.tokens"] as GgufValue.Items
        assertEquals(3, tokens.length)
        assertNotNull("a small string array is materialised", tokens.strings)
        assertEquals(listOf("<s>", "</s>", "hi"), tokens.strings)
        assertEquals("array<string>", tokens.typeName)
    }

    @Test
    fun `a big vocabulary is counted in full but only its head is kept`() {
        val w = Writer()
        w.header(1, 2)
        w.kvString("general.architecture", "llama")
        w.kvStringArray("tokenizer.ggml.tokens", (1..12).map { "tok$it" })
        w.tensor("t", listOf(8), F16)
        val result = probe(w.bytes())
        val tokens = result.metadata["tokenizer.ggml.tokens"] as GgufValue.Items
        // A 128k-token vocabulary would otherwise allocate megabytes for one screen.
        assertEquals(12, tokens.length)
        assertEquals(12, result.tokenCount)
        assertEquals((1..8).map { "tok$it" }, tokens.strings)
        assertNull(tokens.numbers)
    }

    @Test
    fun `multimodal files are flagged from their keys`() {
        val w = Writer()
        w.header(1, 2)
        w.kvString("general.architecture", "llama")
        w.kvU32("clip.vision.embedding_length", 1024)
        w.tensor("t", listOf(8), F16)
        assertTrue(probe(w.bytes()).multimodal)
        assertTrue(GgufReader.isMultimodalKey("mmproj.block_count"))
        assertTrue(GgufReader.isMultimodalKey("clip.audio.ctx"))
        assertFalse(GgufReader.isMultimodalKey("llama.block_count"))
    }

    @Test
    fun `an unknown tensor dtype is reported rather than guessed`() {
        val w = Writer()
        w.header(1, 1)
        w.kvString("general.architecture", "llama")
        w.tensor("t", listOf(8), 999)
        val result = probe(w.bytes())
        assertEquals(999, result.dominantDtype)
        assertEquals("type_999", result.quantization)
        assertEquals(0, result.weightBytes)
    }

    // ------------------------------------------------------------ failure paths

    @Test
    fun `a file that is not GGUF says so`() {
        val result = probe("definitely not a model".toByteArray())
        assertFalse(result.valid)
        assertFalse(result.magicPresent)
        assertEquals("not a GGUF file (bad magic)", result.error)
        assertFalse(result.loadable)
        assertEquals("unknown", result.quantization)
    }

    @Test
    fun `an empty file is not GGUF either`() {
        val result = probe(ByteArray(0))
        assertFalse(result.valid)
        assertEquals("not a GGUF file (bad magic)", result.error)
    }

    @Test
    fun `an unsupported version is named`() {
        val w = Writer(version = 99)
        w.header(tensorCount = 0, kvCount = 0)
        val result = probe(w.bytes())
        assertFalse(result.valid)
        assertEquals("unsupported GGUF version 99", result.error)
    }

    @Test
    fun `implausible counts are rejected before any walking`() {
        val w = Writer()
        w.header(tensorCount = 99_999_999, kvCount = 1)
        val result = probe(w.bytes())
        assertFalse(result.valid)
        assertEquals("implausible tensor/KV counts", result.error)
    }

    @Test
    fun `counts cut in half are a truncated header`() {
        val bytes = standardFixture()
        // magic(4) + version(4) + 3 of the 8 count bytes.
        val result = probe(bytes.copyOf(11))
        assertFalse(result.valid)
        assertEquals("truncated header", result.error)
    }

    @Test
    fun `a file cut inside the metadata section is partial but honest`() {
        val bytes = standardFixture()
        val cut = bytes.copyOf(90)
        val result = probe(cut)
        assertTrue("a partial header is still useful", result.valid)
        assertTrue(result.metadataTruncated)
        assertFalse(result.tensorsTruncated)
        assertEquals("metadata section exceeds read budget; partial header", result.error)
        assertTrue(result.bytesScanned <= cut.size)
    }

    @Test
    fun `a file cut inside the tensor table is not loadable`() {
        val bytes = standardFixture()
        // Drop the last 12 bytes: enough to break the final tensor entry.
        val result = probe(bytes.copyOf(bytes.size - 12))
        assertFalse(result.valid)
        assertTrue(result.tensorsTruncated)
        assertEquals("tensor table truncated or unreadable (partial download?)", result.error)
        assertFalse(result.loadable)
    }

    @Test
    fun `a header with no tensors cannot be loaded`() {
        val w = Writer()
        w.header(tensorCount = 0, kvCount = 1)
        w.kvString("general.architecture", "llama")
        val result = probe(w.bytes())
        assertFalse(result.valid)
        assertFalse(result.loadable)
        assertEquals("no tensors", result.error)
        assertEquals("unknown", result.quantization)
    }

    @Test
    fun `the metadata budget stops a pathological file early`() {
        val bytes = standardFixture()
        val result = probe(bytes, budget = 24)
        assertTrue(result.valid)
        assertTrue(result.metadataTruncated)
        assertEquals("metadata section exceeds read budget; partial header", result.error)
        assertEquals("", result.architecture)
        assertTrue(result.bytesScanned < bytes.size)
    }

    // ------------------------------------------------------------------ helpers

    @Test
    fun `alignUp rounds to the next multiple and leaves exact ones alone`() {
        assertEquals(0, GgufReader.alignUp(0, 32))
        assertEquals(32, GgufReader.alignUp(1, 32))
        assertEquals(32, GgufReader.alignUp(32, 32))
        assertEquals(64, GgufReader.alignUp(33, 32))
        assertEquals(5, GgufReader.alignUp(5, 1))
        assertEquals(7, GgufReader.alignUp(7, 0))
    }

    @Test
    fun `keyIsInteresting keeps the vocabulary out of memory`() {
        assertTrue(GgufReader.keyIsInteresting("general.name", ""))
        assertTrue(GgufReader.keyIsInteresting("tokenizer.chat_template", ""))
        assertTrue(GgufReader.keyIsInteresting("tokenizer.ggml.tokens", ""))
        assertTrue(GgufReader.keyIsInteresting("llama.context_length", "llama"))
        assertFalse(GgufReader.keyIsInteresting("llama.context_length", "gemma"))
        assertFalse(GgufReader.keyIsInteresting("random.vendor.key", "llama"))
    }

    @Test
    fun `dtype labels and sizes match ggml`() {
        assertEquals("Q4_K", GgufDtype.label(12))
        assertEquals("F16", GgufDtype.label(1))
        assertEquals("type_77", GgufDtype.label(77))
        assertEquals(144, GgufDtype.Q4_K.bytesFor(256))
        assertEquals(288, GgufDtype.Q4_K.bytesFor(257))
        assertEquals(4.5, GgufDtype.Q4_K.bitsPerWeight(), 1e-9)
        assertEquals(16.0, GgufDtype.F16.bitsPerWeight(), 1e-9)
        assertTrue(GgufDtype.Q4_K.isKQuants)
        assertFalse(GgufDtype.F16.isKQuants)
    }
}
