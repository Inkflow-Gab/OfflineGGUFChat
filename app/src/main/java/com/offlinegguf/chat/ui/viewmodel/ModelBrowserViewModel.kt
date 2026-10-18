package com.offlinegguf.chat.ui.viewmodel

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinegguf.chat.domain.model.GgufModel
import com.offlinegguf.chat.inference.GgufLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class ModelBrowserViewModel(application: android.app.Application) : AndroidViewModel(application) {

    var onModelSelected: ((GgufModel) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _modelsFound = MutableStateFlow(emptyList<GgufModel>())
    val modelsFound: StateFlow<List<GgufModel>> = _modelsFound

    fun scanFolder(activity: Activity, uri: Uri) {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                // Take persistable permission so we can access this folder later
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                val root = DocumentFile.fromTreeUri(application, uri)
                val models = mutableListOf<GgufModel>()
                walkDocumentFile(root, models)

                _modelsFound.value = models
            } catch (e: Exception) {
                onError?.invoke(e.message ?: "Scan failed")
            } finally {
                _isScanning.value = false
            }
        }
    }

    private fun walkDocumentFile(dir: DocumentFile?, out: MutableList<GgufModel>) {
        dir?.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                walkDocumentFile(file, out)
            } else if (file.name.lowercase().endsWith(".gguf")) {
                val path = file.uri?.path ?: file.name
                val info = GgufLoader.validate(path)
                out.add(GgufModel(
                    path = path,
                    name = file.name,
                    sizeBytes = file.length(),
                    isValid = info.isValidGguf,
                    architecture = info.architecture,
                    quantization = info.quantization,
                    error = info.error
                ))
            }
        }
    }
}
