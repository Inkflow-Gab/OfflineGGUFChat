package com.offlinegguf.chat.ui.viewmodel

import android.app.Application
import android.app.Activity
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.offlinegguf.chat.domain.model.GgufModel
import com.offlinegguf.chat.inference.GgufLoader
import kotlinx.coroutines.launch

class ModelPickerViewModel(application: Application) : AndroidViewModel(application) {

    var onModelPicked: ((GgufModel) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var isLoading = false

    fun pickFile(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream"))
        }
        activity.startActivityForResult(intent, PICK_GGUF_REQUEST)
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != PICK_GGUF_REQUEST || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        viewModelScope.launch {
            isLoading = true
            try {
                val pfd = application.contentResolver.openFileDescriptor(uri, "r") ?: return@launch
                val path = GgufLoader.contextFilePath(application, pfd)
                val info = GgufLoader.validate(path)
                val model = GgufModel(
                    path = path,
                    name = uri.lastPathSegment ?: java.io.File(path).name,
                    sizeBytes = info.sizeBytes,
                    isValid = info.isValidGguf,
                    architecture = info.architecture,
                    error = info.error
                )
                if (!model.isValid) {
                    onError?.invoke(model.error ?: "Invalid GGUF file")
                } else {
                    onModelPicked?.invoke(model)
                }
            } catch (e: Exception) {
                onError?.invoke(e.message ?: "Failed to open file")
            } finally {
                isLoading = false
            }
        }
    }

    companion object {
        const val PICK_GGUF_REQUEST = 1001
    }
}
