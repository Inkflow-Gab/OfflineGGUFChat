package com.offlinegguf.chat.ui.viewmodel

import androidx.lifecycle.ViewModel

class SettingsViewModel : ViewModel() {
    // Placeholder for theme, default params, storage path, etc.
    var modelFolderPath: String? = null
    var defaultTemperature: Float = 0.7f
    var defaultCtx: Int = 1024
}
