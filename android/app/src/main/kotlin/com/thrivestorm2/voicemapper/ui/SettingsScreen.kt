package com.thrivestorm2.voicemapper.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.thrivestorm2.voicemapper.VoiceModel
import com.thrivestorm2.voicemapper.core.analysis.GoalPreset
import com.thrivestorm2.voicemapper.core.analysis.Note
import com.thrivestorm2.voicemapper.core.analysis.Stage
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private enum class SettingsPage { MAIN, GOAL, STAGE, SOURCES }

/** Port of iOS `SettingsView` (WIREFRAMES §8). */
@Composable
fun SettingsScreen(model: VoiceModel, requestMic: (() -> Unit) -> Unit) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
    var showingBaseline by remember { mutableStateOf(false) }
    if (showingBaseline) { BaselineScreen(model, requestMic) { showingBaseline = false }; return }
    if (page != SettingsPage.MAIN) BackHandler { page = if (page == SettingsPage.STAGE) SettingsPage.GOAL else SettingsPage.MAIN }
    when (page) {
        SettingsPage.MAIN -> MainSettings(model, onGoal = { page = SettingsPage.GOAL }, onSources = { page = SettingsPage.SOURCES },
            onBaseline = { showingBaseline = true })
        SettingsPage.GOAL -> SubPage("Goal", { page = SettingsPage.MAIN }) {
            GoalPicker(model)
            SectionHeader("Progression")
            Card { Column {
                ValueRow("Current stage", "${model.stage.number}. ${model.stage.title}")
                HorizontalDivider()
                LinkRow("Choose stage") { page = SettingsPage.STAGE }
            } }
        }
        SettingsPage.STAGE -> SubPage("Choose stage", { page = SettingsPage.GOAL }) { StagePicker(model) }
        SettingsPage.SOURCES -> SubPage("Research sources", { page = SettingsPage.MAIN }) { ResearchSources() }
    }
}

@Composable
private fun MainSettings(model: VoiceModel, onGoal: () -> Unit, onSources: () -> Unit, onBaseline: () -> Unit) {
    val prefs = model.prefs
    val context = LocalContext.current
    val baseline = model.store.data.baseline
    var confirmingDelete by remember { mutableStateOf(false) }
    val micSource by model.audio.sourceDescription.collectAsState()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openOutputStream(uri)?.use { it.write(model.store.exportJson().toByteArray()) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Voice")
        Card { Column {
            LinkRow("Goal", GoalPreset.label(prefs.goalLow, prefs.goalHigh, prefs.resonanceLow, prefs.resonanceHigh), onGoal)
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().clickable(onClick = onBaseline).padding(16.dp)) {
                Text(if (baseline == null) "Record baseline" else "Re-record baseline", color = Accent)
                baseline?.let {
                    Text("${Note.fromFrequency(it.medianHz).name}, ${DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(it.date.atZone(ZoneId.systemDefault()))}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } }

        SectionHeader("Safety")
        Card { Column {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Daily practice cap: ${prefs.capMinutes} min", Modifier.weight(1f))
                OutlinedButton(onClick = { prefs.capMinutes = maxOf(10, prefs.capMinutes - 5) }) { Text("−") }
                OutlinedButton(onClick = { prefs.capMinutes = minOf(120, prefs.capMinutes + 5) }, Modifier.padding(start = 8.dp)) { Text("+") }
            }
            HorizontalDivider()
            ToggleRow("Strain alerts", prefs.strainAlerts) { prefs.strainAlerts = it }
        } }
        Footer("Strain alerts compare clarity, steadiness, pitch and loudness against your baseline (or the first minute of a session) and suggest a rest. They're an estimate, not a diagnosis: if you have pain or lasting hoarseness, see a speech-language pathologist.")

        SectionHeader("Display")
        Card { ToggleRow("Colour-blind palette", prefs.colourBlind) { prefs.colourBlind = it } }
        Footer("Uses blue, grey and orange for the usually-read-male, ambiguous and usually-read-female zones. Zones always have M / A / F labels too.")

        SectionHeader("Privacy")
        Card { Column {
            ToggleRow("App lock (fingerprint, face or screen lock)", prefs.appLock, enabled = model.lockAvailable || prefs.appLock) { on ->
                if (on) model.confirmEnableLock { ok -> if (ok) prefs.appLock = true } else prefs.appLock = false
            }
            HorizontalDivider()
            LinkRow("Export data (JSON)") { exporter.launch("voicemapper-export.json") }
            HorizontalDivider()
            Text("Delete all data", color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().clickable { confirmingDelete = true }.padding(16.dp))
        } }
        Footer("VoiceMapper never saves audio. It keeps only summaries of your sessions (pitch, resonance and voice-quality medians), all on this phone. Nothing is uploaded, and the app has no internet permission. Export saves a copy wherever you choose.")

        SectionHeader("About")
        Card { Column {
            LinkRow("Research sources", onClick = onSources)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, "https://find.asha.org/pro/".toUri()))
            }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Find a voice therapist", Modifier.weight(1f), color = Accent)
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Accent)
            }
            if (micSource.isNotEmpty()) {
                HorizontalDivider()
                ValueRow("Microphone", micSource)
            }
        } }
        Footer("Android preview build. The iPhone app is the reference version; this one is kept in step with it.")
    }

    if (confirmingDelete) AlertDialog(
        onDismissRequest = { confirmingDelete = false },
        title = { Text("Delete all data?") },
        text = { Text("This removes every session, your baseline, your goal and all settings, then starts setup again. It can't be undone.") },
        confirmButton = { TextButton(onClick = { confirmingDelete = false; model.deleteAll() }) { Text("Delete everything", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
    )
}

/** Feminine / masculine / androgynous / custom (PLAN §6). Used in onboarding and Settings ▸ Goal. */
@Composable
fun GoalPicker(model: VoiceModel) {
    val prefs = model.prefs
    var customChosen by remember { mutableStateOf(false) }
    val current = GoalPreset.matching(prefs.goalLow, prefs.goalHigh, prefs.resonanceLow, prefs.resonanceHigh)
    val isCustom = current == null && (customChosen || prefs.goal != null || prefs.resonanceGoal != null)
    Card { Column {
        GoalPreset.entries.forEachIndexed { i, p ->
            if (i > 0) HorizontalDivider()
            GoalRow(p.title, p.summary, p.zone.color(), current == p) {
                prefs.goalLow = p.range.low; prefs.goalHigh = p.range.high
                prefs.resonanceLow = p.resonance.low; prefs.resonanceHigh = p.resonance.high
                customChosen = false
            }
        }
        HorizontalDivider()
        GoalRow("Custom", "Set your own range and resonance target", Color.Gray, isCustom) { customChosen = true }
    } }
    if (isCustom) Footer("Tap a note and then another on the Range tab to set your pitch range. Drag on the Live grid to set your resonance target. Your current settings are kept.")
    Footer("Listed alphabetically. These are typical ranges from voice research, not rules. Any voice can be your goal, and you can change it at any time. Pitch is only part of it: resonance and intonation matter too.")
}

@Composable
private fun GoalRow(title: String, detail: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).semantics { this.selected = selected }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = Accent)
    }
}

@Composable
private fun StagePicker(model: VoiceModel) {
    Card { Column {
        Stage.entries.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider()
            Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { model.chooseStage(s) }
                .semantics { selected = s == model.stage }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${s.number}. ${s.title}")
                    Text(s.focus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (s == model.stage) Icon(Icons.Filled.Check, null, tint = Accent)
            }
        }
    } }
    Footer("Stages are suggestions, not locks. Choosing a stage restarts its ready-to-move-on checks from today.")
}

private val researchSources = listOf(
    "Gelfer, M. P., & Mikos, V. A. (2005). The relative contributions of speaking fundamental frequency and formant frequencies to gender identification based on isolated vowels. Journal of Voice, 19(4), 544–554." to "Speaking-pitch zones in the range table and grid.",
    "Hillenbrand, J. M., & Clark, M. J. (2009). The role of f0 and formant frequencies in distinguishing the voices of men and women. Attention, Perception, & Psychophysics, 71(5), 1150–1166." to "How pitch and resonance together shape how a voice is read.",
    "Hillenbrand, J., Getty, L. A., Clark, M. J., & Wheeler, K. (1995). Acoustic characteristics of American English vowels. Journal of the Acoustical Society of America, 97(5), 3099–3111." to "Formant values behind the resonance score.",
    "Leung, Y., Oates, J., & Chan, S. P. (2018). Voice, articulation, and prosody contribute to listener perceptions of speaker gender: A systematic review and meta-analysis. Journal of Speech, Language, and Hearing Research, 61(2), 266–297." to "Why pitch is only part of it: resonance and intonation matter too.",
    "de Cheveigné, A., & Kawahara, H. (2002). YIN, a fundamental frequency estimator for speech and music. Journal of the Acoustical Society of America, 111(4), 1917–1930." to "Pitch detection.",
    "Fairbanks, G. (1960). Voice and Articulation Drillbook (2nd ed.). Harper & Row." to "The Rainbow Passage used for the baseline and reading exercise.",
)

@Composable
private fun ResearchSources() {
    Card { Column {
        researchSources.forEachIndexed { i, (citation, use) ->
            if (i > 0) HorizontalDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(citation, style = MaterialTheme.typography.bodyMedium)
                Text(use, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } }
    Footer("Norms vary by person, language and culture. The app shows typical ranges, not rules. Voice-health measures (HNR, jitter, shimmer, CPP) are compared only against your own baseline.")
}

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        content()
    }
}

@Composable
private fun LinkRow(title: String, value: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        value?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ValueRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title)
        Text(value, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled)
    }
}
