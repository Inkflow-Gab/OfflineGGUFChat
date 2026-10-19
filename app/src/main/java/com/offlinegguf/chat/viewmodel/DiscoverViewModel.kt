package com.offlinegguf.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.data.catalog.CatalogModel
import com.offlinegguf.chat.data.catalog.ModelAdvisor
import com.offlinegguf.chat.data.catalog.ModelStyle
import com.offlinegguf.chat.domain.device.DeviceProbe
import com.offlinegguf.chat.domain.device.DeviceProfile
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
    /** The one-tap "start here" answer: small download, comfortable fit. */
    val starterPick: ModelAdvisor.Recommendation? = null,
    /** 18+ confirmed by the user; the refusal-free shelf stays locked until then. */
    val maturityConfirmed: Boolean = false,
    val recommendations: List<ModelAdvisor.Recommendation> = emptyList(),
    val uncensored: List<ModelAdvisor.Recommendation> = emptyList(),
    val installedNames: Set<String> = emptySet(),
    val computing: Boolean = false,
    val error: String? = null,
)

/**
 * "What should I download for *this* phone?"
 *
 * Nothing is fetched here: discovery stays offline even though the optional
 * Cloud tab can use the network. The bundled catalogue is scored against the
 * probed device with the same arithmetic
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
            val confirmed = settings.maturityConfirmed
            val prefs = ModelAdvisor.Prefs(
                style = settings.style(),
                // A stored refusal-free preference means nothing without the
                // 18+ confirmation (e.g. it was revoked in Settings).
                uncensored = settings.advisorUncensored && confirmed,
                maxDownloadBytes = settings.advisorMaxDownloadGb.toLong() * 1024 * 1024 * 1024,
                preferSpeed = settings.advisorPreferSpeed,
            )
            val catalog = container.catalog
            _state.update {
                it.copy(
                    prefs = prefs,
                    maturityConfirmed = confirmed,
                    catalogCount = catalog.entries.size,
                    catalogUpdated = catalog.updated,
                    catalogNote = catalog.note,
                    profileSummary = DeviceProbe.describe(container.deviceProfile),
                )
            }
            recompute(prefs)
            watchMaturity()

            container.modelRepository.observeAll().collect { rows ->
                _state.update { current ->
                    current.copy(installedNames = rows.map { it.displayName.lowercase() }.toSet())
                }
            }
        }
    }

    /**
     * Keep the gate in sync when it is flipped from Settings while this screen
     * sits in the tab back stack: locking hides the shelf immediately, unlocking
     * (the settings collector fires) rescores with it visible.
     */
    private fun watchMaturity() {
        viewModelScope.launch {
            container.settings.settings.collect { settings ->
                val confirmed = settings.maturityConfirmed
                if (confirmed == _state.value.maturityConfirmed) return@collect
                _state.update { it.copy(maturityConfirmed = confirmed) }
                // Derive the filter from settings (the source of truth) so a
                // confirmMaturity() racing this collector lands on the same prefs.
                recompute(
                    _state.value.prefs.copy(
                        uncensored = confirmed && settings.advisorUncensored,
                    ),
                )
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
        // The adult shelf is scored only for confirmed adults; everyone else
        // gets the locked card, never the list.
        val refusalFree = if (_state.value.maturityConfirmed) {
            ModelAdvisor.recommend(
                profile = profile,
                catalog = container.catalog.uncensored(),
                prefs = prefs.copy(uncensored = true),
                limit = 6,
            )
        } else {
            emptyList()
        }
        _state.update {
            it.copy(
                computing = false,
                recommendations = ranked,
                uncensored = refusalFree,
                starterPick = ModelAdvisor.starterPick(profile, entries),
                headline = ModelAdvisor.headline(profile, entries, prefs),
            )
        }
    }

    /** The 18+ confirmation dialog's affirmative path. */
    fun confirmMaturity(enableUncensored: Boolean) {
        viewModelScope.launch {
            container.settings.setMaturityConfirmed(true)
            _state.update { it.copy(maturityConfirmed = true) }
            if (enableUncensored) {
                container.settings.setAdvisorUncensored(true)
                recompute(_state.value.prefs.copy(uncensored = true))
            } else {
                recompute(_state.value.prefs)
            }
        }
    }

    fun setStyle(style: ModelStyle) {
        // The refusal-free style is adult shelf: never apply it unconfirmed.
        // (The screen intercepts the tap and shows the age gate instead.)
        if (style == ModelStyle.UNCENSORED && !_state.value.maturityConfirmed) return
        viewModelScope.launch {
            container.settings.setAdvisorStyle(style)
            recompute(_state.value.prefs.copy(style = style))
        }
    }

    fun toggleUncensored() {
        val next = !_state.value.prefs.uncensored
        if (next && !_state.value.maturityConfirmed) return
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
