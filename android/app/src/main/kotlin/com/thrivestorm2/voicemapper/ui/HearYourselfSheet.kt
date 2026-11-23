package com.thrivestorm2.voicemapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thrivestorm2.voicemapper.audio.AudioCapture
import kotlin.math.roundToInt

private val Orange = Color(0xFFFF9500)

/** Port of iOS `HearYourselfSheet` (WIREFRAMES §4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HearYourselfSheet(audio: AudioCapture, requestMic: (() -> Unit) -> Unit, onDismiss: () -> Unit) {
    val hearing by audio.isHearingYourself.collectAsState()
    val headphones by audio.headphonesConnected.collectAsState()
    val bluetooth by audio.outputIsBluetooth.collectAsState()
    val output by audio.outputName.collectAsState()
    val error by audio.lastError.collectAsState()
    val mic by audio.activeMicrophoneName.collectAsState()
    val latency by audio.deviceLatencyMs.collectAsState()
    val volume by audio.systemVolume.collectAsState()
    val voiceLevel by audio.voiceLevel.collectAsState()
    val delayMs by audio.delayMs.collectAsState()
    var showingTips by remember { mutableStateOf(false) }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Hear yourself", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hear yourself", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(hearing, { on -> if (on) requestMic { audio.setHearYourself(true) } else audio.setHearYourself(false) })
            }
            if (!headphones) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Headphones, null, tint = secondary, modifier = Modifier.size(18.dp))
                    Text("Connect headphones to hear yourself.", style = MaterialTheme.typography.bodySmall, color = secondary)
                }
            } else output?.let { name ->
                Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Text("Route", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(if (bluetooth) "$name (Bluetooth)" else name, color = secondary)
                    if (bluetooth) Icon(Icons.Filled.Warning, null, tint = Orange, modifier = Modifier.padding(start = 4.dp).size(18.dp))
                }
                if (bluetooth) Text("Wired headphones recommended: Bluetooth adds ~150–250 ms delay.",
                    style = MaterialTheme.typography.bodySmall, color = Orange)
            }
            // The no-headphones hint above already says this one.
            error?.takeIf { headphones || it != "Connect headphones to hear yourself." }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Orange)
            }

            HorizontalDivider()
            mic?.let { Row { Text("Using", Modifier.weight(1f)); Text(it, color = secondary) } }
            if (hearing) {
                Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Text("Device delay")
                    IconButton(onClick = { showingTips = true }, modifier = Modifier.semantics { contentDescription = "Tips to reduce delay" }) {
                        Icon(Icons.Outlined.Info, null, tint = Accent)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        val ms = latency
                        if (ms == null) Text("Measuring…", color = secondary)
                        else {
                            val level = DelayLevel.of(ms, bluetooth)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(8.dp).background(level.color, CircleShape))
                                Text("${level.label} · ${ms.roundToInt()} ms")
                            }
                        }
                        if (bluetooth) Text("+ Bluetooth (~150–250 ms)", style = MaterialTheme.typography.labelSmall, color = secondary)
                    }
                }
            }
            Text("VoiceMapper uses the phone's own mic, even with a headset plugged in. It records your voice at full quality with no wireless delay, so pitch and resonance readings are as accurate as possible.",
                style = MaterialTheme.typography.bodySmall, color = secondary)

            HorizontalDivider()
            if (headphones) {
                val loud = audio.isAboveSafeVolume()
                LabeledSlider("Headphone volume", "${(volume * 100).roundToInt()}%", volume, { audio.setSystemVolume(it) },
                    tint = if (loud) Orange else null)
                if (loud) Text("Above the safe-listening level. Long sessions this loud can harm your hearing.",
                    style = MaterialTheme.typography.bodySmall, color = Orange)
            }
            LabeledSlider("Voice level", "${(voiceLevel * 100).roundToInt()}%", voiceLevel, { audio.setVoiceLevel(it) })
            LabeledSlider("Delay", "${delayMs.roundToInt()} ms", (delayMs / AudioCapture.MAX_DELAY_MS).toFloat(),
                { audio.setDelayMs((it * AudioCapture.MAX_DELAY_MS / 10).roundToInt() * 10.0) })
            Text("Headphone volume is the same as your phone's volume buttons. Voice level sets how loud your voice is. Delay is for delayed-feedback drills; leave it at 0 ms for normal practice.",
                style = MaterialTheme.typography.bodySmall, color = secondary)
        }
    }
    if (showingTips) DelayTips { showingTips = false }
}

@Composable
private fun LabeledSlider(title: String, value: String, fraction: Float, onChange: (Float) -> Unit, tint: Color? = null) {
    Column {
        Row {
            Text(title, Modifier.weight(1f))
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(fraction, onChange,
            colors = if (tint != null) SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint) else SliderDefaults.colors(),
            modifier = Modifier.semantics { contentDescription = title })
    }
}

/** How noticeable the device delay is when hearing yourself (same thresholds as iOS). */
private enum class DelayLevel(val label: String, val color: Color) {
    LOW("Low", Color(0xFF34C759)), NOTICEABLE("Noticeable", Color(0xFFFFCC00)), HIGH("High", Color(0xFFFF3B30));

    companion object {
        fun of(ms: Double, bluetooth: Boolean) = if (bluetooth || ms > 80) HIGH else if (ms > 30) NOTICEABLE else LOW
    }
}

@Composable
private fun DelayTips(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reducing delay") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("• Use wired headphones. Bluetooth adds about 150–250 ms.")
                Text("• Headphones with a cable may still use Bluetooth while it's connected. To force the cable: plug it in, turn Bluetooth off in quick settings, then check that Route no longer says Bluetooth.")
                Text("• On earbuds, turn off ambient sound or transparency mode. It adds processing.")
                Text("Some headphones add delay the phone can't report, so it may feel longer than shown.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** "Wired headphones recommended" banner on Live (iOS `BluetoothBanner`). */
@Composable
fun BluetoothBanner(onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Orange.copy(alpha = 0.15f), RoundedCornerShape(12.dp)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Warning, null, tint = Orange)
        Text("Wired headphones recommended: Bluetooth adds ~150–250 ms delay.",
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Normal, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "Dismiss" }) {
            Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
