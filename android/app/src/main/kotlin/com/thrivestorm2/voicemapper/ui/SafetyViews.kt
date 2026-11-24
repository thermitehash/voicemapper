package com.thrivestorm2.voicemapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thrivestorm2.voicemapper.VoiceModel
import com.thrivestorm2.voicemapper.core.analysis.StrainGuard
import com.thrivestorm2.voicemapper.core.dsp.VoiceQuality
import kotlinx.coroutines.delay
import java.time.Instant

// Port of iOS UI/SafetyViews.swift.

/** Strain and daily-cap alerts, shown over every tab (WIREFRAMES §6). */
@Composable
fun SafetyAlert(model: VoiceModel) {
    val alert = model.alert ?: return
    val (title, message, primary, secondary) = when (alert) {
        is StrainGuard.Alert.Strain -> Quad(
            "Take a break",
            "Signs of strain: ${joinList(alert.reasons)}. Rest for 5 minutes and sip some water.",
            "Rest" to { model.rest() },
            "Dismiss" to { model.dismissAlert() })
        is StrainGuard.Alert.DailyCap -> Quad(
            "Daily practice cap reached",
            rs("You've practised ${alert.minutes} minutes today. Short, regular sessions are kinder to your voice than long ones."),
            "Stop for today" to { model.stopForToday() },
            "5 more minutes" to { model.dismissAlert() })
    }
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(if (alert is StrainGuard.Alert.Strain) Icons.Filled.WaterDrop else Icons.Filled.Bedtime, null, tint = Accent) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                Text(rs("This is an estimate, not a diagnosis. If you have pain, lasting hoarseness or lose your voice, see a speech-language pathologist."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = primary.second) { Text(primary.first) } },
        dismissButton = { OutlinedButton(onClick = secondary.second) { Text(secondary.first) } },
    )
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/** "a, b and c", like ListFormatter. */
fun joinList(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
}

@Composable
fun NoisySessionPrompt(model: VoiceModel) {
    val session = model.noisySession ?: return
    if (model.alert != null) return
    AlertDialog(
        onDismissRequest = { model.noisySession = null },
        title = { Text("Keep this session?") },
        text = { Text("About ${Math.round((session.noisyShare ?: 0.0) * 100)}% of it had loud background noise, so its readings may be off.") },
        confirmButton = { TextButton(onClick = { model.noisySession = null }) { Text("Keep") } },
        dismissButton = { TextButton(onClick = { model.deleteSession(session) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
    )
}

/** Shown while background noise is loud enough to throw off the readings. */
@Composable
fun NoiseBanner(compact: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(Color(0xFFFF9500).copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(if (compact) 8.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color(0xFFFF9500))
        Text(
            if (compact) "Too noisy for accurate readings. Try a quieter spot or move closer to the mic."
            else "Background noise is affecting the readings. Try a quieter spot or move closer to the mic.",
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Countdown shown on Live during a rest. */
@Composable
fun RestBanner(until: Instant, onEnd: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val remaining = maxOf(0L, (until.toEpochMilli() - now + 500) / 1000)
    Row(
        Modifier.fillMaxWidth().background(Accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.WaterDrop, null, tint = Accent)
        Text(if (remaining > 0) "Resting: %d:%02d left".format(remaining / 60, remaining % 60) else "Rest done. Start gently.",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onEnd) { Text(if (remaining > 0) "End" else "OK") }
    }
}

/** Weight and health rows under the grid (WIREFRAMES §2). */
@Composable
fun VoiceQualityPanel(quality: VoiceQuality?, health: StrainGuard.Health, todayMinutes: Int, capMinutes: Int, modifier: Modifier = Modifier) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val weightText = quality?.weight?.let { if (it < 0.35) "Lighter" else if (it > 0.65) "Heavier" else "Medium" } ?: "No reading"
        Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "Vocal weight: $weightText" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Weight", Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium)
            Text("lighter", style = MaterialTheme.typography.labelSmall, color = secondary)
            WeightBar(quality?.weight, Modifier.weight(1f))
            Text("heavier", style = MaterialTheme.typography.labelSmall, color = secondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Health", Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.size(10.dp).background(healthColor(health), CircleShape))
            val muted = health in setOf(StrainGuard.Health.UNKNOWN, StrainGuard.Health.LEARNING, StrainGuard.Health.NOISY)
            Text(healthText(health), style = MaterialTheme.typography.bodyMedium, color = if (muted) secondary else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text("Today $todayMinutes / $capMinutes min", style = MaterialTheme.typography.labelSmall, color = secondary)
        }
    }
}

fun healthText(h: StrainGuard.Health) = when (h) {
    StrainGuard.Health.UNKNOWN -> "–"
    StrainGuard.Health.LEARNING -> "Listening…"
    StrainGuard.Health.NOISY -> "Paused: too noisy"
    StrainGuard.Health.STEADY -> "Steady"
    StrainGuard.Health.WATCH -> "Watch: ease off a little"
    StrainGuard.Health.STRAIN -> "Signs of strain"
}

@Composable
private fun healthColor(h: StrainGuard.Health): Color = when (h) {
    StrainGuard.Health.UNKNOWN, StrainGuard.Health.LEARNING, StrainGuard.Health.NOISY -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    StrainGuard.Health.STEADY -> Color(0xFF34C759)
    StrainGuard.Health.WATCH -> Color(0xFFFFCC00)
    StrainGuard.Health.STRAIN -> Color(0xFFFF9500)
}

@Composable
private fun WeightBar(value: Double?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.height(14.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f), CircleShape))
        if (value != null) {
            Box(Modifier.offset(x = (maxWidth - 14.dp) * value.toFloat()).size(14.dp).background(Accent, CircleShape))
        }
    }
}
