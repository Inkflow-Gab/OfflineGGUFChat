package com.offlinegguf.chat

import android.app.Application
import android.content.Context
import android.net.Uri
import com.offlinegguf.chat.data.catalog.CatalogStore
import com.offlinegguf.chat.data.catalog.ModelAdvisor
import com.offlinegguf.chat.data.catalog.ModelCatalog
import com.offlinegguf.chat.data.local.AppDatabase
import com.offlinegguf.chat.data.repository.ChatRepository
import com.offlinegguf.chat.data.repository.ModelRepository
import com.offlinegguf.chat.data.repository.SkillRepository
import com.offlinegguf.chat.data.settings.SettingsStore
import com.offlinegguf.chat.domain.device.DeviceProbe
import com.offlinegguf.chat.domain.device.DeviceProfile
import com.offlinegguf.chat.inference.NativeLlamaEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Hand-rolled container: the app has four repositories and one engine, which does
 * not justify a dependency-injection framework or the build time one costs.
 *
 * Everything is `by lazy` so a cold start does not touch the database, open the
 * catalogue asset or load the native library until something actually needs it.
 */
class AppContainer(private val context: Context) {

    /** Outlives every screen; used for seeding and background scans. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** For view models that need SAF access (attachments, exports). */
    val applicationContext: Context get() = context.applicationContext

    val contentResolver get() = context.contentResolver

    val database: AppDatabase by lazy { AppDatabase.build(context) }

    val settings: SettingsStore by lazy { SettingsStore(context) }

    val engine: NativeLlamaEngine by lazy { NativeLlamaEngine() }

    /** Opt-in OpenAI-compatible client. Idle (no sockets) unless cloud is on. */
    val cloudEngine: com.offlinegguf.chat.data.cloud.CloudChatEngine by lazy {
        com.offlinegguf.chat.data.cloud.CloudChatEngine()
    }

    /** Probed once. RAM, cores and storage do not change during a session. */
    val deviceProfile: DeviceProfile by lazy {
        DeviceProbe.profile(context, engine.isAvailable)
    }

    val chatRepository: ChatRepository by lazy { ChatRepository(database) }
    val modelRepository: ModelRepository by lazy { ModelRepository(context, database) }
    val skillRepository: SkillRepository by lazy { SkillRepository(database) }

    val catalog: ModelCatalog by lazy { CatalogStore.load(context) }

    val advisor: ModelAdvisor get() = ModelAdvisor

    /**
     * Set when the app is opened from a file manager ("Open with" / share). The
     * models screen drains it and runs the import with a progress bar.
     */
    private val _pendingImport = MutableStateFlow<Uri?>(null)
    val pendingImport: StateFlow<Uri?> = _pendingImport

    fun requestImport(uri: Uri?) {
        if (uri != null) _pendingImport.value = uri
    }

    fun clearPendingImport() {
        _pendingImport.value = null
    }

    /**
     * Set when a skill is picked on the Skills screen. The chat screen drains it,
     * which is how "Use this skill" works across two separate view models.
     */
    private val _pendingSkill = MutableStateFlow<String?>(null)
    val pendingSkill: StateFlow<String?> = _pendingSkill

    fun requestSkill(slug: String?) {
        if (!slug.isNullOrBlank()) _pendingSkill.value = slug
    }

    fun clearPendingSkill() {
        _pendingSkill.value = null
    }

    /**
     * Set when another app shares *text* here. The SEND filter accepts every mime
     * type, so this app shows up in the text share sheet; the chat screen drains
     * this into the composer instead of ignoring the share.
     */
    private val _pendingPrompt = MutableStateFlow<String?>(null)
    val pendingPrompt: StateFlow<String?> = _pendingPrompt

    fun requestPrompt(text: String?) {
        if (!text.isNullOrBlank()) _pendingPrompt.value = text
    }

    fun clearPendingPrompt() {
        _pendingPrompt.value = null
    }

    /**
     * Work that should happen shortly after launch but must not block the first
     * frame: seeding built-in skills and picking up GGUF files the user copied in
     * by hand (adb push, a file manager, another app's download folder).
     */
    fun startup() {
        applicationScope.launch {
            runCatching { skillRepository.seedBuiltins() }
            runCatching { modelRepository.scan() }
        }
    }
}

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.startup()
    }

    override fun onTerminate() {
        runCatching { container.engine.close() }
        super.onTerminate()
    }
}

/** Convenience for composables and view models. */
val Context.appContainer: AppContainer
    get() = (applicationContext as App).container
