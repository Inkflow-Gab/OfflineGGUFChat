package com.offlinegguf.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.data.catalog.CatalogModel
import com.offlinegguf.chat.data.catalog.ModelAdvisor
import com.offlinegguf.chat.data.catalog.ModelStyle
import com.offlinegguf.chat.domain.device.DeviceProfile
import com.offlinegguf.chat.domain.device.DeviceProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DiscoverUiState(
    val profile: DeviceProfile = DeviceProfile.Unknown,
    val profileSummary: String = "",
    val prefs: ModelAdvisor.Prefs = ModelAdvisor.Prefs(),
    val catalogCount: Int = 0,
    val catalogUpdated: String = "",
    val catalogNote: String = "",
    val headline: String = "",
    val recommendations: List<ModelAdvisor.Recommendation> = emptyList(),
    val uncensored: List<ModelAdvisor.Recommendation> = emptyList(),
    val installedNames: Set<String> = emptySet(),
    val computing: Boolean = false,
    val error: String? = null,
)

/**
 * "What should I download for *this* phone?"
 *
 * The app holds no INTERNET permission, so nothing is fetched here. Instead the
 * bundled catalogue is scored against the probed device with the same arithmetic
 * the loader uses ([com.offlinegguf.chat.domain.fit.ModelFit]), and each result
 * links out to Hugging Face in the browser. Download there, then "Open with" this
 * app and the file is imported and verified.
 */
class DiscoverViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(profile = container.deviceProfile, computing = true) }
        viewModelScope.launch {
            val settings = container.settings.settings.first()
            val prefs = ModelAdvisor.Prefs(
                style = settings.style(),
                uncensored = settings.advisorUncensored,
                maxDownloadBytes = settings.advisorMaxDownloadGb.toLong() * 1024 * 1024 * 1024,
                preferSpeed = settings.advisorPreferSpeed,
            )
            val catalog = container.catalog
            _state.update {
                it.copy(
                    prefs = prefs,
                    catalogCount = catalog.entries.size,
                    catalogUpdated = catalog.updated,
                    catalogNote = catalog.note,
                    profileSummary = DeviceProbe.describe(container.deviceProfile),
                )
            }
            recompute(prefs)

            container.modelRepository.observeAll().collect { rows ->
                _state.update { current ->
                    current.copy(installedNames = rows.map { it.displayName.lowercase() }.toSet())
                }
            }
        }
    }

    private suspend fun recompute(prefs: ModelAdvisor.Prefs) = withContext(Dispatchers.Default) {
        _state.update { it.copy(computing = true, prefs = prefs) }
        val profile = container.deviceProfile
        val entries: List<CatalogModel> = container.catalog.entries
        if (entries.isEmpty()) {
            _state.update {
                it.copy(
                    computing = false,
                    recommendations = emptyList(),
                    headline = "The bundled catalogue is empty.",
                    error = null,
                )
            }
            return@withContext
        }
        val ranked = ModelAdvisor.recommend(profile, entries, prefs, limit = 8)
        val refusalFree = ModelAdvisor.recommend(
            profile = profile,
            catalog = container.catalog.uncensored(),
            prefs = prefs.copy(uncensored = true),
            limit = 4,
        )
        _state.update {
            it.copy(
                computing = false,
                recommendations = ranked,
                uncensored = refusalFree,
                headline = ModelAdvisor.headline(profile, entries, prefs),
            )
        }
    }

    fun setStyle(style: ModelStyle) {
        viewModelScope.launch {
            container.settings.setAdvisorStyle(style)
            recompute(_state.value.prefs.copy(style = style))
        }
    }

    fun toggleUncensored() {
        val next = !_state.value.prefs.uncensored
        viewModelScope.launch {
            container.settings.setAdvisorUncensored(next)
            recompute(_state.value.prefs.copy(uncensored = next))
        }
    }

    fun togglePreferSpeed() {
        val next = !_state.value.prefs.preferSpeed
        viewModelScope.launch {
            container.settings.setAdvisorPreferSpeed(next)
            recompute(_state.value.prefs.copy(preferSpeed = next))
        }
    }

    fun setMaxDownloadGb(gb: Int) {
        val bounded = gb.coerceIn(1, 64)
        viewModelScope.launch {
            container.settings.setAdvisorMaxDownloadGb(bounded)
            recompute(_state.value.prefs.copy(maxDownloadBytes = bounded.toLong() * 1024 * 1024 * 1024))
        }
    }

    /** Is this catalogue entry already sitting on the phone? */
    fun isInstalled(model: CatalogModel): Boolean {
        val installed = _state.value.installedNames
        return installed.any { name ->
            name.contains(model.family.lowercase()) &&
                (model.quant.isBlank() || name.contains(model.quant.lowercase()))
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { DiscoverViewModel(container) }
        }
    }
}
