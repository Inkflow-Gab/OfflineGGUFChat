package com.offlinegguf.chat

import android.app.Application
import com.offlinegguf.chat.data.local.AppDatabase

class App : Application() {

    val database by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        // Initialize any offline-only components here
    }
}
