package com.offlinegguf.chat.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.R
import com.offlinegguf.chat.data.catalog.CatalogModel
import com.offlinegguf.chat.data.catalog.ModelAdvisor
import com.offlinegguf.chat.data.catalog.ModelStyle
import com.offlinegguf.chat.domain.device.DeviceProfile
import com.offlinegguf.chat.domain.fit.ModelFit
import com.offlinegguf.chat.ui.components.Hairline
import com.offlinegguf.chat.ui.components.InkChip
import com.offlinegguf.chat.ui.components.InkTopBar
import com.offlinegguf.chat.ui.components.KeyValueRow
import com.offlinegguf.chat.ui.components.SectionTitle
import com.offlinegguf.chat.ui.components.StatRow
import com.offlinegguf.chat.ui.components.VerdictBadge
import com.offlinegguf.chat.ui.theme.CodeText
import com.offlinegguf.chat.ui.theme.MetaKeyText
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme
import com.offlinegguf.chat.util.Formats
import com.offlinegguf.chat.viewmodel.DiscoverViewModel

/**
 * Device-aware model discovery.
 *
 * The scoring runs entirely on the phone: probe the hardware, read the bundled
 * catalogue, apply the same memory arithmetic the loader uses. Results link out to
 * Hugging Face in the browser because this app deliberately ships without the
 * INTERNET permission — an offline chat app that phones home is not offline.
 */
@Composable
fun DiscoverScreen(
    container: AppContainer,
    onOpenModels: () -> Unit,
) {
    val viewModel: DiscoverViewModel = viewModel(factory = DiscoverViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    val context = LocalContext.current

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        InkTopBar(
            title = "Discover",
            subtitle = state.profileSummary.ifBlank { "Reading this phone…" },
            actions = {
                IconButton(onClick = onOpenModels) {
                    Icon(
                        painter = painterResource(R.drawable.ic_cube),
                        contentDescription = "Your models",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item(key = "device") {
                DeviceCard(
                    profileSummary = state.profileSummary,
                    profile = container.deviceProfile,
                    catalogCount = state.catalogCount,
                    catalogUpdated = state.catalogUpdated,
                )
            }

            item(key = "prefs") {
                PreferenceRow(
                    style = state.prefs.style,
                    uncensored = state.prefs.uncensored,
                    preferSpeed = state.prefs.preferSpeed,
                    maxDownloadGb = (state.prefs.maxDownloadBytes / (1024L * 1024 * 1024)).toInt().coerceIn(1, 64),
                    computing = state.computing,
                    onStyle = viewModel::setStyle,
                    onUncensored = { viewModel.toggleUncensored() },
                    onPreferSpeed = { viewModel.togglePreferSpeed() },
                    onMaxGb = viewModel::setMaxDownloadGb,
                )
            }

            item(key = "headline") {
                Text(
                    text = state.headline,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                )
            }

            // ---- refusal-free, before the general list: it is what people come here for
            if (state.uncensored.isNotEmpty()) {
                item(key = "uncensored-header") {
                    Column {
                        SectionTitle(text = "Refusal-free")
                        Text(
                            text = "Fine-tunes with the refusal behaviour removed or reduced. They answer " +
                                "directly instead of declining — including for things a filtered model " +
                                "would push back on. You are responsible for what you ask them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                items(state.uncensored, key = { "u-${it.model.id}" }) { recommendation ->
                    RecommendationCard(
                        recommendation = recommendation,
                        installed = viewModel.isInstalled(recommendation.model),
                        uncensored = true,
                        onOpenFile = { open(recommendation.model.primaryUrl) },
                        onOpenCard = { open(recommendation.model.modelCardUrl) },
                        onSearch = { open(recommendation.model.searchUrl) },
                    )
                }
            }

            item(key = "ranked-header") {
                SectionTitle(
                    text = if (state.prefs.uncensored) "Best refusal-free matches" else "Best matches",
                )
            }

            items(state.recommendations, key = { "r-${it.model.id}" }) { recommendation ->
                RecommendationCard(
                    recommendation = recommendation,
                    installed = viewModel.isInstalled(recommendation.model),
                    uncensored = recommendation.model.uncensored,
                    onOpenFile = { open(recommendation.model.primaryUrl) },
                    onOpenCard = { open(recommendation.model.modelCardUrl) },
                    onSearch = { open(recommendation.model.searchUrl) },
                )
            }

            item(key = "howto") { HowToCard(onOpenModels = onOpenModels) }

            item(key = "catalogue") {
                Column {
                    Hairline()
                    Spacer(Modifier.height(spacing.sm))
                    Text(
                        text = "Catalogue v${state.catalogCount} entries" +
                            if (state.catalogUpdated.isNotBlank()) " · updated ${state.catalogUpdated}" else "",
                        style = MetaKeyText,
                        color = colors.tertiary,
                    )
                    if (state.catalogNote.isNotBlank()) {
                        Text(
                            text = state.catalogNote,
                            style = MetaKeyText,
                            color = colors.tertiary,
                        )
                    }
                    Spacer(Modifier.height(spacing.sm))
                    Text(
                        text = "Scores are estimates from header numbers and this device's RAM, cores and " +
                            "storage. Real speed depends on thermals and what else is running.",
                        style = MetaKeyText,
                        color = colors.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    profileSummary: String,
    profile: DeviceProfile,
    catalogCount: Int,
    catalogUpdated: String,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(spacing.card))
            .padding(spacing.md),
    ) {
        SectionTitle(text = "This phone")
        Spacer(Modifier.height(spacing.sm))
        Text(
            text = profileSummary.ifBlank { "Probing hardware…" },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(spacing.sm))
        StatRow(
            items = listOf(
                "ram" to Formats.bytes(profile.ramBytes),
                "cores" to profile.cores.toString(),
                "tier" to profile.tier.label,
                "free" to Formats.bytes(profile.freeStorageBytes),
            ),
            emphasizedIndex = 2,
        )
        Spacer(Modifier.height(spacing.sm))
        KeyValueRow("model memory budget", Formats.bytes(profile.modelRamBudgetBytes))
        KeyValueRow("suggested context", Formats.contextTokens(profile.suggestedContext.toLong()))
        KeyValueRow("suggested threads", profile.suggestedThreads.toString())
        KeyValueRow("abi", profile.primaryAbi)
        Spacer(Modifier.height(spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_offline),
                contentDescription = null,
                tint = colors.tertiary,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "$catalogCount catalogue entries scored locally. No INTERNET permission: " +
                    "links open in your browser.",
                style = MetaKeyText,
                color = colors.tertiary,
                modifier = Modifier.weight(1f),
            )
        }
        if (catalogUpdated.isNotBlank()) {
            Text(
                text = "catalogue updated $catalogUpdated",
                style = MetaKeyText,
                color = colors.tertiary,
            )
        }
    }
}

@Composable
private fun PreferenceRow(
    style: ModelStyle,
    uncensored: Boolean,
    preferSpeed: Boolean,
    maxDownloadGb: Int,
    computing: Boolean,
    onStyle: (ModelStyle) -> Unit,
    onUncensored: () -> Unit,
    onPreferSpeed: () -> Unit,
    onMaxGb: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(spacing.card))
            .padding(spacing.md),
    ) {
        SectionTitle(text = "What do you want it for?")
        Spacer(Modifier.height(spacing.sm))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            items(ModelStyle.values().size) { index ->
                val option = ModelStyle.values()[index]
                InkChip(
                    text = option.label,
                    selected = style == option,
                    onClick = { onStyle(option) },
                )
            }
        }
        Spacer(Modifier.height(spacing.sm))
        Text(
            text = style.blurb,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )

        Spacer(Modifier.height(spacing.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Refusal-free only",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (uncensored) FontWeight.SemiBold else FontWeight.Normal,
                    color = colors.onSurface,
                )
                Text(
                    text = "Uncensored fine-tunes; answers without the filter.",
                    style = MetaKeyText,
                    color = colors.tertiary,
                )
            }
            InkChip(
                text = if (uncensored) "on" else "off",
                selected = uncensored,
                onClick = onUncensored,
            )
        }
        Spacer(Modifier.height(spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Prefer speed",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (preferSpeed) FontWeight.SemiBold else FontWeight.Normal,
                    color = colors.onSurface,
                )
                Text(
                    text = "Weight tokens/second above capability.",
                    style = MetaKeyText,
                    color = colors.tertiary,
                )
            }
            InkChip(
                text = if (preferSpeed) "on" else "off",
                selected = preferSpeed,
                onClick = onPreferSpeed,
            )
        }

        Spacer(Modifier.height(spacing.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Max download",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            listOf(2, 4, 8, 16).forEach { gb ->
                InkChip(
                    text = "$gb GB",
                    selected = maxDownloadGb == gb,
                    onClick = { onMaxGb(gb) },
                )
                Spacer(Modifier.width(4.dp))
            }
        }
        if (computing) {
            Spacer(Modifier.height(spacing.sm))
            Text(text = "Scoring…", style = MetaKeyText, color = colors.tertiary)
        }
    }
}

@Composable
private fun RecommendationCard(
    recommendation: ModelAdvisor.Recommendation,
    installed: Boolean,
    uncensored: Boolean,
    onOpenFile: () -> Unit,
    onOpenCard: () -> Unit,
    onSearch: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing
    val model: CatalogModel = recommendation.model
    val assessment: ModelFit.Assessment = recommendation.assessment
    var expanded by remember(model.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(spacing.card))
            .padding(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = recommendation.rank.toString(),
                style = CodeText,
                color = colors.tertiary,
                modifier = Modifier.width(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.displayName.ifBlank { model.repo },
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = model.repo,
                    style = MetaKeyText,
                    color = colors.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${recommendation.score.roundToInt()}",
                style = CodeText,
                color = colors.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(spacing.sm))
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VerdictBadge(verdict = assessment.verdict)
            if (model.quant.isNotBlank()) InkChip(text = model.quant, selected = true)
            if (model.paramsBillions > 0) InkChip(text = "${Formats.fixed(model.paramsBillions, 1)}B")
            if (uncensored) InkChip(text = "refusal-free", icon = R.drawable.ic_shield)
            if (installed) InkChip(text = "installed", icon = R.drawable.ic_check)
        }

        Spacer(Modifier.height(spacing.sm))
        StatRow(
            items = listOf(
                "download" to Formats.bytes(model.approxSizeBytes),
                "context" to Formats.contextTokens(model.contextLength.toLong()),
                "ram" to Formats.bytes(assessment.peakRamBytes),
                "speed" to (if (assessment.tokensPerSecond > 0) "~${Formats.fixed(assessment.tokensPerSecond, 1)}/s" else "—"),
            ),
            emphasizedIndex = 3,
        )

        if (recommendation.reasons.isNotEmpty()) {
            Spacer(Modifier.height(spacing.sm))
            recommendation.reasons.forEach { reason ->
                Row(modifier = Modifier.padding(bottom = 2.dp)) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(3.dp)
                            .background(colors.tertiary, RoundedCornerShape(1.dp)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (recommendation.badges.isNotEmpty()) {
            Spacer(Modifier.height(spacing.xs))
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                modifier = Modifier.fillMaxWidth(),
            ) {
                recommendation.badges.forEach { badge -> InkChip(text = badge) }
            }
        }

        if (model.notes.isNotBlank()) {
            Spacer(Modifier.height(spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_info),
                    contentDescription = null,
                    tint = colors.tertiary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (expanded) model.notes else model.notes.lineSequence().first(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.tertiary,
                    maxLines = if (expanded) 12 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            InkChip(text = "Download file", icon = R.drawable.ic_download, selected = true, onClick = onOpenFile)
            InkChip(text = "Model card", icon = R.drawable.ic_external, onClick = onOpenCard)
            InkChip(text = "Search", icon = R.drawable.ic_search, onClick = onSearch)
        }
    }
}

@Composable
private fun HowToCard(onOpenModels: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = OfflineGgufTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(spacing.card))
            .padding(spacing.md),
    ) {
        SectionTitle(text = "Getting the file onto the phone")
        Spacer(Modifier.height(spacing.sm))
        listOf(
            "1. Tap Download file — it opens the Hugging Face page in your browser.",
            "2. Pick the quant that fits the RAM budget above (smaller is faster).",
            "3. Open the downloaded .gguf with this app, or move it into the models folder " +
                "and tap Scan. The header is verified before it is listed.",
        ).forEach { step ->
            Text(
                text = step,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = spacing.xs),
            )
        }
        Spacer(Modifier.height(spacing.sm))
        Spacer(Modifier.height(spacing.sm))
        InkChip(text = "Go to your models", icon = R.drawable.ic_cube, onClick = onOpenModels)
    }
}
