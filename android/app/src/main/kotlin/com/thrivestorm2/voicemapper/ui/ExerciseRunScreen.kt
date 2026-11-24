package com.thrivestorm2.voicemapper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrivestorm2.voicemapper.VoiceModel
import com.thrivestorm2.voicemapper.core.analysis.Exercise
import com.thrivestorm2.voicemapper.core.analysis.ExerciseLog
import com.thrivestorm2.voicemapper.core.analysis.ExerciseResult
import com.thrivestorm2.voicemapper.core.analysis.Note
import com.thrivestorm2.voicemapper.core.analysis.Progression
import com.thrivestorm2.voicemapper.core.analysis.RainbowPassage
import com.thrivestorm2.voicemapper.core.analysis.Stage
import com.thrivestorm2.voicemapper.core.analysis.Stats
import com.thrivestorm2.voicemapper.core.analysis.StrainGuard
import com.thrivestorm2.voicemapper.core.analysis.swiftRound
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil

private const val TICK = 0.1
private const val HOLD_GRACE = 0.6
private const val REP_PAUSE = 5.0
private const val HOLD_TOLERANCE = 1.0

/** Port of iOS `ExerciseRunView`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseRunScreen(model: VoiceModel, exercise: Exercise, requestMic: (() -> Unit) -> Unit, onClose: () -> Unit) {
    val prefs = model.prefs
    val pitch by model.audio.pitch.collectAsState()
    val resonance by model.audio.resonance.collectAsState()
    val listening by model.audio.isListening.collectAsState()
    val noisy by model.audio.isNoisy.collectAsState()
    val baselineHz = model.store.data.baseline?.medianHz

    val isSteppedHold = exercise.kind is Exercise.Kind.Hold && model.stage == Stage.PITCH
    val defaultTarget = when {
        isSteppedHold -> Progression.currentHoldStep(prefs.holdStep, prefs.goal, baselineHz)
        prefs.goal != null -> (prefs.goal!!.low + prefs.goal!!.high) / 2
        baselineHz != null -> Note.fromFrequency(baselineHz).midi
        else -> 57
    }

    var startedCapture by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(0) }
    var stepElapsed by remember { mutableDoubleStateOf(0.0) }
    var targetMidi by remember { mutableIntStateOf(defaultTarget) }
    var rep by remember { mutableIntStateOf(0) }
    var held by remember { mutableDoubleStateOf(0.0) }
    var offTarget by remember { mutableDoubleStateOf(0.0) }
    var pauseLeft by remember { mutableDoubleStateOf(0.0) }
    var readSeconds by remember { mutableDoubleStateOf(0.0) }
    val pitchSamples = remember { mutableListOf<Double>() }
    val resonanceSamples = remember { mutableListOf<Double>() }
    var strainAlerts by remember { mutableIntStateOf(0) }
    var nextStepMessage by remember { mutableStateOf<String?>(null) }
    var tooLittleVoice by remember { mutableStateOf(false) }
    var semitoneRange by remember { mutableStateOf<Double?>(null) }
    val done = ExerciseLog.done(prefs.doneToday, LocalDate.now())

    val requiredVoice = when (val k = exercise.kind) {
        is Exercise.Kind.Guided -> minOf(20.0, 0.25 * k.steps.sumOf { it.seconds })
        else -> 0.0 // can't finish without voice anyway
    }

    fun stopIfStarted() { if (startedCapture) { startedCapture = false; model.setListening(false) } }

    fun finish() {
        finished = true
        prefs.doneToday = ExerciseLog.marking(exercise.id, prefs.doneToday, LocalDate.now())
        val voiced = pitchSamples.size * TICK
        val lo = Stats.percentile(pitchSamples, 0.1); val hi = Stats.percentile(pitchSamples, 0.9)
        val result = ExerciseResult(
            date = Instant.now(), exerciseId = exercise.id, stage = prefs.stage,
            passed = strainAlerts == 0 && voiced >= requiredVoice, voicedSeconds = voiced, strainAlerts = strainAlerts,
            targetMidi = if (exercise.kind is Exercise.Kind.Hold) targetMidi else null,
            medianHz = Stats.median(pitchSamples),
            semitoneRange = if (lo != null && hi != null) Note.midiValue(hi) - Note.midiValue(lo) else null,
            medianResonance = Stats.median(resonanceSamples),
        )
        tooLittleVoice = voiced < requiredVoice
        semitoneRange = result.semitoneRange
        model.store.addResult(result)
        if (isSteppedHold && result.passed) {
            val input = model.progressionInput().copy(stage = Stage.PITCH, sessions = emptyList(), strainDates = emptyList(), resonanceGoal = null)
            val current = Progression.currentHoldStep(prefs.holdStep, input.goal, input.baselineHz)
            val next = Progression.nextHoldStep(input)
            prefs.holdStep = next
            nextStepMessage = if (next != current) "Step passed. Your next hold target is ${Note(next).name}." else null
        }
        stopIfStarted()
    }

    fun nextStep(steps: List<Exercise.Step>) {
        stepElapsed = 0.0
        if (step + 1 < steps.size) step++ else finish()
    }

    // Count strain alerts that fire while running.
    val alert = model.alert
    var lastAlert by remember { mutableStateOf<StrainGuard.Alert?>(null) }
    LaunchedEffect(alert) {
        if (listening && !finished && lastAlert == null && alert is StrainGuard.Alert.Strain) strainAlerts++
        lastAlert = alert
    }

    val currentPitch by rememberUpdatedState(pitch)
    val currentResonance by rememberUpdatedState(resonance)
    val running by rememberUpdatedState(listening && !finished)
    LaunchedEffect(Unit) {
        while (true) {
            delay((TICK * 1000).toLong())
            if (!running) continue
            val p = currentPitch
            if (p != null) { pitchSamples += p.frequency; currentResonance?.let { resonanceSamples += it.score } }
            when (val k = exercise.kind) {
                is Exercise.Kind.Guided -> {
                    stepElapsed += TICK
                    if (stepElapsed >= k.steps[minOf(step, k.steps.lastIndex)].seconds) nextStep(k.steps)
                }
                is Exercise.Kind.Hold -> {
                    if (pauseLeft > 0) { pauseLeft = maxOf(0.0, pauseLeft - TICK); continue }
                    val d = p?.let { Note.midiValue(it.frequency) - targetMidi }
                    if (d != null && abs(d) <= HOLD_TOLERANCE) { held += TICK; offTarget = 0.0 }
                    else { offTarget += TICK; if (offTarget > HOLD_GRACE) held = 0.0 }
                    if (held >= k.seconds) {
                        rep++; held = 0.0; offTarget = 0.0
                        if (rep >= k.reps) finish() else pauseLeft = REP_PAUSE
                    }
                }
                is Exercise.Kind.Reading -> {
                    if (p != null) readSeconds += TICK
                    if (readSeconds >= k.seconds) finish()
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { if (startedCapture) model.setListening(false) } }
    BackHandler { onClose() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(exercise.title) }, navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to exercises") }
        }) },
        bottomBar = {
            if (!finished) Surface(tonalElevation = 3.dp) {
                Button(onClick = {
                    if (listening) model.setListening(false)
                    else requestMic { model.setListening(true); startedCapture = true }
                }, Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) {
                    Icon(if (listening) Icons.Filled.Pause else Icons.Outlined.Mic, null)
                    Text(if (listening) "  Pause" else if (model.restUntil != null) "  Start anyway" else "  Start")
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            model.restUntil?.let { RestBanner(it) { model.endRest() } }
            if (noisy && listening && !finished) NoiseBanner()
            if (!finished && !exercise.isWarmUp && !exercise.isCoolDown && Exercise.WARM_UP_ID !in done) {
                Row(Modifier.fillMaxWidth().background(Color(0xFFFFCC00).copy(alpha = 0.15f), RoundedCornerShape(12.dp)).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.WbTwilight, null)
                    Text("You haven't warmed up today. A few minutes of warm-up first protects your voice.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (finished) {
                FinishedView(exercise, nextStepMessage, semitoneRange, tooLittleVoice, strainAlerts, Exercise.COOL_DOWN_ID in done, onClose)
            } else {
                Column(Modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(pitch?.note?.name ?: "–", fontSize = 56.sp, fontWeight = FontWeight.SemiBold,
                        color = if (pitch == null) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface)
                    Text(pitch?.let { "${swiftRound(it.frequency)} Hz" } ?: if (listening) "Listening…" else "Tap Start when ready",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (exercise.showsResonance) ResonanceMeter(resonance?.score, Modifier.padding(top = 8.dp))
                }
                when (val k = exercise.kind) {
                    is Exercise.Kind.Guided -> {
                        val current = k.steps[minOf(step, k.steps.lastIndex)]
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Step ${minOf(step, k.steps.lastIndex) + 1} of ${k.steps.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(rs(current.text), style = MaterialTheme.typography.titleLarge)
                            LinearProgressIndicator(progress = { minOf(stepElapsed / current.seconds, 1.0).toFloat() }, Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${ceil(maxOf(0.0, current.seconds - stepElapsed)).toInt()} s left", Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = { nextStep(k.steps) }) { Text(if (step + 1 < k.steps.size) "Next step" else "Finish") }
                            }
                        }
                    }
                    is Exercise.Kind.Hold -> {
                        val note = Note(targetMidi)
                        val d = pitch?.let { Note.midiValue(it.frequency) - targetMidi }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("Hold target: ${note.name} (${swiftRound(note.frequency)} Hz)", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { if (targetMidi > 36) { targetMidi--; held = 0.0 } },
                                    Modifier.semantics { contentDescription = "Target note lower, now ${note.name}" }) { Text("−") }
                                OutlinedButton(onClick = { if (targetMidi < 84) { targetMidi++; held = 0.0 } },
                                    Modifier.semantics { contentDescription = "Target note higher, now ${note.name}" }) { Text("+") }
                            }
                            if (pauseLeft > 0) Text("Relax and breathe… ${ceil(pauseLeft).toInt()} s", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else Text(
                                when { d == null -> if (listening) "Sing or hum the target note" else " "
                                    abs(d) <= HOLD_TOLERANCE -> "On target, hold it"
                                    d < 0 -> "A little higher"
                                    else -> "A little lower" },
                                color = when { d == null -> MaterialTheme.colorScheme.onSurfaceVariant; abs(d) <= HOLD_TOLERANCE -> Green; else -> Color(0xFFFF9500) })
                            LinearProgressIndicator(progress = { minOf(held / k.seconds, 1.0).toFloat() }, Modifier.fillMaxWidth())
                            Text("${held.toInt()} s / ${k.seconds.toInt()} s · hold ${minOf(rep + 1, k.reps)} of ${k.reps}",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (isSteppedHold && prefs.goal != null) Text("Stage 2 steps: this target moves 1 semitone towards your goal after you pass it on ${Progression.PASSES_PER_STEP} separate days.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            Text("Counts while you stay within a semitone. Comfortable volume. If it feels tight, pick an easier target.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                    is Exercise.Kind.Reading -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(rs("Read aloud slowly, using the voice you're practising. Stop and breathe whenever you need to."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(rs(RainbowPassage.TEXT), style = MaterialTheme.typography.titleLarge)
                        Text(RainbowPassage.CITATION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress = { minOf(readSeconds / k.seconds, 1.0).toFloat() }, Modifier.fillMaxWidth())
                        Text("${readSeconds.toInt()} s of ${k.seconds.toInt()} s voiced", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                BasisView(exercise.basis)
            }
        }
    }
}

@Composable
private fun FinishedView(exercise: Exercise, nextStepMessage: String?, semitoneRange: Double?, tooLittleVoice: Boolean,
                         strainAlerts: Int, coolDownDone: Boolean, onClose: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Filled.CheckCircle, null, tint = Green, modifier = Modifier.size(64.dp))
        Text("Done", style = MaterialTheme.typography.headlineSmall)
        nextStepMessage?.let { Text(it, textAlign = TextAlign.Center) }
        if (exercise.id == Exercise.INTONATION_ID && semitoneRange != null && !tooLittleVoice)
            Text("Your pitch moved across about ${swiftRound(semitoneRange)} semitones. There's no right amount: use the melody that feels like you.", textAlign = TextAlign.Center)
        if (tooLittleVoice) Text(rs("The mic heard very little voice, so this won't count towards your stage. Next time, tap Start and practise aloud."), textAlign = TextAlign.Center, color = muted)
        else if (strainAlerts > 0) Text("There was a strain alert during this exercise, so it won't count towards moving on. Rest your voice before trying again.", textAlign = TextAlign.Center, color = muted)
        if (exercise.isCoolDown) Text("Nice work today. Rest your voice and drink some water.", textAlign = TextAlign.Center)
        else if (!coolDownDone) Text(rs("When you've finished practising for today, end with the cool-down."), textAlign = TextAlign.Center, color = muted)
        Button(onClick = onClose) { Text("Back to exercises") }
    }
}

@Composable
private fun BasisView(basis: Exercise.Basis) {
    var open by remember { mutableStateOf(false) }
    val tint = when (basis.strength) {
        Exercise.Basis.Strength.RESEARCH -> Green
        Exercise.Basis.Strength.CLINICAL -> Accent
        Exercise.Basis.Strength.LIMITED -> Color(0xFFFF9500)
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text("Why this exercise", Modifier.weight(1f))
            Text(rs(basis.strength.label), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.background(tint.copy(alpha = 0.15f), CircleShape).padding(horizontal = 8.dp, vertical = 3.dp))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Collapse" else "Expand")
        }
        if (open) Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(rs(basis.summary), style = MaterialTheme.typography.bodyMedium)
            basis.citations.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("Keep to a comfortable volume, and stop if anything hurts or feels scratchy.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResonanceMeter(score: Double?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription = "Resonance"
        stateDescription = score?.let { "${swiftRound(it)} of 100, darker to brighter" } ?: "No reading"
    }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(8.dp).background(
                Brush.horizontalGradient(listOf(Color(0x800A84FF), Color(0x80AF52DE), Color(0x80FF2D55))), CircleShape))
            if (score != null) Box(Modifier.offset(x = (maxWidth - 18.dp) * (score / 100).toFloat()).size(18.dp)
                .background(Color.White, CircleShape).border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape))
        }
        Row {
            val st = MaterialTheme.typography.labelSmall; val c = MaterialTheme.colorScheme.onSurfaceVariant
            Text("Darker", style = st, color = c)
            Text(score?.let { "Resonance ${swiftRound(it)}" } ?: "Resonance –", Modifier.weight(1f), style = st, color = c, textAlign = TextAlign.Center)
            Text("Brighter", style = st, color = c)
        }
    }
}
