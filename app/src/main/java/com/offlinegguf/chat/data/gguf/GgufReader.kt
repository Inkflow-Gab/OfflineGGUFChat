package com.offlinegguf.chat.data.gguf

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

/**
 * Sequential GGUF header reader.
 *
 * This is the app's source of truth for "what is inside this file". It parses
 * magic/version, the KV metadata block and the full tensor-info table, and from
 * the tensor table derives the quantization family, parameter count and weight
 * size — all without touching tensor *data*, so a 20 GB file costs a few
 * milliseconds and no memory.
 *
 * It never seeks: only [InputStream.read] and counted skips. That keeps it
 * usable on `content://` streams and SAF copies, and makes the whole thing unit
 * testable on a JVM with a `ByteArrayInputStream`.
 *
 * The rules (budget, error strings, majority-dtype quantization) deliberately
 * mirror `app/cpp/gguf/gguf_probe.cpp`, which does the same job inside the
 * engine as a preflight/integrity check. If one changes, change both.
 */
class GgufReader(
    private val metadataBudgetBytes: Long = DEFAULT_METADATA_BUDGET,
    /** Cap on materialised metadata entries so a pathological file cannot OOM us. */
    private val maxMetadataEntries: Int = 4096,
) {

    data class Probe(
        val valid: Boolean,
        val error: String?,
        val magicPresent: Boolean,
        val version: Int,
        val tensorCount: Long,
        val kvCount: Long,
        val tensorsScanned: Long,
        val alignment: Long,
        /** Absolute offset where tensor data begins (header end, aligned up). */
        val dataStart: Long,
        /** Size of the tensor-info table only, in bytes (0 when it was unreadable). */
        val tensorTableBytes: Long,
        val fileSize: Long,
        val metadataTruncated: Boolean,
        val tensorsTruncated: Boolean,
        val architecture: String,
        val name: String,
        val contextLength: Long,
        /** Declared `{arch}.vocab_size`; falls back to the tokenizer's token count. */
        val vocabSize: Long,
        /** Length of `tokenizer.ggml.tokens` (0 when the file has no tokenizer). */
        val tokenCount: Long,
        val blockCount: Long,
        val headCount: Long,
        val embeddingLength: Long,
        val quantization: String,
        val dominantDtype: Int,
        val paramCount: Long,
        val weightBytes: Long,
        val chatTemplate: String?,
        val tokenizerModel: String?,
        val tokenizerPre: String?,
        val addBosToken: Boolean,
        val bosTokenId: Long,
        val eosTokenId: Long,
        val padTokenId: Long,
        val eotTokenId: Long,
        val multimodal: Boolean,
        /** First N tensors as (name, dtype) — used by the model inspector. */
        val tensorDtypes: List<Pair<String, Int>>,
        /** Interesting metadata, in file order. */
        val metadata: Map<String, GgufValue>,
        val bytesScanned: Long,
        val elapsedMs: Long,
    ) {
        val bitsPerWeight: Double
            get() = if (paramCount > 0) weightBytes * 8.0 / paramCount else 0.0

        /** True when the file looks complete enough to load. */
        val loadable: Boolean get() = valid && tensorCount > 0

        fun int(key: String): Long? = metadata[key]?.asLongOrNull()
        fun double(key: String): Double? = metadata[key]?.asDoubleOrNull()
        fun string(key: String): String? = metadata[key]?.asStringOrNull()
    }

    fun read(file: File): Probe {
        val size = runCatching { file.length() }.getOrDefault(-1L)
        return try {
            FileInputStream(file).use { read(it, size) }
        } catch (e: IOException) {
            failure(if (e.message?.contains("Permission") == true) "permission denied" else "cannot open file", size)
        } catch (e: SecurityException) {
            failure("permission denied", size)
        }
    }

    fun read(input: InputStream, fileSize: Long = -1L): Probe {
        val started = System.nanoTime()
        val r = LeReader(input)

        val magic = r.bytes(4)
        if (!r.ok() || !magic.contentEquals(MAGIC)) {
            val reason = if (magic.size == 4 && magic.contentEquals(MAGIC)) "short file"
            else "not a GGUF file (bad magic)"
            return failure(reason, fileSize, elapsedMs = millisSince(started), bytes = r.position)
        }

        val version = r.u32().toInt()
        if (version < 1 || version > 3) {
            return failure("unsupported GGUF version $version", fileSize, elapsedMs = millisSince(started), bytes = r.position)
        }

        // GGUF v1 stored the counts (and string/array lengths) as uint32; v2 and
        // v3 use uint64. llama.cpp reads it exactly this way and rejects v1
        // outright, so a v2 file with u32 counts would not load there either.
        val nTensors: Long
        val nKv: Long
        if (version <= 1) {
            nTensors = r.u32()
            nKv = r.u32()
        } else {
            nTensors = r.u64()
            nKv = r.u64()
        }
        if (!r.ok()) return failure("truncated header", fileSize, elapsedMs = millisSince(started), bytes = r.position)
        if (nTensors > MAX_TENSORS || nKv > MAX_KV) {
            return failure("implausible tensor/KV counts", fileSize, elapsedMs = millisSince(started), bytes = r.position)
        }

        var architecture = ""
        var name = ""
        var tokenizerModel: String? = null
        var tokenizerPre: String? = null
        var chatTemplate: String? = null
        var addBos = false
        var contextLength = 0L
        var vocabSize = 0L
        var tokenCount = 0L
        var blockCount = 0L
        var headCount = 0L
        var embeddingLength = 0L
        var bosId = -1L
        var eosId = -1L
        var padId = -1L
        var eotId = -1L
        var multimodal = false
        var alignmentSeen = 0L
        var truncated = false

        val metadata = LinkedHashMap<String, GgufValue>()

        for (i in 0 until nKv) {
            val key = r.string(version)
            if (!r.ok()) {
                truncated = true
                break
            }
            val type = GgufType.fromId(r.u32().toInt())
            if (!r.ok() || type == null) {
                truncated = true
                break
            }
            // Stop rather than walk hundreds of megabytes of vocab on a phone.
            if (metadataBudgetBytes > 0 && r.position > metadataBudgetBytes) {
                truncated = true
                break
            }

            val interesting = keyIsInteresting(key, architecture)
            val value = if (interesting) readValue(r, version, type, 0) else null
            if (interesting && (value == null || !r.ok())) {
                truncated = true
                break
            }
            if (!interesting && !skipValue(r, version, type, 0)) {
                truncated = true
                break
            }
            if (value == null) continue

            if (metadata.size < maxMetadataEntries) metadata[key] = value
            when (key) {
                "general.architecture" -> architecture = value.asStringOrNull() ?: ""
                "general.name" -> name = value.asStringOrNull() ?: ""
                "general.alignment" -> alignmentSeen = value.asLongOrNull() ?: 0L
                "tokenizer.ggml.model" -> tokenizerModel = value.asStringOrNull()
                "tokenizer.ggml.pre" -> tokenizerPre = value.asStringOrNull()
                "tokenizer.chat_template" -> chatTemplate = value.asStringOrNull()
                "tokenizer.ggml.add_bos_token" -> addBos = value.asBooleanOrNull() == true
                "tokenizer.ggml.bos_token_id" -> bosId = value.asLongOrNull() ?: -1L
                "tokenizer.ggml.eos_token_id" -> eosId = value.asLongOrNull() ?: -1L
                "tokenizer.ggml.padding_token_id" -> padId = value.asLongOrNull() ?: -1L
                "tokenizer.ggml.eot_token_id" -> eotId = value.asLongOrNull() ?: -1L
                "tokenizer.ggml.tokens" -> {
                    tokenCount = (value as? GgufValue.Items)?.length ?: 0L
                    if (vocabSize == 0L) vocabSize = tokenCount
                }
                else -> if (architecture.isNotEmpty() && key.startsWith("$architecture.")) {
                    when (key.removePrefix("$architecture.")) {
                        "context_length" -> contextLength = value.asLongOrNull() ?: contextLength
                        "vocab_size" -> vocabSize = value.asLongOrNull() ?: vocabSize
                        "block_count" -> blockCount = value.asLongOrNull() ?: blockCount
                        "embedding_length" -> embeddingLength = value.asLongOrNull() ?: embeddingLength
                        "attention.head_count" -> headCount = value.asLongOrNull() ?: headCount
                    }
                }
            }
            if (!multimodal && isMultimodalKey(key)) multimodal = true
        }

        var alignment = alignmentSeen
        if (alignment <= 0L || alignment and (alignment - 1) != 0L) alignment = DEFAULT_ALIGNMENT

        // ---- tensor info table ----
        val tensorTableStart = r.position
        val dtypeHistogram = HashMap<Int, Long>()
        val tensorDtypes = ArrayList<Pair<String, Int>>(min(nTensors.toInt(), 64))
        var params = 0L
        var weightBytes = 0L
        var tensorsTruncated = false

        for (i in 0 until nTensors) {
            val tensorName = r.string(version)
            if (!r.ok()) {
                tensorsTruncated = true
                break
            }
            val nDims = r.u32().toInt()
            if (!r.ok() || nDims < 0 || nDims > MAX_DIMS) {
                tensorsTruncated = true
                break
            }
            var elements = 1L
            repeat(nDims) {
                val dim = if (version <= 1) r.u32() else r.u64()
                if (dim > 0 && elements <= MAX_ELEMENTS) elements *= dim
            }
            val dtype = r.u32().toInt()
            r.u64() // offset relative to dataStart
            if (!r.ok()) {
                tensorsTruncated = true
                break
            }
            dtypeHistogram[dtype] = (dtypeHistogram[dtype] ?: 0L) + 1
            params += elements
            val type = GgufDtype.fromId(dtype)
            if (type != null && type.blockBytes > 0 && type.blockSize > 0) {
                val blocks = (elements + type.blockSize - 1) / type.blockSize
                weightBytes += blocks * type.blockBytes
            }
            if (tensorDtypes.size < 64) tensorDtypes += tensorName to dtype
        }

        // Embeddings/output heads are usually kept at a higher precision than the
        // body of the model, so the majority type is what people call "the quant".
        var dominant = -1
        var dominantCount = 0L
        dtypeHistogram.forEach { (id, count) ->
            if (count > dominantCount || (count == dominantCount && id < dominant)) {
                dominantCount = count
                dominant = id
            }
        }
        val quantization = if (dominant >= 0) GgufDtype.label(dominant) else "unknown"

        var dataStart = 0L
        var tensorTableBytes = 0L
        if (!tensorsTruncated) {
            val headerEnd = r.position
            dataStart = alignUp(headerEnd, alignment)
            tensorTableBytes = headerEnd - tensorTableStart
        }

        val elapsed = millisSince(started)
        val ok = !truncated && !tensorsTruncated && nTensors > 0
        val error = when {
            tensorsTruncated -> "tensor table truncated or unreadable (partial download?)"
            truncated -> "metadata section exceeds read budget; partial header"
            nTensors == 0L -> "no tensors"
            else -> null
        }

        return Probe(
            valid = ok || (truncated && !tensorsTruncated),
            error = error,
            magicPresent = true,
            version = version,
            tensorCount = nTensors,
            kvCount = nKv,
            tensorsScanned = dtypeHistogram.values.sum(),
            alignment = alignment,
            dataStart = dataStart,
            tensorTableBytes = tensorTableBytes,
            fileSize = fileSize,
            metadataTruncated = truncated,
            tensorsTruncated = tensorsTruncated,
            architecture = architecture,
            name = name,
            contextLength = contextLength,
            vocabSize = vocabSize,
            tokenCount = tokenCount,
            blockCount = blockCount,
            headCount = headCount,
            embeddingLength = embeddingLength,
            quantization = quantization,
            dominantDtype = dominant,
            paramCount = params,
            weightBytes = weightBytes,
            chatTemplate = chatTemplate,
            tokenizerModel = tokenizerModel,
            tokenizerPre = tokenizerPre,
            addBosToken = addBos,
            bosTokenId = bosId,
            eosTokenId = eosId,
            padTokenId = padId,
            eotTokenId = eotId,
            multimodal = multimodal,
            tensorDtypes = tensorDtypes,
            metadata = metadata,
            bytesScanned = r.position,
            elapsedMs = elapsed,
        )
    }

    private fun failure(reason: String, fileSize: Long, magicPresent: Boolean = false, elapsedMs: Long = 0L, bytes: Long = 0L) =
        Probe(
            valid = false,
            error = reason,
            magicPresent = magicPresent,
            version = 0,
            tensorCount = 0,
            kvCount = 0,
            tensorsScanned = 0,
            alignment = DEFAULT_ALIGNMENT,
            dataStart = 0,
            tensorTableBytes = 0,
            fileSize = fileSize,
            metadataTruncated = false,
            tensorsTruncated = false,
            architecture = "",
            name = "",
            contextLength = 0,
            vocabSize = 0,
            tokenCount = 0,
            blockCount = 0,
            headCount = 0,
            embeddingLength = 0,
            quantization = "unknown",
            dominantDtype = -1,
            paramCount = 0,
            weightBytes = 0,
            chatTemplate = null,
            tokenizerModel = null,
            tokenizerPre = null,
            addBosToken = false,
            bosTokenId = -1,
            eosTokenId = -1,
            padTokenId = -1,
            eotTokenId = -1,
            multimodal = false,
            tensorDtypes = emptyList(),
            metadata = emptyMap(),
            bytesScanned = bytes,
            elapsedMs = elapsedMs,
        )

    /** Null means "the stream went bad" — mirrors the C++ `!r.ok()` path. */
    private fun readValue(r: LeReader, version: Int, type: GgufType, depth: Int): GgufValue? {
        val value: GgufValue? = when (type) {
            GgufType.UINT8 -> GgufValue.Integer(r.u8().toLong(), type)
            GgufType.INT8 -> GgufValue.Integer(r.i8().toLong(), type)
            GgufType.UINT16 -> GgufValue.Integer(r.u16().toLong(), type)
            GgufType.INT16 -> GgufValue.Integer(r.i16().toLong(), type)
            GgufType.UINT32 -> GgufValue.Integer(r.u32(), type)
            GgufType.INT32 -> GgufValue.Integer(r.i32().toLong(), type)
            GgufType.UINT64 -> GgufValue.Integer(r.u64(), type)
            GgufType.INT64 -> GgufValue.Integer(r.i64(), type)
            GgufType.FLOAT32 -> GgufValue.Real(r.f32().toDouble(), type)
            GgufType.FLOAT64 -> GgufValue.Real(r.f64(), type)
            GgufType.BOOL -> GgufValue.Flag(r.i32() != 0)
            GgufType.STRING -> if (r.ok()) GgufValue.Text(r.string(version)) else null
            GgufType.UINT128, GgufType.INT128 -> if (r.skip(16)) GgufValue.Skipped(type, 16) else null
            GgufType.ARRAY -> readArray(r, version, depth)
        }
        return value?.takeIf { r.ok() }
    }

    private fun readArray(r: LeReader, version: Int, depth: Int): GgufValue? {
        val elemType = GgufType.fromId(r.u32().toInt())
        val len = r.u64()
        if (!r.ok() || elemType == null || len > MAX_ARRAY_LEN) return null
        val scalar = elemType.scalarBytes.toLong()
        val keepNumbers = elemType.isIntegral && len <= MAX_KEPT_NUMBERS
        return when {
            // Small numeric arrays (bos/eos id lists) are worth materialising.
            scalar > 0 && keepNumbers -> {
                val out = LongArray(len.toInt())
                for (i in 0 until len.toInt()) {
                    out[i] = when (elemType) {
                        GgufType.UINT8 -> r.u8().toLong()
                        GgufType.INT8 -> r.i8().toLong()
                        GgufType.UINT16 -> r.u16().toLong()
                        GgufType.INT16 -> r.i16().toLong()
                        GgufType.UINT32 -> r.u32()
                        GgufType.INT32 -> r.i32().toLong()
                        GgufType.UINT64 -> r.u64()
                        GgufType.INT64 -> r.i64()
                        GgufType.BOOL -> if (r.i32() != 0) 1L else 0L
                        else -> {
                            r.skip(scalar)
                            0L
                        }
                    }
                    if (!r.ok()) return null
                }
                GgufValue.Items(elemType, len, out, null)
            }
            // Vocab-scale number arrays: skip in one counted pass.
            scalar > 0 -> if (r.skip(scalar * len)) GgufValue.Items(elemType, len, null, null) else null
            elemType == GgufType.STRING -> {
                val kept = ArrayList<String>(min(len.toInt(), MAX_KEPT_STRINGS))
                for (i in 0 until len) {
                    val s = r.string(version)
                    if (!r.ok()) return null
                    if (i < MAX_KEPT_STRINGS) kept += s
                }
                GgufValue.Items(elemType, len, null, kept)
            }
            elemType == GgufType.ARRAY -> {
                if (depth > MAX_DEPTH) return null // pathological nesting
                for (i in 0 until len) if (readValue(r, version, elemType, depth + 1) == null) return null
                GgufValue.Items(elemType, len, null, null)
            }
            else -> null
        }
    }

    private fun skipValue(r: LeReader, version: Int, type: GgufType, depth: Int): Boolean {
        val scalar = type.scalarBytes.toLong()
        if (scalar > 0) return r.skip(scalar)
        if (type == GgufType.STRING) {
            r.string(version)
            return r.ok()
        }
        if (type == GgufType.ARRAY) {
            val elemType = GgufType.fromId(r.u32().toInt())
            val len = r.u64()
            if (!r.ok() || elemType == null || len > MAX_ARRAY_LEN) return false
            val child = elemType.scalarBytes.toLong()
            if (child > 0) return r.skip(child * len)
            if (elemType == GgufType.STRING) {
                for (i in 0 until len) {
                    r.string(version)
                    if (!r.ok()) return false
                }
                return true
            }
            if (elemType == GgufType.ARRAY && depth <= MAX_DEPTH) {
                for (i in 0 until len) if (!skipValue(r, version, elemType, depth + 1)) return false
                return true
            }
        }
        return false
    }

    companion object {
        const val DEFAULT_METADATA_BUDGET = 8L * 1024 * 1024
        const val DEFAULT_ALIGNMENT = 32L
        private const val MAX_TENSORS = 10_000_000L
        private const val MAX_KV = 1_000_000L
        private const val MAX_DIMS = 8
        private const val MAX_ELEMENTS = Long.MAX_VALUE / 4
        private const val MAX_ARRAY_LEN = 100_000_000L
        private const val MAX_KEPT_NUMBERS = 256
        private const val MAX_KEPT_STRINGS = 8
        private const val MAX_DEPTH = 4
        private const val MAX_STRING_BYTES = 8L * 1024 * 1024
        private val MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

        private fun millisSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

        fun alignUp(value: Long, alignment: Long): Long {
            if (alignment <= 1) return value
            return (value + alignment - 1) / alignment * alignment
        }

        /** Keys we materialise; everything else is skipped in one counted pass. */
        fun keyIsInteresting(key: String, arch: String): Boolean {
            if (key.startsWith("general.") ||
                key.startsWith("tokenizer.ggml.model") ||
                key.startsWith("tokenizer.ggml.pre") ||
                key.startsWith("tokenizer.ggml.bos") ||
                key.startsWith("tokenizer.ggml.eos") ||
                key.startsWith("tokenizer.ggml.padding") ||
                key.startsWith("tokenizer.ggml.eot") ||
                key.startsWith("tokenizer.ggml.add_bos") ||
                key.startsWith("tokenizer.chat_template") ||
                key.startsWith("tokenizer.ggml.tokens") ||
                key.startsWith("tokenizer.ggml.token_type") ||
                key.startsWith("tokenizer.ggml.scores") ||
                key.startsWith("tokenizer.ggml.merges") ||
                key.startsWith("clip.") ||
                key.startsWith("mmproj.") ||
                key.startsWith("split.") ||
                key.startsWith("writer.")
            ) return true
            return arch.isNotEmpty() && key.startsWith("$arch.")
        }

        fun isMultimodalKey(key: String): Boolean =
            key.startsWith("clip.") || key.startsWith("mmproj.") ||
                key.contains(".vision.") || key.contains(".audio.")
    }

    /** Little-endian reader with position tracking and sticky failure. */
    private class LeReader(private val input: InputStream) {
        private val buffer = ByteArray(BUFFER)
        private var limit = 0
        private var cursor = 0
        private var failed = false
        var position: Long = 0
            private set

        fun ok(): Boolean = !failed

        private fun byte(): Int {
            if (failed) return 0
            if (cursor >= limit) {
                if (!fill()) {
                    failed = true
                    return 0
                }
            }
            position++
            return buffer[cursor++].toInt() and 0xFF
        }

        private fun fill(): Boolean {
            cursor = 0
            limit = 0
            while (limit < BUFFER) {
                val n = try {
                    input.read(buffer, limit, BUFFER - limit)
                } catch (e: IOException) {
                    failed = true
                    return false
                }
                if (n < 0) break
                limit += n
            }
            return limit > 0
        }

        fun u8(): Int = byte()

        fun i8(): Int = byte().toByte().toInt()

        fun u16(): Int = byte() or (byte() shl 8)

        fun i16(): Int = u16().toShort().toInt()

        fun u32(): Long = (byte().toLong()) or (byte().toLong() shl 8) or
            (byte().toLong() shl 16) or (byte().toLong() shl 24)

        fun i32(): Int = u32().toInt()

        fun u64(): Long {
            var low = 0L
            for (i in 0 until 4) low = low or (byte().toLong() shl (8 * i))
            var high = 0L
            for (i in 0 until 4) high = high or (byte().toLong() shl (8 * i))
            return low or (high shl 32)
        }

        fun i64(): Long = u64()

        fun f32(): Float = java.lang.Float.intBitsToFloat(i32())

        fun f64(): Double = java.lang.Double.longBitsToDouble(u64())

        fun skip(count: Long): Boolean {
            if (failed || count < 0) return !failed
            var left = count
            // Drop what the buffer already holds before touching the stream.
            val buffered = (limit - cursor).toLong()
            if (left > 0 && buffered > 0) {
                val take = min(buffered, left)
                cursor += take.toInt()
                position += take
                left -= take
            }
            while (left > 0) {
                if (cursor >= limit && !fill()) {
                    failed = true
                    return false
                }
                val available = (limit - cursor).toLong()
                val take = min(available, left)
                cursor += take.toInt()
                position += take
                left -= take
            }
            return true
        }

        fun bytes(count: Int): ByteArray {
            val out = ByteArray(count)
            for (i in 0 until count) {
                val b = byte()
                if (failed) return out.copyOf(i)
                out[i] = b.toByte()
            }
            return out
        }

        /** GGUF string: uint32 length in v1, uint64 in v2/v3, then UTF-8 bytes. */
        fun string(version: Int): String {
            val len = if (version <= 1) u32() else u64()
            if (failed || len < 0 || len > MAX_STRING_BYTES) {
                failed = true
                return ""
            }
            if (len == 0L) return ""
            val out = ByteArray(len.toInt())
            var read = 0
            while (read < out.size) {
                if (cursor >= limit && !fill()) {
                    failed = true
                    return String(out, 0, read, Charsets.UTF_8)
                }
                val available = limit - cursor
                val take = min(available, out.size - read)
                buffer.copyInto(out, read, cursor, cursor + take)
                cursor += take
                position += take
                read += take
            }
            return String(out, Charsets.UTF_8)
        }

        companion object {
            private const val BUFFER = 64 * 1024
        }
    }
}
