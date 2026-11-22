package com.thrivestorm2.voicemapper

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thrivestorm2.voicemapper.audio.AudioCapture
import androidx.biometric.BiometricManager
import com.thrivestorm2.voicemapper.core.analysis.Exercise
import com.thrivestorm2.voicemapper.core.analysis.GridCell
import com.thrivestorm2.voicemapper.core.analysis.PracticeSession
import com.thrivestorm2.voicemapper.core.analysis.Progression
import com.thrivestorm2.voicemapper.core.analysis.QualitySummary
import com.thrivestorm2.voicemapper.core.analysis.Stage
import com.thrivestorm2.voicemapper.core.analysis.Stats
import com.thrivestorm2.voicemapper.core.analysis.StrainGuard
import com.thrivestorm2.voicemapper.core.dsp.VoiceQuality
import com.thrivestorm2.voicemapper.data.Prefs
import com.thrivestorm2.voicemapper.data.Store
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * App-wide state: capture, storage, settings, strain guard and the session
 * recorder (port of iOS `SessionRecorder`: samples the latest readings
 * 5×/second and saves a session each time capture stops).
 */
class VoiceModel(app: Application) : AndroidViewModel(app) {
    companion object {
        const val TICK = 0.2
        /** Sessions with less voiced time than this are discarded as accidental taps. */
        const val MINIMUM_VOICED = 3.0
        /** A saved session noisier than this asks whether to keep it. */
        const val NOISY_PROMPT_SHARE = 0.3
        const val LOCK_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }

    val audio = AudioCapture(app)
    val store = Store(app)
    val prefs = Prefs(app)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val guard = StrainGuard(alertsEnabled = { prefs.strainAlerts }, capMinutesSetting = { prefs.capMinutes })

    // Compose-visible copies of the strain guard's state, refreshed every tick.
    var health by mutableStateOf(StrainGuard.Health.UNKNOWN); private set
    var alert by mutableStateOf<StrainGuard.Alert?>(null); private set
    var restUntil by mutableStateOf<Instant?>(null); private set
    var todaySeconds by mutableDoubleStateOf(0.0); private set
    /** Just-saved session with a lot of background noise, awaiting Keep/Delete. */
    var noisySession by mutableStateOf<PracticeSession?>(null)

    /** The exercise being run, shown over the tabs. */
    var runningExercise by mutableStateOf<Exercise?>(null)

    // App lock (iOS AppLock): biometric or screen lock, set up by MainActivity.
    var isLocked by mutableStateOf(prefs.appLock); private set
    var lockError by mutableStateOf<String?>(null); private set
    /** Shows the system prompt; set by the activity, which owns the BiometricPrompt. */
    var authenticate: ((reason: String, done: (ok: Boolean, error: String?) -> Unit) -> Unit)? = null
    val lockAvailable: Boolean
        get() = BiometricManager.from(getApplication()).canAuthenticate(LOCK_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun lockIfEnabled() { if (prefs.appLock) isLocked = true }

    fun unlock() {
        if (!isLocked) return
        val auth = authenticate ?: return
        auth("Unlock VoiceMapper") { ok, error ->
            if (ok) { isLocked = false; lockError = null } else lockError = error
        }
    }

    /** Turning the lock on asks first, so it can't lock you out. */
    fun confirmEnableLock(done: (Boolean) -> Unit) {
        val auth = authenticate ?: return done(false)
        auth("Turn on the VoiceMapper lock") { ok, _ -> done(ok) }
    }

    private var start: Instant? = null
    private val hz = mutableListOf<Double>()
    private var noisyTicks = 0
    private val scores = mutableListOf<Double>()
    private val qualities = mutableListOf<VoiceQuality>()
    private val cells = HashMap<Int, HashMap<Int, Double>>()
    private var ticker: Job? = null

    init {
        guard.setToday(practicedToday())
        syncGuard()
        ticker = viewModelScope.launch {
            while (isActive) {
                delay((TICK * 1000).toLong())
                sample()
            }
        }
    }

    val isCapturing: Boolean get() = audio.isListening.value

    fun setListening(on: Boolean) {
        if (on) endRest()
        audio.setListening(on)
    }

    private fun sample() {
        if (!audio.isListening.value) { finish(); syncGuard(); return }
        if (start == null) begin()
        val noisy = audio.isNoisy.value
        val quality = if (noisy) null else audio.quality.value
        val pitch = audio.pitch.value
        val hadAlert = guard.alert != null
        guard.observe(TICK, pitch?.frequency, quality, noisy)
        if (!hadAlert && guard.alert is StrainGuard.Alert.Strain) store.addStrainEvent(Instant.now())
        syncGuard()
        if (pitch == null) return
        hz += pitch.frequency
        if (noisy) noisyTicks++
        if (quality != null) qualities += quality
        audio.resonance.value?.let { r ->
            scores += r.score
            cells.getOrPut(pitch.note.midi) { HashMap() }.merge(GridCell.bin(r.score), TICK, Double::plus)
        }
    }

    private fun begin() {
        start = Instant.now()
        val baseline = store.data.baseline
        guard.beginSession(practicedToday(), baseline?.quality, baseline?.let { it.medianHz * 2 })
    }

    /** Capture seconds in sessions saved since midnight. */
    fun practicedToday(): Double {
        val midnight = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        return store.data.sessions.filter { it.start >= midnight }.sumOf { it.duration }
    }

    /** Saves the open session (if worth keeping). Also called when the app goes to the background. */
    fun finish() {
        val s = start
        try {
            if (s == null) return
            val voiced = hz.size * TICK
            if (voiced < MINIMUM_VOICED) return
            val med = Stats.median(hz) ?: return
            val session = PracticeSession(
                start = s,
                duration = Duration.between(s, Instant.now()).toMillis() / 1000.0,
                voicedSeconds = voiced,
                medianHz = med,
                p10Hz = Stats.percentile(hz, 0.1)!!,
                p90Hz = Stats.percentile(hz, 0.9)!!,
                medianResonance = Stats.median(scores),
                quality = QualitySummary.of(qualities),
                noisyShare = noisyTicks.toDouble() / hz.size,
                cells = cells.flatMap { (midi, bins) -> bins.map { (bin, sec) -> GridCell(midi, bin, sec) } },
            )
            val saved = store.addSession(session)
            if ((saved.noisyShare ?: 0.0) >= NOISY_PROMPT_SHARE) noisySession = saved
        } finally {
            if (s != null) {
                guard.endSession()
                guard.setToday(practicedToday())
            }
            start = null; hz.clear(); noisyTicks = 0; scores.clear(); qualities.clear(); cells.clear()
        }
    }

    fun deleteSession(session: PracticeSession) {
        if (noisySession?.id == session.id) noisySession = null
        store.deleteSession(session.id)
        if (start == null) guard.setToday(practicedToday())
        syncGuard()
    }

    // Strain alert actions (SafetyViews on iOS).
    fun rest() { guard.rest(); setListening(false); syncGuard() }
    fun endRest() { guard.endRest(); syncGuard() }
    fun dismissAlert() { guard.dismiss(); syncGuard() }
    fun stopForToday() { guard.stopForToday(); setListening(false); syncGuard() }

    private fun syncGuard() {
        health = guard.health
        alert = guard.alert
        restUntil = guard.restUntil?.takeIf { it.isAfter(Instant.now()) }
        todaySeconds = guard.todaySeconds
    }

    // Progression helpers shared by Exercises, Settings and History.

    val stage: Stage get() = Stage.of(prefs.stage)

    fun progressionInput() = Progression.Input(
        now = Instant.now(), stage = stage, stageStart = prefs.stageStartInstant,
        sessions = store.data.sessions, results = store.data.results, strainDates = store.data.strainEvents,
        baselineHz = store.data.baseline?.medianHz, goal = prefs.goal, resonanceGoal = prefs.resonanceGoal,
        holdStep = prefs.holdStep, capMinutes = prefs.capMinutes,
    )

    fun chooseStage(s: Stage) {
        prefs.stage = s.number
        prefs.stageStart = Instant.now().toEpochMilli() / 1000.0
    }

    /** Settings ▸ Delete all data. */
    fun deleteAll() {
        setListening(false)
        start = null; hz.clear(); noisyTicks = 0; scores.clear(); qualities.clear(); cells.clear()
        guard.endSession()
        noisySession = null
        store.deleteAll()
        prefs.clearAll()
        guard.stopForToday(); guard.endRest(); guard.setToday(0.0)
        runningExercise = null
        isLocked = false; lockError = null
        syncGuard()
    }

    override fun onCleared() {
        finish()
        audio.release()
    }
}
