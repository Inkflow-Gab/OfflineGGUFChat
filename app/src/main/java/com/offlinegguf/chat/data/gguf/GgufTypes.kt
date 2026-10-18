@file:Suppress("unused")

package com.offlinegguf.chat.data.gguf

/**
 * GGUF value types, exactly as numbered by the format spec.
 * Ref: https://github.com/ggml-org/llama.cpp/blob/master/docs/gguf.md
 */
enum class GgufType(val id: Int) {
    UINT8(0),
    INT8(1),
    UINT16(2),
    INT16(3),
    UINT32(4),
    INT32(5),
    FLOAT32(6),
    BOOL(7),
    STRING(8),
    ARRAY(9),
    UINT64(10),
    INT64(11),
    FLOAT64(12),
    UINT128(13),
    INT128(14);

    val isIntegral: Boolean
        get() = this == UINT8 || this == INT8 || this == UINT16 || this == INT16 ||
            this == UINT32 || this == INT32 || this == UINT64 || this == INT64 || this == BOOL

    val isFloat: Boolean
        get() = this == FLOAT32 || this == FLOAT64

    /** Fixed on-disk size, or 0 when the value is variable length (string/array). */
    val scalarBytes: Int
        get() = when (this) {
            UINT8, INT8 -> 1
            UINT16, INT16 -> 2
            UINT32, INT32, FLOAT32, BOOL -> 4
            UINT64, INT64, FLOAT64 -> 8
            UINT128, INT128 -> 16
            else -> 0
        }

    companion object {
        fun fromId(id: Int): GgufType? = values().firstOrNull { it.id == id }
    }
}

/**
 * Tensor element types (ggml `ggml_type`). [blockSize]/[blockBytes] mirror
 * ggml's type traits so a file's weight size can be estimated from the tensor
 * table alone — i.e. without loading the model.
 */
enum class GgufDtype(val id: Int, val label: String, val blockSize: Int, val blockBytes: Int) {
    F32(0, "F32", 1, 4),
    F16(1, "F16", 1, 2),
    Q4_0(2, "Q4_0", 32, 18),
    Q4_1(3, "Q4_1", 32, 20),
    Q4_2(4, "Q4_2", 32, 18),
    Q4_3(5, "Q4_3", 32, 18),
    Q5_0(6, "Q5_0", 32, 22),
    Q5_1(7, "Q5_1", 32, 24),
    Q8_0(8, "Q8_0", 32, 36),
    Q8_1(9, "Q8_1", 32, 40),
    Q2_K(10, "Q2_K", 256, 84),
    Q3_K(11, "Q3_K", 256, 110),
    Q4_K(12, "Q4_K", 256, 144),
    Q5_K(13, "Q5_K", 256, 170),
    Q6_K(14, "Q6_K", 256, 210),
    Q8_K(15, "Q8_K", 256, 240),
    IQ2_XXS(16, "IQ2_XXS", 256, 66),
    IQ2_XS(17, "IQ2_XS", 256, 74),
    IQ3_XXS(18, "IQ3_XXS", 256, 98),
    IQ1_S(19, "IQ1_S", 256, 50),
    IQ4_NL(20, "IQ4_NL", 32, 18),
    IQ3_S(21, "IQ3_S", 256, 110),
    IQ2_S(22, "IQ2_S", 256, 80),
    IQ4_XS(23, "IQ4_XS", 256, 136),
    I8(24, "I8", 1, 1),
    I16(25, "I16", 1, 2),
    I32(26, "I32", 1, 4),
    I64(27, "I64", 1, 8),
    IQ1_M(28, "IQ1_M", 256, 56),
    BF16(29, "BF16", 1, 2);

    /** True for the k-quants, which carry the `_M`/`_S`/`_L` suffix users know. */
    val isKQuants: Boolean get() = label.contains("_K")

    fun bytesFor(elements: Long): Long {
        if (blockBytes <= 0 || elements <= 0) return 0
        val blocks = (elements + blockSize - 1) / blockSize
        return blocks * blockBytes
    }

    fun bitsPerWeight(): Double = blockBytes * 8.0 / blockSize

    companion object {
        fun fromId(id: Int): GgufDtype? = values().firstOrNull { it.id == id }
        fun label(id: Int): String = fromId(id)?.label ?: "type_$id"
    }
}

/**
 * A parsed GGUF metadata value.
 *
 * Large numeric arrays are kept (they are cheap and useful: token ids), huge
 * string arrays are counted but not materialised — a 128k-token vocabulary
 * would otherwise allocate megabytes for a screen that shows one number.
 */
sealed class GgufValue {
    data class Integer(val value: Long, val type: GgufType) : GgufValue()
    data class Real(val value: Double, val type: GgufType) : GgufValue()
    data class Flag(val value: Boolean) : GgufValue()
    data class Text(val value: String) : GgufValue()

    data class Items(
        val elementType: GgufType,
        val length: Long,
        /** Materialised numbers for small numeric arrays, otherwise null. */
        val numbers: LongArray?,
        /** Materialised strings for small string arrays, otherwise null. */
        val strings: List<String>?,
    ) : GgufValue()

    /** A value we recognised but deliberately did not decode. */
    data class Skipped(val type: GgufType, val bytes: Long) : GgufValue()

    val typeName: String
        get() = when (this) {
            is Integer -> type.name.lowercase()
            is Real -> type.name.lowercase()
            is Flag -> "bool"
            is Text -> "string"
            is Items -> "array<" + elementType.name.lowercase() + ">"
            is Skipped -> type.name.lowercase()
        }

    fun asLongOrNull(): Long? = when (this) {
        is Integer -> value
        is Real -> value.toLong()
        is Flag -> if (value) 1L else 0L
        else -> null
    }

    fun asDoubleOrNull(): Double? = when (this) {
        is Real -> value
        is Integer -> value.toDouble()
        else -> null
    }

    fun asStringOrNull(): String? = (this as? Text)?.value

    fun asBooleanOrNull(): Boolean? = when (this) {
        is Flag -> value
        is Integer -> value != 0L
        else -> null
    }

    /** Display form used by the metadata inspector. */
    fun render(truncateAt: Int = 120): String {
        val raw = when (this) {
            is Integer -> value.toString()
            is Real -> if (value == value.toLong().toDouble()) value.toLong().toString()
                else value.toString()
            is Flag -> value.toString()
            is Text -> "\"" + value.replace("\n", "\\n") + "\""
            is Items -> {
                val preview = when {
                    strings != null -> strings.take(4).joinToString(", ") { "\"" + it + "\"" }
                    numbers != null -> numbers.take(4).joinToString(", ")
                    else -> ""
                }
                "[$length of ${elementType.name.lowercase()}${if (preview.isEmpty()) "" else ": $preview"}]"
            }
            is Skipped -> "<${bytes}B ${type.name.lowercase()}>"
        }
        return if (raw.length <= truncateAt) raw else raw.take(truncateAt) + "…"
    }
}
