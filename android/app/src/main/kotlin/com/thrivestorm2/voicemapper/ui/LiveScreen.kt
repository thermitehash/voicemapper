package com.thrivestorm2.voicemapper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrivestorm2.voicemapper.VoiceModel
import com.thrivestorm2.voicemapper.core.analysis.GoalRange
import com.thrivestorm2.voicemapper.core.analysis.HeatmapBinner
import com.thrivestorm2.voicemapper.core.analysis.HistoryPeriod
import com.thrivestorm2.voicemapper.core.analysis.swiftRound
import com.thrivestorm2.voicemapper.core.dsp.PitchReading

/** Port of iOS `LiveView`. */
@Composable
fun LiveScreen(model: VoiceModel, requestMic: (() -> Unit) -> Unit) {
    val pitch by model.audio.pitch.collectAsState()
    val resonance by model.audio.resonance.collectAsState()
    val quality by model.audio.quality.collectAsState()
    val listening by model.audio.isListening.collectAsState()
    val noisy by model.audio.isNoisy.collectAsState()
    val error by model.audio.lastError.collectAsState()
    val hearing by model.audio.isHearingYourself.collectAsState()
    val bluetooth by model.audio.outputIsBluetooth.collectAsState()
    val bannerDismissed by model.audio.bluetoothBannerDismissed.collectAsState()
    var showingHearYourself by remember { mutableStateOf(false) }
    val prefs = model.prefs
    val period = HistoryPeriod.entries.firstOrNull { it.name == prefs.historyPeriod } ?: HistoryPeriod.WEEK

    Column(Modifier.fillMaxSize()) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        model.restUntil?.let { RestBanner(it) { model.endRest() } }
        if (hearing && bluetooth && !bannerDismissed) BluetoothBanner { model.audio.dismissBluetoothBanner() }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        PitchLabel(pitch, listening, prefs.goal)
        val sessions = if (prefs.showOverlay || prefs.showHeat) HeatmapBinner.sessions(model.store.data.sessions, period) else emptyList()
        PitchResonanceGrid(
            pitch = pitch, resonance = resonance, goal = prefs.goal, resonanceGoal = prefs.resonanceGoal,
            onResonanceGoal = { lo, hi -> prefs.resonanceLow = lo; prefs.resonanceHigh = hi },
            heat = if (prefs.showHeat) HeatmapBinner.cells(sessions) else emptyList(),
            trace = if (prefs.showOverlay) HeatmapBinner.points(sessions) else emptyList(),
        )
        HistoryControls(prefs.showOverlay, { prefs.showOverlay = it }, prefs.showHeat, { prefs.showHeat = it }, period) {
            prefs.historyPeriod = it.name
        }
        Box {
            VoiceQualityPanel(if (listening) quality else null, model.health, (model.todaySeconds / 60).toInt(), prefs.capMinutes,
                Modifier.alpha(if (noisy) 0f else 1f))
            if (noisy) NoiseBanner(compact = true)
        }
    }
    // Pinned below the scrolling content, like the iOS bottom overlay.
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
        SpeakButton(listening) { if (listening) model.setListening(false) else requestMic { model.setListening(true) } }
        OutlinedButton(onClick = { showingHearYourself = true },
            modifier = Modifier.semantics { contentDescription = if (hearing) "Listen, on" else "Listen, off" }) {
            val tint = if (hearing) Accent else MaterialTheme.colorScheme.onSurfaceVariant
            Icon(if (hearing) Icons.Filled.Headphones else Icons.Outlined.Headphones, null, tint = tint)
            Text("  Listen", color = tint)
        }
    }
    }
    if (showingHearYourself) HearYourselfSheet(model.audio, requestMic) { showingHearYourself = false }
}

@Composable
fun SpeakButton(listening: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = if (listening) "Stop, on" else "Speak, off" }) {
        Icon(if (listening) Icons.Filled.Mic else Icons.Outlined.Mic, null, tint = if (listening) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (listening) "  Stop" else "  Speak", color = if (listening) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HistoryControls(overlay: Boolean, setOverlay: (Boolean) -> Unit, heat: Boolean, setHeat: (Boolean) -> Unit,
                            period: HistoryPeriod, setPeriod: (HistoryPeriod) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(overlay, { setOverlay(!overlay) }, label = { Text("History") })
        FilterChip(heat, { setHeat(!heat) }, label = { Text("Heat map") })
        Box {
            FilterChip(false, { menu = true }, label = { Text("${period.label} ▾") })
            DropdownMenu(menu, { menu = false }) {
                HistoryPeriod.entries.forEach { p -> DropdownMenuItem({ Text(p.label) }, { setPeriod(p); menu = false }) }
            }
        }
    }
}

/** Note name and frequency, e.g. "G4  196 Hz". No needle or cents, by design. Always three lines. */
@Composable
private fun PitchLabel(reading: PitchReading?, listening: Boolean, goal: GoalRange?) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        if (reading != null) {
            val color = goal?.let { Color.hsv((it.closeness(reading.frequency) * 120).toFloat(), 0.85f, 0.85f) } ?: MaterialTheme.colorScheme.onSurface
            Text(reading.note.name, fontSize = 64.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
            Text("${swiftRound(reading.frequency)} Hz", style = MaterialTheme.typography.titleLarge, color = secondary)
        } else {
            Text("–", fontSize = 64.sp, fontWeight = FontWeight.SemiBold, color = secondary.copy(alpha = 0.5f))
            Text(if (listening) "Say or hum a note" else "Tap Speak to start", style = MaterialTheme.typography.titleLarge, color = secondary)
        }
        val caption = if (reading != null && goal != null) when (goal.direction(reading.frequency)) {
            null -> "In your goal range"
            GoalRange.Direction.HIGHER -> "Goal is higher"
            GoalRange.Direction.LOWER -> "Goal is lower"
        } else " "
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = secondary)
    }
}
