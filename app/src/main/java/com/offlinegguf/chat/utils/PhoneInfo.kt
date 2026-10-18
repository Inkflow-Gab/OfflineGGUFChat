package com.offlinegguf.chat.utils

import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File

object PhoneInfo {

    /**
     * Total RAM in bytes (approx, from /proc/meminfo).
     */
    fun totalRamBytes(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.totalMem
    }

    fun totalRamGB(context: Context): Double = totalRamBytes(context) / (1024.0 * 1024.0 * 1024.0)

    fun availableRamGB(context: Context): Double {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.availMem / (1024.0 * 1024.0 * 1024.0)
    }

    fun cpuCores(): Int = Runtime.getRuntime().availableProcessors()

    fun storageTotalBytes(path: String = "/data"): Long {
        val stat = StatFs(path)
        return stat.blockSizeLong * stat.blockCountLong
    }

    fun storageFreeBytes(path: String = "/data"): Long {
        val stat = StatFs(path)
        return stat.blockSizeLong * stat.availableBlocksLong
    }

    fun storageFreeGB(): Double = storageFreeBytes() / (1024.0 * 1024.0 * 1024.0)

    fun androidVersion(): String = Build.VERSION.RELEASE

    fun sdkInt(): Int = Build.VERSION.SDK_INT

    fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    fun isLowEndDevice(context: Context): Boolean = totalRamGB(context) < 4.0

    fun isMidRangeDevice(context: Context): Boolean = totalRamGB(context) >= 4.0 && totalRamGB(context) < 8.0

    fun isHighEndDevice(context: Context): Boolean = totalRamGB(context) >= 8.0

    fun recommendedNctx(context: Context): Int = when {
        totalRamGB(context) < 3.0 -> 512
        totalRamGB(context) < 6.0 -> 1024
        totalRamGB(context) < 12.0 -> 2048
        else -> 4096
    }

    fun recommendedThreads(context: Context): Int = cpuCores().coerceAtLeast(2).coerceAtMost(8)

    fun recommendedMaxModelSizeGB(context: Context): Double = when {
        totalRamGB(context) < 3.0 -> 3.0
        totalRamGB(context) < 6.0 -> 6.0
        totalRamGB(context) < 12.0 -> 10.0
        else -> 20.0
    }

    fun recommendedQuantization(context: Context): String = when {
        totalRamGB(context) < 4.0 -> "Q4_K_M (4-bit) — smallest, fastest"
        totalRamGB(context) < 8.0 -> "Q5_K_M or Q6_K (5–6-bit) — balanced"
        else -> "Q8_0 or F16 (8-bit / 16-bit) — highest quality"
    }

    fun summary(context: Context): String {
        return """
            Device: ${deviceModel()}
            Android: $androidVersion() (SDK $sdkInt())
            RAM: ${"%.1f".format(totalRamGB(context))} GB total, ${"%.1f".format(availableRamGB(context))} GB free
            CPU cores: $cpuCores()
            Storage free: ${"%.1f".format(storageFreeGB())} GB
            Recommended context: ${recommendedNctx(context)}
            Recommended threads: ${recommendedThreads(context)}
            Recommended quant: ${recommendedQuantization(context)}
        """.trimIndent()
    }
}
