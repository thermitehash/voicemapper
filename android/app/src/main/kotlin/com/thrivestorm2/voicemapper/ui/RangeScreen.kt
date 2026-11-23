package com.thrivestorm2.voicemapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thrivestorm2.voicemapper.VoiceModel
import com.thrivestorm2.voicemapper.core.analysis.PerceivedGender
import com.thrivestorm2.voicemapper.core.analysis.RangeRow
import com.thrivestorm2.voicemapper.core.analysis.RangeTable
import com.thrivestorm2.voicemapper.core.analysis.VoicePerception
import com.thrivestorm2.voicemapper.core.analysis.swiftRound

/** Port of iOS `RangeTableView`: rows share the height, so the table never scrolls. */
@Composable
fun RangeScreen(model: VoiceModel, requestMic: (() -> Unit) -> Unit) {
    val pitch by model.audio.pitch.collectAsState()
    val listening by model.audio.isListening.collectAsState()
    val prefs = model.prefs
    val goal = prefs.goal
    val currentId = pitch?.let { RangeTable.rowId(it.note) }
    var info by remember { mutableStateOf(false) }

    // First tap sets a one-note goal, a second on another row stretches it to a
    // range, tapping the lone note again clears it, any tap on a range starts over.
    fun tap(midi: Int) {
        if (goal != null && goal.low == goal.high) {
            if (midi == goal.low) { prefs.goalLow = 0; prefs.goalHigh = 0 }
            else { prefs.goalLow = minOf(midi, goal.low); prefs.goalHigh = maxOf(midi, goal.low) }
        } else { prefs.goalLow = midi; prefs.goalHigh = midi }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(pitch?.let { "${it.note.name}   ${swiftRound(it.frequency)} Hz" } ?: if (listening) "Say or hum a note" else "Tap Speak to start",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (pitch == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val st = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
            val c = MaterialTheme.colorScheme.onSurfaceVariant
            Text("Note", Modifier.width(56.dp), style = st, color = c)
            Text("Hz", Modifier.width(48.dp), style = st, color = c, textAlign = TextAlign.End)
            Text("Voice", Modifier.width(76.dp).padding(start = 12.dp), style = st, color = c)
            Text("Read", Modifier.weight(1f), style = st, color = c)
            IconButton(onClick = { info = true }) { Icon(Icons.Outlined.Info, "About this table") }
        }
        Column(Modifier.weight(1f)) {
            for (row in RangeTable.rows) {
                val inGoal = goal?.contains(row.id) == true
                val current = row.id == currentId
                Row(
                    Modifier.weight(1f).fillMaxWidth()
                        .background(when { current -> Accent.copy(alpha = 0.2f); inGoal -> Teal.copy(alpha = 0.18f); else -> Color.Transparent })
                        .clickable(role = Role.Button, onClickLabel = "Set your goal range") { tap(row.id) }
                        .padding(horizontal = 16.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${row.note.name}, ${swiftRound(row.frequency)} hertz, ${row.voiceType.label}, ${row.perceived.meaning}"
                            selected = current
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.note.name, Modifier.width(56.dp), fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                    Text("${swiftRound(row.frequency)}", Modifier.width(48.dp), textAlign = TextAlign.End)
                    Text(row.voiceType.label, Modifier.width(76.dp).padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ZoneSwatch(row.perceived, row.commonness, row)
                        Text(row.perceived.letter, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (z in listOf(PerceivedGender.FEMALE, PerceivedGender.AMBIGUOUS, PerceivedGender.MALE)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ZoneSwatch(z, 1.0, null)
                        Text("${z.letter} – ${z.meaning}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            SpeakButton(listening) { if (listening) model.setListening(false) else requestMic { model.setListening(true) } }
        }
    }
    if (info) AlertDialog(
        onDismissRequest = { info = false },
        confirmButton = { TextButton(onClick = { info = false }) { Text("OK") } },
        text = { Text("Tap a note to set your goal, then another to stretch it into a range. Tap the single note again to clear.\n\nResonance matters too. These are typical speaking ranges, not rules.") },
    )
}

/** Bar tinted by zone; fill strongest at the zone's most common pitch. */
@Composable
private fun ZoneSwatch(zone: PerceivedGender, strength: Double, row: RangeRow?) {
    val colourBlind = LocalColourBlind.current
    val color = row?.let { VoicePerception.color(VoicePerception.blend(it.frequency, 50.0), colourBlind).toColor() } ?: zone.color()
    Box(Modifier.size(40.dp, 14.dp).background(color.copy(alpha = strength.toFloat()), RoundedCornerShape(3.dp))
        .border(1.dp, color, RoundedCornerShape(3.dp)))
}
