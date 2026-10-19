package com.offlinegguf.chat.domain.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

/**
 * Reads the handset once and turns it into a [DeviceProfile].
 *
 * Every probe is defensive: sysfs is unreadable on many kernels, `StatFs` can
 * throw on odd storage layouts, and emulators report nonsense. A failed probe
 * falls back to a conservative default rather than crashing the settings screen.
 */
object DeviceProbe {

    fun profile(context: Context, engineAvailable: Boolean): DeviceProfile {
        val activityManager = runCatching {
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }.getOrNull()

        val memoryInfo = runCatching {
            ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        }.getOrNull()

        val ramBytes = memoryInfo?.totalMem?.takeIf { it > 0 } ?: DeviceProfile.RAM_4GB
        val heapMb = (activityManager?.memoryClass ?: 0).takeIf { it > 0 } ?: 256
        val largeHeapMb = (activityManager?.largeMemoryClass ?: 0).takeIf { it > heapMb } ?: (heapMb * 2)
        val lowRam = runCatching { activityManager?.isLowRamDevice == true }.getOrDefault(false)

        val cores = runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(4)
            .coerceIn(1, 32)

        val storage = storageBytes()

        return DeviceProfile(
            ramBytes = ramBytes,
            javaHeapBytes = heapMb.toLong() * 1024 * 1024,
            largeJavaHeapBytes = largeHeapMb.toLong() * 1024 * 1024,
            cores = cores,
            maxCpuMhz = maxCpuMhz(),
            abiList = supportedAbis(),
            apiLevel = Build.VERSION.SDK_INT,
            freeStorageBytes = storage.first,
            totalStorageBytes = storage.second,
            pageSizeBytes = pageSizeBytes(),
            lowRamDevice = lowRam,
            performanceCores = performanceCores(cores),
            engineAvailable = engineAvailable,
        )
    }

    private fun supportedAbis(): List<String> = runCatching {
        Build.SUPPORTED_ABIS?.toList()?.filter { it.isNotBlank() } ?: emptyList()
    }.getOrDefault(emptyList())

    /** (free, total) bytes on the partition that holds app-private files. */
    private fun storageBytes(): Pair<Long, Long> = runCatching {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        stat.availableBytes to stat.totalBytes
    }.getOrElse { 0L to 0L }

    /**
     * Peak CPU clock in MHz, read from sysfs. Returns 0.0 when the kernel hides
     * cpufreq (common on newer devices) — callers must treat 0 as "unknown".
     */
    fun maxCpuMhz(): Double = runCatching {
        val root = File("/sys/devices/system/cpu")
        var best = 0.0
        root.listFiles { file -> file.isDirectory && file.name.startsWith("cpu") }
            ?.forEach { cpu ->
                val node = File(cpu, "cpufreq/cpuinfo_max_freq")
                if (node.canRead()) {
                    val khz = node.readText().trim().toDoubleOrNull() ?: return@forEach
                    if (khz / 1000.0 > best) best = khz / 1000.0
                }
            }
        best
    }.getOrDefault(0.0)

    /**
     * How many cores sit in the fastest clock bin — the "big" cluster on a
     * big.LITTLE SoC. Read from sysfs the same defensive way as [maxCpuMhz].
     *
     * Returns 0 when the kernel hides frequencies (then callers fall back to
     * all cores), and 0 when every core reports the same clock (a uniform CPU
     * has no little cluster to avoid).
     */
    fun performanceCores(cores: Int): Int = runCatching {
        if (cores <= 2) return 0
        val root = File("/sys/devices/system/cpu")
        val clocks = (0 until cores).map { index ->
            val node = File(root, "cpu$index/cpufreq/cpuinfo_max_freq")
            if (node.canRead()) node.readText().trim().toLongOrNull() ?: 0L else 0L
        }
        if (clocks.any { it <= 0L }) return 0
        val fastest = clocks.maxOrNull() ?: 0L
        if (fastest <= 0L) return 0
        // Within 15% of the top bin counts as a performance core. A uniform CPU
        // lands here with every core, which the caller treats as "no clusters".
        val big = clocks.count { it >= (fastest * 0.85).toLong() }
        if (big >= cores) 0 else big
    }.getOrDefault(0)

    /**
     * Kernel page size. Almost always 4096, but 16 KB page devices exist and a
     * native library linked without `max-page-size=16384` will not load there —
     * which is exactly what the diagnostics card wants to be able to explain.
     *
     * Read reflectively: `Os.sysconf` exists on every supported API level, but
     * referencing `OsConstants._SC_PAGESIZE` directly would fail to compile on
     * any SDK where the constant is absent, and this is a diagnostics nicety,
     * not a reason to break the build.
     */
    fun pageSizeBytes(): Long = runCatching {
        val os = Class.forName("android.system.Os")
        val constants = Class.forName("android.system.OsConstants")
        val key = constants.getField("_SC_PAGESIZE").getInt(null)
        val sysconf = os.getMethod("sysconf", Int::class.javaPrimitiveType)
        (sysconf.invoke(null, key) as Long).takeIf { it > 0 } ?: 4096L
    }.getOrDefault(0L)

    /** One-line description for the settings screen. */
    fun describe(profile: DeviceProfile): String = buildString {
        append("${(profile.ramBytes / (1024.0 * 1024 * 1024)).let { String.format(java.util.Locale.US, "%.1f", it) }} GB RAM")
        append(" · ${profile.cores} cores")
        if (profile.performanceCores in 1..profile.cores) {
            append(" (${profile.performanceCores} performance)")
        }
        append(" · ${profile.tier.label}")
        if (profile.maxCpuMhz > 0) append(" · ${profile.maxCpuMhz.toInt()} MHz")
    }
}
