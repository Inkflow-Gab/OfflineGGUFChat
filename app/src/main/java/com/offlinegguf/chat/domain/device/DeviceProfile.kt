package com.offlinegguf.chat.domain.device

/**
 * Everything about the handset that decides whether a model will run, and how
 * fast. Kept as a plain data class (no Android types) so the fit maths and the
 * model advisor can be unit tested on a JVM.
 */
data class DeviceProfile(
    val ramBytes: Long,
    /** `ActivityManager.memoryClass` in bytes — the per-process heap ceiling. */
    val javaHeapBytes: Long,
    val largeJavaHeapBytes: Long,
    val cores: Int,
    val maxCpuMhz: Double,
    val abiList: List<String>,
    val apiLevel: Int,
    val freeStorageBytes: Long,
    val totalStorageBytes: Long,
    /** Page size the kernel runs with (4096 or 16384), 0 when unknown. */
    val pageSizeBytes: Long,
    /** `ActivityManager.isLowRamDevice` — a stronger signal than the RAM total. */
    val lowRamDevice: Boolean = false,
    /** True when the native engine was compiled into this build. */
    val engineAvailable: Boolean,
) {
    val tier: DeviceTier get() = DeviceTier.of(ramBytes, cores, lowRamDevice)

    val primaryAbi: String get() = abiList.firstOrNull() ?: "unknown"

    val isArm64: Boolean get() = abiList.any { it == "arm64-v8a" }

    /**
     * Keep a core available for Android/UI. On a one- or two-core phone, use
     * fewer workers rather than forcing two threads and starving the foreground.
     */
    val suggestedThreads: Int
        get() = if (lowRamDevice) 1 else (cores - 1).coerceIn(1, 4)

    /** Smaller prompt batches reduce temporary allocations on memory-tight phones. */
    val suggestedBatch: Int
        get() = when {
            lowRamDevice || ramBytes < RAM_3GB -> 64
            ramBytes < RAM_4GB -> 128
            else -> 512
        }

    /**
     * RAM the app may realistically spend on a model. Everything else belongs
     * to the OS, the launcher and whatever the user already has open; being
     * optimistic here is what makes an app get killed mid-generation.
     */
    val modelRamBudgetBytes: Long
        get() {
            val fraction = when (tier) {
                DeviceTier.LIGHT -> 0.45
                DeviceTier.MEDIUM -> 0.52
                DeviceTier.STRONG -> 0.58
                DeviceTier.FLAGSHIP -> 0.62
            }
            return (ramBytes * fraction).toLong().coerceIn(MIN_BUDGET, MAX_BUDGET)
        }

    /** Largest context worth attempting, from the same budget. */
    val suggestedContext: Int
        get() = when {
            ramBytes < RAM_3GB -> 1024
            ramBytes < RAM_4GB -> 2048
            ramBytes < RAM_6GB -> 4096
            ramBytes < RAM_10GB -> 8192
            else -> 16384
        }

    companion object {
        const val RAM_3GB = 3L * 1024 * 1024 * 1024
        const val RAM_4GB = 4L * 1024 * 1024 * 1024
        const val RAM_6GB = 6L * 1024 * 1024 * 1024
        const val RAM_10GB = 10L * 1024 * 1024 * 1024
        const val MIN_BUDGET = 512L * 1024 * 1024
        const val MAX_BUDGET = 14L * 1024 * 1024 * 1024

        /** Fallback for emulators/CI where probing returns nothing useful. */
        val Unknown = DeviceProfile(
            ramBytes = RAM_4GB,
            javaHeapBytes = 256L * 1024 * 1024,
            largeJavaHeapBytes = 512L * 1024 * 1024,
            cores = 8,
            maxCpuMhz = 0.0,
            abiList = listOf("arm64-v8a"),
            apiLevel = 34,
            freeStorageBytes = 8L * 1024 * 1024 * 1024,
            totalStorageBytes = 64L * 1024 * 1024 * 1024,
            pageSizeBytes = 4096,
            engineAvailable = false,
        )
    }
}

/** Coarse capability bucket used for copy and for the speed estimate. */
enum class DeviceTier(val label: String) {
    LIGHT("Light"),
    MEDIUM("Mid-range"),
    STRONG("Upper mid-range"),
    FLAGSHIP("Flagship");

    companion object {
        fun of(ramBytes: Long, cores: Int, lowRamDevice: Boolean = false): DeviceTier = when {
            lowRamDevice || ramBytes < DeviceProfile.RAM_3GB || cores <= 4 -> LIGHT
            ramBytes < DeviceProfile.RAM_6GB -> MEDIUM
            ramBytes < DeviceProfile.RAM_10GB -> STRONG
            else -> FLAGSHIP
        }
    }
}
