package com.offlinegguf.chat.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.R
import com.offlinegguf.chat.data.settings.AppSettings
import com.offlinegguf.chat.ui.screens.ChatScreen
import com.offlinegguf.chat.ui.screens.CloudScreen
import com.offlinegguf.chat.ui.screens.DiscoverScreen
import com.offlinegguf.chat.ui.screens.HistoryScreen
import com.offlinegguf.chat.ui.screens.ModelDetailScreen
import com.offlinegguf.chat.ui.screens.ModelsScreen
import com.offlinegguf.chat.ui.screens.SettingsScreen
import com.offlinegguf.chat.ui.screens.SkillsScreen
import com.offlinegguf.chat.ui.theme.OfflineGgufTheme

/** Bottom-bar destinations. Everything else is pushed on top of these. */
enum class Destination(val route: String, val label: String, val icon: Int) {
    CHAT("chat", "Chat", R.drawable.ic_chat),
    MODELS("models", "Models", R.drawable.ic_cube),
    DISCOVER("discover", "Discover", R.drawable.ic_target),
    CLOUD("cloud", "Cloud", R.drawable.ic_cloud),
}

object Routes {
    const val CHAT = "chat"
    const val CHAT_WITH_ID = "chat/{conversationId}"
    const val MODELS = "models"
    const val MODEL_DETAIL = "model/{path}"
    const val DISCOVER = "discover"
    const val CLOUD = "cloud"
    const val SKILLS = "skills"
    const val SETTINGS = "settings"
    const val HISTORY = "history"

    fun chat(conversationId: Long) = "chat/$conversationId"

    /** File paths contain '/' and sometimes '#', both of which break a route. */
    fun modelDetail(path: String) = "model/${Uri.encode(path)}"

    fun decodeModelPath(encoded: String?) = encoded?.let { Uri.decode(it) }.orEmpty()
}

/** Routes that own a bottom-bar tab; moving between them cross-fades. */
private val TabRoutes = setOf(
    Routes.CHAT,
    Routes.CHAT_WITH_ID,
    Routes.MODELS,
    Routes.DISCOVER,
    Routes.CLOUD,
)

/**
 * Navigation host.
 *
 * Four tabs — Chat, Models, Discover, Cloud — because those are the four
 * questions the app answers ("talk", "what's on my phone", "what should I
 * get", "what can I use online"). Everything else (history, skills, settings,
 * model detail) is one tap deeper.
 *
 * Motion is directional and honest about hierarchy: switching tabs is a
 * cross-fade with a whisper of scale (siblings, no travel), pushing a detail
 * screen slides in from the right (deeper), going back slides out to the
 * right. Tab icons spring to a slightly larger size when selected — the one
 * playful animation in the chrome, and it settles behind the action rather
 * than in front of it. All of it collapses to clean cuts under Reduce motion.
 */
@Composable
fun AppNavigation(
    container: AppContainer,
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // Under "Reduce motion" the installed MotionSpec already has zero durations,
    // so these two lines need no special case of their own.
    val motion = OfflineGgufTheme.motion
    val barDuration = motion.standard
    val pushDuration = motion.slow

    val showBottomBar = currentRoute in TabRoutes

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(tween(barDuration, easing = motion.easeEmphasized)) { it } +
                    fadeIn(tween(barDuration)),
                exit = slideOutVertically(tween(motion.fast, easing = motion.easeAccelerate)) { it } +
                    fadeOut(tween(motion.fast)),
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    Destination.values().forEach { destination ->
                        val selected = currentRoute == destination.route ||
                            (destination == Destination.CHAT && currentRoute == Routes.CHAT_WITH_ID)
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(destination.route) },
                            icon = {
                                val iconScale by animateFloatAsState(
                                    targetValue = if (selected) 1.18f else 1f,
                                    animationSpec = motion.springBouncy,
                                    label = "tab-${destination.route}",
                                )
                                Icon(
                                    painter = painterResource(destination.icon),
                                    contentDescription = destination.label,
                                    tint = if (selected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    },
                                )
                            },
                            label = {
                                Text(
                                    destination.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.tertiary,
                                )
                            },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CHAT,
            modifier = Modifier.padding(innerPadding),
            enterTransition = {
                if (initialState.destination.route in TabRoutes && targetState.destination.route in TabRoutes) {
                    fadeIn(tween(barDuration)) + scaleIn(tween(barDuration), initialScale = 0.985f)
                } else {
                    fadeIn(tween(barDuration)) +
                        slideInHorizontally(tween(pushDuration, easing = motion.easeEmphasized)) {
                            width -> width / 24
                        }
                }
            },
            exitTransition = {
                if (initialState.destination.route in TabRoutes && targetState.destination.route in TabRoutes) {
                    fadeOut(tween(motion.fast)) + scaleOut(tween(motion.fast), targetScale = 0.985f)
                } else {
                    fadeOut(tween(barDuration)) +
                        slideOutHorizontally(tween(pushDuration, easing = motion.easeAccelerate)) {
                            width -> -width / 24
                        }
                }
            },
            popEnterTransition = {
                fadeIn(tween(barDuration)) +
                    slideInHorizontally(tween(pushDuration, easing = motion.easeEmphasized)) {
                        width -> -width / 24
                    }
            },
            popExitTransition = {
                fadeOut(tween(barDuration)) +
                    slideOutHorizontally(tween(pushDuration, easing = motion.easeAccelerate)) {
                        width -> width / 24
                    }
            },
        ) {
            composable(Routes.CHAT) {
                ChatScreen(
                    container = container,
                    settings = settings,
                    conversationId = null,
                    onOpenModels = { navController.navigateToTab(Routes.MODELS) },
                    onOpenHistory = { navController.navigate(Routes.HISTORY) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenSkills = { navController.navigate(Routes.SKILLS) },
                    onOpenCloud = { navController.navigateToTab(Routes.CLOUD) },
                )
            }

            composable(Routes.CHAT_WITH_ID) { entry ->
                val id = entry.arguments?.getString("conversationId")?.toLongOrNull()
                ChatScreen(
                    container = container,
                    settings = settings,
                    conversationId = id,
                    onOpenModels = { navController.navigateToTab(Routes.MODELS) },
                    onOpenHistory = { navController.navigate(Routes.HISTORY) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenSkills = { navController.navigate(Routes.SKILLS) },
                    onOpenCloud = { navController.navigateToTab(Routes.CLOUD) },
                )
            }

            composable(Routes.MODELS) {
                ModelsScreen(
                    container = container,
                    settings = settings,
                    onOpenModel = { path -> navController.navigate(Routes.modelDetail(path)) },
                    onOpenDiscover = { navController.navigateToTab(Routes.DISCOVER) },
                )
            }

            composable(Routes.MODEL_DETAIL) { entry ->
                val path = Routes.decodeModelPath(entry.arguments?.getString("path"))
                ModelDetailScreen(
                    container = container,
                    settings = settings,
                    path = path,
                    onBack = { navController.popBackStack() },
                    onUse = { navController.navigateToTab(Routes.CHAT) },
                )
            }

            composable(Routes.DISCOVER) {
                DiscoverScreen(
                    container = container,
                    onOpenModels = { navController.navigateToTab(Routes.MODELS) },
                )
            }

            composable(Routes.CLOUD) {
                CloudScreen(
                    container = container,
                    onOpenChat = { navController.navigateToTab(Routes.CHAT) },
                )
            }

            composable(Routes.SKILLS) {
                SkillsScreen(container = container, onBack = { navController.popBackStack() })
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    settings = settings,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.HISTORY) {
                HistoryScreen(
                    container = container,
                    onOpenConversation = { id ->
                        navController.navigate(Routes.chat(id)) {
                            popUpTo(Routes.CHAT) { inclusive = false }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/**
 * Tab switching that never stacks duplicates: pop back to the tab's own node and
 * keep the saved state of the other tabs.
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
