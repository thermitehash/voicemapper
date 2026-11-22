package com.thrivestorm2.voicemapper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrivestorm2.voicemapper.VoiceModel
import com.thrivestorm2.voicemapper.core.analysis.Baseline
import com.thrivestorm2.voicemapper.core.analysis.Note
import com.thrivestorm2.voicemapper.core.analysis.QualitySummary
import com.thrivestorm2.voicemapper.core.analysis.RainbowPassage
import com.thrivestorm2.voicemapper.core.analysis.Stats
import com.thrivestorm2.voicemapper.core.analysis.swiftRound
import com.thrivestorm2.voicemapper.core.dsp.VoiceQuality
import kotlinx.coroutines.delay
import java.time.Instant

private const val LENGTH = 30.0

/** Records 30 s of the user's natural voice and stores the medians. Port of iOS `BaselineView`. */
@Composable
fun BaselineScreen(model: VoiceModel, requestMic: (() -> Unit) -> Unit, onDone: () -> Unit) {
    val pitch by model.audio.pitch.collectAsState()
    val listening by model.audio.isListening.collectAsState()
    val noisy by model.audio.isNoisy.collectAsState()
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableDoubleStateOf(0.0) }
    var result by remember { mutableStateOf<String?>(null) }
    val hz = remember { mutableListOf<Double>() }
    val scores = remember { mutableListOf<Double>() }
    val qualities = remember { mutableListOf<VoiceQuality>() }

    fun cancel() { if (recording) { recording = false; model.setListening(false) } }
    DisposableEffect(Unit) { onDispose { if (recording) model.setListening(false) } }
    BackHandler { cancel(); onDone() }

    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        while (elapsed < LENGTH && recording) {
            delay(200)
            if (!model.audio.isListening.value) continue
            elapsed += 0.2
            model.audio.pitch.value?.let { p ->
                hz += p.frequency
                model.audio.resonance.value?.let { scores += it.score }
                val q = model.audio.quality.value
                if (q != null && !model.audio.isNoisy.value) qualities += q
            }
        }
        if (!recording) return@LaunchedEffect
        recording = false
        model.setListening(false)
        val med = Stats.median(hz)
        if (hz.size * 5 < 50 || med == null) { // need ≥ 10 s voiced
            result = "Not enough voice heard. Try again, a little closer to the mic."
        } else {
            model.store.setBaseline(Baseline(Instant.now(), med, Stats.median(scores), QualitySummary.of(qualities)))
            result = "Saved: median ${Note.fromFrequency(med).name} (${swiftRound(med)} Hz)"
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Baseline", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { cancel(); onDone() }) { Text("Done") }
        }
        Text("Read this passage aloud in your natural, comfortable voice. This is your reference point, not a target.", textAlign = TextAlign.Center)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(RainbowPassage.TEXT, style = MaterialTheme.typography.titleLarge)
            Text(RainbowPassage.CITATION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (recording) {
            LinearProgressIndicator(progress = { (elapsed / LENGTH).toFloat() }, Modifier.fillMaxWidth())
            if (noisy) NoiseBanner()
            Text(pitch?.note?.name ?: "–", fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { cancel() }) { Text("Cancel") }
        } else {
            result?.let { Text(it, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
            Button(onClick = {
                requestMic {
                    result = null; elapsed = 0.0; hz.clear(); scores.clear(); qualities.clear()
                    model.setListening(true)
                    recording = true
                }
            }) { Text(if (result == null) "Record" else "Record again") }
            if (listening) Spacer(Modifier.padding(0.dp))
        }
    }
}
