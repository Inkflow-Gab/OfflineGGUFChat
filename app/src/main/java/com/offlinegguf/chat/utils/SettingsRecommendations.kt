package com.offlinegguf.chat.utils

import android.content.Context

object SettingsRecommendations {

    data class Recommendation(
        val title: String,
        val value: Any,
        val reason: String
    )

    fun all(context: Context): List<Recommendation> {
        val ramGb = PhoneInfo.totalRamGB(context)
        val isLowEnd = ramGb < 4.0

        return listOf(
            Recommendation(
                "Max model size",
                if (isLowEnd) "Under 2 GB" else "${"%.1f".format(PhoneInfo.recommendedMaxModelSizeGB(context))} GB",
                "Larger models will crash or be extremely slow on ${
                    "%.1f".format(ramGb)
                } GB RAM phones."
            ),
            Recommendation(
                "Context size (n_ctx)",
                PhoneInfo.recommendedNctx(context),
                "Based on your RAM. Lower = faster, less memory."
            ),
            Recommendation(
                "CPU threads",
                PhoneInfo.recommendedThreads(context),
                "Your device has ${PhoneInfo.cpuCores()} CPU cores."
            ),
            Recommendation(
                "Quantization",
                PhoneInfo.recommendedQuantization(context),
                "Q2_K/Q4_K_M = small & fast. Q8_0/F16 = large & slow."
            ),
            Recommendation(
                "Memory mapping (mmap)",
                true,
                "Required so 1 GB+ files don't fill RAM."
            ),
            Recommendation(
                "MLOCK",
                false,
                "Disable on low-RAM devices — can crash the system."
            )
        )
    }
}
