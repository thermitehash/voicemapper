package com.thrivestorm2.voicemapper.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.thrivestorm2.voicemapper.VoiceModel
import com.thrivestorm2.voicemapper.core.analysis.Note
import com.thrivestorm2.voicemapper.core.analysis.swiftRound

/** First-launch setup (PLAN §6). Port of iOS `OnboardingView`; wording adapted only where iPhone-specific. */
@Composable
fun OnboardingScreen(model: VoiceModel, requestMic: (() -> Unit) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var showingBaseline by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var micGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val baseline = model.store.data.baseline
    if (showingBaseline) { BaselineScreen(model, requestMic) { showingBaseline = false }; return }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
        LinearProgressIndicator(progress = { (step + 1) / 6f }, Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (step) {
                0 -> Page(Icons.Filled.GraphicEq, "Welcome to VoiceMapper") {
                    Text("VoiceMapper helps you reach your own voice goal, safely.")
                    Text("There's no \"correct\" voice here. You choose what you're aiming for, and the app shows your pitch and resonance so you can see where you are.")
                    Text("Everything stays on this phone. Nothing is uploaded, and no audio is ever saved.")
                }
                1 -> Page(Icons.Filled.MedicalServices, "Before you start") {
                    Text("VoiceMapper is a practice tool, not medical advice, and not a substitute for a speech-language pathologist.")
                    Text("Its strain alerts are estimates, not a diagnosis. Stop if anything hurts or feels scratchy, and see a professional if you have pain or hoarseness that lasts.")
                    Text("Working with a voice therapist who specialises in gender-affirming voice care is the safest way to train. Settings has a link to help you find one.")
                }
                2 -> Page(Icons.Outlined.Mic, "Microphone") {
                    Text("VoiceMapper listens to your voice to measure pitch and resonance. Audio is analysed as you speak and never stored.")
                    if (micGranted) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.CheckCircle, null, tint = Green); Text("Microphone access is on")
                    } else OutlinedButton(onClick = { requestMic { micGranted = true } }) { Icon(Icons.Outlined.Mic, null); Text("  Allow microphone") }
                    if (!micGranted) Text("If you've turned it down before, you can turn it on in Android Settings ▸ Apps ▸ VoiceMapper ▸ Permissions.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                3 -> Page(Icons.Filled.GpsFixed, "Your goal") { GoalPicker(model) }
                4 -> Page(Icons.Filled.FiberManualRecord, "Baseline (optional)") {
                    Text("Read a short passage for 30 seconds in your natural voice. It's your reference point, not a target: strain alerts compare against it, and you'll see how you change over time.")
                    baseline?.let { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.CheckCircle, null, tint = Green)
                        Text("Saved: ${Note.fromFrequency(it.medianHz).name} (${swiftRound(it.medianHz)} Hz)")
                    } }
                    OutlinedButton(onClick = { showingBaseline = true }) { Text(if (baseline == null) "Record baseline" else "Record again") }
                    Text("You can skip this and record it later in Settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Page(Icons.Filled.Headphones, "Hearing yourself") {
                    Text("Hearing your own voice through headphones while you practise is coming to the Android version later. On iPhone, wired headphones work best: Bluetooth adds a delay of about 150–250 ms.")
                    Text("The mic picks up your voice fine without headphones. Practise somewhere quiet, at a comfortable volume, and keep sessions short.")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { if (step == 5) model.prefs.onboardingDone = true else step++ }) {
                Text(when (step) {
                    0 -> "Get started"; 1 -> "I understand"; 2 -> if (micGranted) "Next" else "Not now"
                    4 -> if (baseline == null) "Skip" else "Next"; 5 -> "Start practising"; else -> "Next"
                })
            }
        }
    }
}

@Composable
private fun Page(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    Icon(icon, null, Modifier.size(44.dp), tint = Accent)
    Text(title, style = MaterialTheme.typography.headlineMedium)
    content()
}
