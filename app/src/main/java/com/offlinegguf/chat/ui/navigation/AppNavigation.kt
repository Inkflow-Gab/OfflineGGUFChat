package com.offlinegguf.chat.ui.navigation

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinegguf.chat.App
import com.offlinegguf.chat.data.repository.ChatRepository
import com.offlinegguf.chat.domain.model.GgufModel
import com.offlinegguf.chat.domain.model.ModelInfo
import com.offlinegguf.chat.ui.screens.AboutScreen
import com.offlinegguf.chat.ui.screens.ChatScreen
import com.offlinegguf.chat.ui.screens.HistoryScreen
import com.offlinegguf.chat.ui.screens.ModelBrowserScreen
import com.offlinegguf.chat.ui.screens.ModelDetailsScreen
import com.offlinegguf.chat.ui.screens.ModelPickerScreen
import com.offlinegguf.chat.ui.screens.ModelZooScreen
import com.offlinegguf.chat.ui.screens.SettingsScreen
import com.offlinegguf.chat.ui.screens.SkillsScreen
import com.offlinegguf.chat.ui.theme.OfflineGgufChatTheme
import com.offlinegguf.chat.ui.viewmodel.HistoryViewModel
import com.offlinegguf.chat.ui.viewmodel.ModelBrowserViewModel
import com.offlinegguf.chat.ui.viewmodel.ModelPickerViewModel
import com.offlinegguf.chat.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

sealed class Screen {
    object ModelPicker : Screen()
    object ModelBrowser : Screen()
    object ModelZoo : Screen()
    object Skills : Screen()
    data class Chat(val conversationId: String, val modelPath: String) : Screen()
    object History : Screen()
    object Settings : Screen()
    object ModelDetails : Screen()
    object About : Screen()
}

@Composable
fun AppNavigation(activity: Activity) {
    val app = (activity.application as App)
    val repository = ChatRepository(activity.applicationContext)

    var navScreen by remember { mutableStateOf<Screen>(Screen.ModelPicker) }

    val modelPickerVM: ModelPickerViewModel = viewModel {
        ModelPickerViewModel(activity.application)
    }
    val modelBrowserVM: ModelBrowserViewModel = viewModel {
        ModelBrowserViewModel(activity.application)
    }
    val historyVM: HistoryViewModel = viewModel {
        HistoryViewModel(repository)
    }
    val settingsVM: SettingsViewModel = viewModel {
        SettingsViewModel()
    }

    val pickLauncher = rememberLauncherForActivityResult(OpenDocument) { uri ->
        if (uri != null) {
            modelPickerVM.handleActivityResult(
                ModelPickerViewModel.PICK_GGUF_REQUEST,
                Activity.RESULT_OK,
                Intent().setData(uri)
            )
        }
    }

    LaunchedEffect(modelBrowserVM) {
        modelBrowserVM.onModelSelected = { model: GgufModel ->
            viewModelScope.launch {
                val convId = repository.createConversation(
                    title = model.name,
                    systemPrompt = "You are a helpful offline AI assistant."
                )
                navScreen = Screen.Chat(conversationId = convId, modelPath = model.path)
            }
        }
    }

    LaunchedEffect(modelPickerVM) {
        modelPickerVM.onModelPicked = { model: GgufModel ->
            viewModelScope.launch {
                val convId = repository.createConversation(
                    title = model.name,
                    systemPrompt = "You are a helpful offline AI assistant."
                )
                navScreen = Screen.Chat(conversationId = convId, modelPath = model.path)
            }
        }
    }

    OfflineGgufChatTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(screenTitle(navScreen)) },
                    colors = TopAppBarDefaults.mediumTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (val screen = navScreen) {
                    Screen.ModelPicker -> ModelPickerScreen(
                        onPickFile = { pickLauncher.launch(Intent()) },
                        viewModel = modelPickerVM
                    )
                    Screen.ModelBrowser -> ModelBrowserScreen(
                        activity = activity,
                        viewModel = modelBrowserVM
                    )
                    Screen.ModelZoo -> ModelZooScreen(
                        onModelSelected = { model: ModelInfo ->
                            // info only; user still loads file via picker
                        }
                    )
                    Screen.Skills -> SkillsScreen()
                    is Screen.Chat -> ChatScreen(
                        conversationId = screen.conversationId,
                        modelPath = screen.modelPath,
                        repository = repository,
                        context = activity.applicationContext
                    )
                    Screen.History -> HistoryScreen(repository = repository, viewModel = historyVM)
                    Screen.Settings -> SettingsScreen(viewModel = settingsVM)
                    Screen.ModelDetails -> ModelDetailsScreen()
                    Screen.About -> AboutScreen()
                }
            }
        }
    }
}

private fun screenTitle(screen: Screen): String = when (screen) {
    Screen.ModelPicker -> "Select Model"
    Screen.ModelBrowser -> "Model Folder"
    Screen.ModelZoo -> "Model Zoo"
    Screen.Skills -> "Skills"
    is Screen.Chat -> "Chat"
    Screen.History -> "History"
    Screen.Settings -> "Settings"
    Screen.ModelDetails -> "Model Details"
    Screen.About -> "About"
}
