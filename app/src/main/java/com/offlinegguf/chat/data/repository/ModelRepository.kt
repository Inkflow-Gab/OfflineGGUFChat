package com.offlinegguf.chat.data.repository

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.offlinegguf.chat.data.gguf.GgufReader
import com.offlinegguf.chat.data.local.AppDatabase
import com.offlinegguf.chat.data.local.LocalModelEntity
import com.offlinegguf.chat.domain.device.DeviceProfile
import com.offlinegguf.chat.domain.fit.ModelFit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

sealed class ImportResult {
    data class Success(val model: LocalModelEntity) : ImportResult()
    data class Failed(val message: String) : ImportResult()
}

data class ScanReport(
    val scanned: Int = 0,
    val added: Int = 0,
    val updated: Int = 0,
    val invalid: Int = 0,
    val errors: List<String> = emptyList(),
)

/**
 * Everything about GGUF files on the device: finding them, reading their headers,
 * importing them from other apps, and remembering per-model settings.
 *
 * Import is a real copy into app-private storage with progress. An earlier
 * version of this app tried to hand the engine `/proc/self/fd/N` for a
 * `content://` uri; that works until the provider revokes the grant or the file
 * is moved, and llama.cpp needs a seekable path with a stable inode.
 */
class ModelRepository(
    private val context: Context,
    db: AppDatabase,
    private val reader: GgufReader = GgufReader(),
) {
    private val dao = db.models()

    val modelsDir: File = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    private val externalModelsDir: File? =
        context.getExternalFilesDir(null)?.let { File(it, "models").apply { if (!exists()) mkdirs() } }

    fun observeAll(): Flow<List<LocalModelEntity>> = dao.observeAll()

    fun observeLoadable(): Flow<List<LocalModelEntity>> = dao.observeLoadable()

    fun observeByPath(path: String): Flow<LocalModelEntity?> = dao.observeByPath(path)

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun byPath(path: String): LocalModelEntity? = withContext(Dispatchers.IO) { dao.findByPath(path) }

    suspend fun markUsed(path: String) = withContext(Dispatchers.IO) {
        dao.markUsed(path, System.currentTimeMillis())
    }

    suspend fun rename(path: String, name: String) = withContext(Dispatchers.IO) {
        dao.rename(path, name.trim().ifBlank { File(path).nameWithoutExtension })
    }

    suspend fun updateEngineSettings(
        path: String,
        nCtx: Int,
        threads: Int,
        batch: Int,
        gpuLayers: Int,
        useMmap: Boolean,
        kvType: String,
    ) = withContext(Dispatchers.IO) {
        dao.updateEngineSettings(path, nCtx, threads, batch, gpuLayers, useMmap, kvType)
    }

    suspend fun updateSampling(
        path: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        maxTokens: Int,
        seed: Int,
    ) = withContext(Dispatchers.IO) {
        dao.updateSampling(path, temperature, topP, topK, minP, repeatPenalty, repeatLastN, maxTokens, seed)
    }

    /** Reads a header without storing anything. */
    suspend fun probe(file: File): GgufReader.Probe = withContext(Dispatchers.IO) { reader.read(file) }

    /** Reads a header and upserts the row, keeping any settings already saved. */
    suspend fun probeAndStore(
        file: File,
        sourceUrl: String? = null,
        catalogId: String? = null,
    ): LocalModelEntity = withContext(Dispatchers.IO) {
        val probe = reader.read(file)
        val existing = dao.findByPath(file.absolutePath)
        val entity = toEntity(file, probe, existing, sourceUrl, catalogId)
        dao.upsert(entity)
        entity
    }

    private fun toEntity(
        file: File,
        probe: GgufReader.Probe,
        existing: LocalModelEntity?,
        sourceUrl: String?,
        catalogId: String?,
    ): LocalModelEntity {
        val displayName = probe.name.ifBlank { file.nameWithoutExtension.replace('_', ' ') }
        return LocalModelEntity(
            path = file.absolutePath,
            fileName = file.name,
            displayName = existing?.displayName?.takeIf { it.isNotBlank() && it != existing.fileName } ?: displayName,
            sizeBytes = file.length(),
            valid = probe.loadable,
            errorText = probe.error,
            ggufVersion = probe.version,
            architecture = probe.architecture,
            quantization = probe.quantization,
            contextLength = probe.contextLength.toInt(),
            vocabSize = probe.vocabSize.toInt(),
            blockCount = probe.blockCount.toInt(),
            embeddingLength = probe.embeddingLength.toInt(),
            paramCount = probe.paramCount,
            weightBytes = probe.weightBytes,
            tensorCount = probe.tensorCount.toInt(),
            hasChatTemplate = !probe.chatTemplate.isNullOrBlank(),
            multimodal = probe.multimodal,
            bitsPerWeight = probe.bitsPerWeight,
            sourceUrl = sourceUrl ?: existing?.sourceUrl,
            catalogId = catalogId ?: existing?.catalogId,
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            lastUsedAt = existing?.lastUsedAt ?: 0L,
            useCount = existing?.useCount ?: 0,
            nCtx = existing?.nCtx ?: 0,
            threads = existing?.threads ?: 0,
            batch = existing?.batch ?: 0,
            gpuLayers = existing?.gpuLayers ?: 0,
            useMmap = existing?.useMmap ?: true,
            kvType = existing?.kvType ?: "f16",
            temperature = existing?.temperature ?: 0.7f,
            topP = existing?.topP ?: 0.95f,
            topK = existing?.topK ?: 40,
            minP = existing?.minP ?: 0.05f,
            repeatPenalty = existing?.repeatPenalty ?: 1.1f,
            repeatLastN = existing?.repeatLastN ?: 256,
            maxTokens = existing?.maxTokens ?: 512,
            seed = existing?.seed ?: -1,
        )
    }

    /**
     * Walks the known model directories and upserts anything new. Files that are
     * gone are dropped from the database so the picker never offers a dead path.
     */
    suspend fun scan(extraDirs: List<File> = emptyList()): ScanReport = withContext(Dispatchers.IO) {
        val dirs = listOfNotNull(modelsDir, externalModelsDir) + extraDirs
        val errors = ArrayList<String>()
        var scanned = 0
        var added = 0
        var updated = 0
        var invalid = 0
        val seen = HashSet<String>()

        for (dir in dirs) {
            val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".gguf", ignoreCase = true) }
                ?: continue
            for (file in files.sortedBy { it.name.lowercase() }) {
                seen += file.absolutePath
                scanned++
                val probe = runCatching { reader.read(file) }.getOrElse {
                    errors += "${file.name}: ${it.message ?: it.javaClass.simpleName}"
                    invalid++
                    continue
                }
                val existing = dao.findByPath(file.absolutePath)
                val entity = toEntity(file, probe, existing, null, null)
                dao.upsert(entity)
                if (existing == null) added++ else updated++
                if (!probe.loadable) {
                    invalid++
                    probe.error?.let { errors += "${file.name}: $it" }
                }
            }
        }

        // Drop rows whose file no longer exists.
        for (row in dao.listAll()) {
            if (row.path !in seen && !File(row.path).exists()) dao.deleteByPath(row.path)
        }

        ScanReport(scanned, added, updated, invalid, errors.take(10))
    }

    /**
     * Copies a `content://` document into app storage.
     *
     * [onProgress] receives (bytes copied, total or -1). Runs on IO; the caller is
     * expected to keep the screen awake and show the progress.
     */
    suspend fun importFrom(
        uri: Uri,
        displayNameHint: String? = null,
        sourceUrl: String? = null,
        catalogId: String? = null,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): ImportResult = withContext(Dispatchers.IO) {
        val name = sanitize(displayNameHint ?: queryDisplayName(uri) ?: "model-${System.currentTimeMillis()}.gguf")
        val total = querySize(uri)
        val target = uniqueFile(modelsDir, name)

        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                    output.flush()
                    done
                }
            } ?: -1L
        }.getOrElse {
            runCatching { target.delete() }
            return@withContext ImportResult.Failed("copy failed: ${it.message ?: it.javaClass.simpleName}")
        }

        if (copied <= 0L) {
            runCatching { target.delete() }
            return@withContext ImportResult.Failed("nothing was read from that file")
        }

        val probe = runCatching { reader.read(target) }.getOrElse {
            return@withContext ImportResult.Failed("copied, but unreadable: ${it.message ?: it.javaClass.simpleName}")
        }
        if (!probe.loadable) {
            // Keep the file: the detail screen explains exactly what is wrong with
            // it, which is more useful than a silent delete after a long copy.
            val entity = toEntity(target, probe, null, sourceUrl, catalogId)
            dao.upsert(entity)
            return@withContext ImportResult.Failed(probe.error ?: "not a usable GGUF file")
        }

        val entity = toEntity(target, probe, null, sourceUrl, catalogId)
        dao.upsert(entity)
        ImportResult.Success(entity)
    }

    /** Deletes the row and, when the file lives in app storage, the file too. */
    suspend fun delete(path: String, deleteFile: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        dao.deleteByPath(path)
        if (!deleteFile) return@withContext true
        val file = File(path)
        val ours = file.absolutePath.startsWith(modelsDir.absolutePath) ||
            (externalModelsDir?.let { file.absolutePath.startsWith(it.absolutePath) } == true)
        if (ours && file.exists()) runCatching { file.delete() }.getOrDefault(false) else true
    }

    suspend fun storageUsedBytes(): Long = withContext(Dispatchers.IO) {
        listOfNotNull(modelsDir, externalModelsDir).sumOf { dir ->
            dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }
    }

    /** Settings that fit this phone, for a file whose header we just read. */
    fun suggestedSettings(profile: DeviceProfile, probe: GgufReader.Probe): ModelFit.Settings =
        ModelFit.assess(profile, probe.toSpec()).settings

    /** Display name for a SAF uri — used for imports and attachments alike. */
    fun displayName(uri: Uri): String? = queryDisplayName(uri)

    private fun queryDisplayName(uri: Uri): String? =
        queryOpenable(uri) { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }

    private fun querySize(uri: Uri): Long =
        queryOpenable(uri) { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else -1L
        } ?: -1L

    private fun <T> queryOpenable(uri: Uri, block: (Cursor) -> T): T? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor -> block(cursor) }
    }.getOrNull()

    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._\\-+ ]"), "_").trim()
        val withExtension = if (base.endsWith(".gguf", ignoreCase = true)) base else "$base.gguf"
        return withExtension.take(120).ifBlank { "model.gguf" }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        var counter = 1
        val stem = name.removeSuffix(".gguf").removeSuffix(".GGUF")
        while (candidate.exists()) {
            candidate = File(dir, "$stem-$counter.gguf")
            counter++
            if (counter > 500) break
        }
        return candidate
    }
}

/** Maps a parsed header onto the fit engine's input. */
fun GgufReader.Probe.toSpec(): ModelFit.Spec = ModelFit.Spec(
    weightBytes = if (weightBytes > 0) weightBytes else fileSize.coerceAtLeast(0),
    paramCount = paramCount,
    contextLength = contextLength,
    blockCount = blockCount,
    embeddingLength = embeddingLength,
    headCount = headCount,
    headCountKv = metadata["$architecture.attention.head_count_kv"]?.asLongOrNull() ?: 0,
    quantization = quantization,
    hasChatTemplate = !chatTemplate.isNullOrBlank(),
    multimodal = multimodal,
    vocabSize = vocabSize,
    tokenCount = tokenCount,
    version = version,
    valid = loadable,
    error = error,
)
