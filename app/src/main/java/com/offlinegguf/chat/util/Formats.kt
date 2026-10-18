package com.offlinegguf.chat.util

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Formatting helpers shared by the UI and the model report screens.
 *
 * Pure JVM on purpose: everything here is covered by unit tests and can be
 * exercised without an emulator.
 */
object Formats {

    private const val KIB = 1024L

    fun bytes(value: Long): String {
        if (value < 0) return "—"
        if (value < KIB) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var v = value.toDouble()
        var unit = -1
        while (v >= KIB.toDouble() && unit < units.size - 1) {
            v /= KIB.toDouble()
            unit++
        }
        val pattern = if (v >= 100.0) "%.0f %s" else "%.1f %s"
        return String.format(Locale.US, pattern, v, units[unit])
    }

    fun gigabytes(value: Long): String = String.format(Locale.US, "%.2f GB", value / (KIB.toDouble() * KIB * KIB))

    fun gigabytesOrBytes(value: Long): String =
        if (value >= KIB * KIB * KIB) gigabytes(value) else bytes(value)

    /** 8.03B, 1.5B, 723M — parameter counts as people write them on model cards. */
    fun paramCount(count: Long): String {
        if (count <= 0L) return "—"
        return when {
            count >= 1_000_000_000L -> String.format(Locale.US, "%.2fB", count / 1e9)
            count >= 1_000_000L -> String.format(Locale.US, "%.1fM", count / 1e6)
            else -> "$count"
        }
    }

    fun tokensPerSecond(tokens: Int, microseconds: Long): String {
        if (microseconds <= 0L || tokens <= 0) return "—"
        val seconds = microseconds / 1_000_000.0
        return String.format(Locale.US, "%.1f tok/s", tokens / max(seconds, 0.0001))
    }

    fun seconds(microseconds: Long): String {
        if (microseconds <= 0) return "—"
        val s = microseconds / 1_000_000.0
        return if (s < 60) String.format(Locale.US, "%.1fs", s)
        else String.format(Locale.US, "%dm %02ds", (s / 60).toInt(), (s % 60).toInt())
    }

    fun percent(fraction: Float): String = String.format(Locale.US, "%.0f%%", fraction * 100f)

    /** Two-decimal number with a fixed width so sliders do not jitter. */
    fun fixed(value: Double, digits: Int = 2): String {
        val pattern = "%." + min(max(digits, 0), 6) + "f"
        return String.format(Locale.US, pattern, value)
    }

    fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val delta = now - timestamp
        if (delta < 0) return "just now"
        val minutes = delta / 60_000
        val hours = delta / 3_600_000
        val days = delta / 86_400_000
        return when {
            delta < 60_000 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            days < 30 -> "${days / 7}w ago"
            days < 365 -> "${days / 30}mo ago"
            else -> "${days / 365}y ago"
        }
    }

    /** Compact one-line preview for chat list rows. */
    fun preview(text: String, limit: Int = 90): String {
        val flat = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= limit) flat else flat.take(limit - 1).trimEnd() + "…"
    }

    /** 4096 -> "4k", 131072 -> "128k", 512 -> "512". */
    fun contextTokens(value: Long): String {
        if (value <= 0) return "—"
        if (value < 1000) return value.toString()
        val k = value / 1000.0
        return if (value % 1000 == 0L) String.format(Locale.US, "%dk", (value / 1000))
        else String.format(Locale.US, "%.1fk", k)
    }

    fun bitsPerWeight(bytes: Long, parameters: Long): String {
        if (parameters <= 0L || bytes <= 0L) return "—"
        return String.format(Locale.US, "%.2f bpw", bytes * 8.0 / parameters)
    }
}
