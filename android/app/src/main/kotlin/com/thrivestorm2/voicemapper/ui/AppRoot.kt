package com.thrivestorm2.voicemapper.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.thrivestorm2.voicemapper.VoiceModel

/** The five main tabs, matching docs/WIREFRAMES.md. */
enum class AppTab(val title: String, val icon: ImageVector) {
    LIVE("Live", Icons.Filled.GraphicEq),
    RANGE("Range", Icons.AutoMirrored.Filled.QueueMusic),
    EXERCISES("Exercises", Icons.Filled.SelfImprovement),
    HISTORY("History", Icons.AutoMirrored.Filled.ShowChart),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/** Onboarding until it's finished, then the tabs; the app lock covers both. */
@Composable
fun AppRoot(model: VoiceModel, requestMic: (() -> Unit) -> Unit, initialTab: String? = null) {
    VoiceMapperTheme {
        CompositionLocalProvider(LocalColourBlind provides model.prefs.colourBlind) {
            Box(Modifier.fillMaxSize()) {
                if (model.prefs.onboardingDone) RootTabs(model, requestMic, initialTab)
                else OnboardingScreen(model, requestMic)
                if (model.isLocked) LockScreen(model)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootTabs(model: VoiceModel, requestMic: (() -> Unit) -> Unit, initialTab: String?) {
    var tab by rememberSaveable { mutableStateOfTab(AppTab.entries.firstOrNull { it.name.equals(initialTab, true) } ?: AppTab.LIVE) }
    // A running exercise covers the tabs, like a pushed screen on iOS.
    model.runningExercise?.let { ex ->
        ExerciseRunScreen(model, ex, requestMic) { model.runningExercise = null }
        return
    }
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(tab.title) }) },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.title) })
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                AppTab.LIVE -> LiveScreen(model, requestMic)
                AppTab.RANGE -> RangeScreen(model, requestMic)
                AppTab.EXERCISES -> ExercisesScreen(model)
                AppTab.HISTORY -> HistoryScreen(model)
                AppTab.SETTINGS -> SettingsScreen(model, requestMic)
            }
        }
    }
    SafetyAlert(model)
    NoisySessionPrompt(model)
}

private fun mutableStateOfTab(t: AppTab) = androidx.compose.runtime.mutableStateOf(t)
