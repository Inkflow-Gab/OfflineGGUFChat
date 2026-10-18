package com.offlinegguf.chat.inference

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.offlinegguf.chat.utils.PhoneInfo
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

object GgufLoader {

    private const val TAG = "GgufLoader"
    private const val GGUF_MAGIC = "GGUF"

    data class ModelInfo(
        val path: String,
        val sizeBytes: Long,
        val sizeGB: Double,
        val isValidGguf: Boolean,
        val architecture: String? = null,
        val nCtxTrain: Int? = null,
        val nVocab: Int? = null,
        val quantization: String? = null,
        val error: String? = null,
        /** Whether this model is safe to load on current device */
        val fitsRam: Boolean = false,
        /** Human-readable warning if model is too big */
        val ramWarning: String? = null,
        /** Recommended n_ctx for this model on this device */
        val recommendedCtx: Int = 512,
        /** Recommended max model size for this device */
        val maxRecommendedSizeGB: Double = 3.0
    )

    fun validate(context: Context, path: String): ModelInfo {
        val file = File(path)
        if (!file.exists()) {
            return ModelInfo(path, 0, 0, false, error = "File not found")
        }

        val size = file.length()
        val sizeGB = size / (1024.0 * 1024.0 * 1024.0)
        val totalRamGB = PhoneInfo.totalRamGB(context)
        val availableRamGB = PhoneInfo.availableRamGB(context)

        // Conservative: model should use at most 60% of total RAM
        val maxSafeSizeGB = totalRamGB * 0.6
        val fitsRam = sizeGB <= maxSafeSizeGB

        var ramWarning: String? = null
        if (!fitsRam) {
            ramWarning = "⚠️ Model (${
                "%.2f".format(sizeGB)
            } GB) is too large for this phone (${
                "%.1f".format(totalRamGB)
            } GB RAM). Max safe: ${
                "%.2f".format(maxSafeSizeGB)
            } GB. Use a smaller quant (Q4_K_M or Q2_K)."
        } else if (sizeGB > totalRamGB * 0.4) {
            ramWarning = "⚠️ Large model (${
                "%.2f".format(sizeGB)
            } GB). May be slow on ${
                "%.1f".format(totalRamGB)
            } GB RAM phone."
        }

        // Recommended context based on RAM
        val recommendedCtx = when {
            totalRamGB < 3.0 -> 512
            totalRamGB < 6.0 -> 1024
            totalRamGB < 8.0 -> 2048
            else -> 4096
        }

        var isValid = false
        var arch: String? = null
        var nCtx: Int? = null
        var nVocab: Int? = null
        var quant: String? = null
        var parseError: String? = null

        var fis: FileInputStream? = null
        var channel: FileChannel? = null
        var mmap: MappedByteBuffer? = null
        try {
            fis = FileInputStream(file)
            channel = fis.channel
            mmap = channel.map(FileChannel.MapMode.READ_ONLY, 0, kotlin.math.min(65536, size).toInt())

            if (mmap!!.remaining() >= 4) {
                val magic = ByteArray(4)
                mmap.get(magic)
                if (String(magic, charsets.US_ASCII) == GGUF_MAGIC) {
                    isValid = true
                } else {
                    parseError = "Invalid GGUF magic bytes"
                }
            } else {
                parseError = "File too small to be a GGUF"
            }

            if (isValid && mmap!!.remaining() >= 8) {
                val version = mmap.getInt() and 0xFFFFFFFF
                Log.d(TAG, "GGUF version=$version size=$size bytes")
            }
        } catch (e: Exception) {
            parseError = e.message
            Log.e(TAG, "validate failed", e)
        } finally {
            channel?.close()
            fis?.close()
        }

        return ModelInfo(
            path, size, sizeGB, isValid, arch, nCtx, nVocab, quant,
            parseError, fitsRam, ramWarning, recommendedCtx, maxSafeSizeGB
        )
    }

    fun contextFilePath(context: Context, pfd: ParcelFileDescriptor): String {
        val fd = pfd.fileDescriptor
        return "/proc/self/fd/${fd.fd}"
    }
}
