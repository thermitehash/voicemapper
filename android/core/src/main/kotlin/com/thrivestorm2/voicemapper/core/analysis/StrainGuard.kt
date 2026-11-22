package com.thrivestorm2.voicemapper.core.analysis

import com.thrivestorm2.voicemapper.core.dsp.VoiceQuality
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Heuristic strain watch and daily practice cap (PLAN §5). Port of iOS
 * `Analysis/StrainGuard.swift`. Not diagnostic: compares the user with their
 * own reference (the baseline, else the first minute of the session).
 * Fed 5×/second. Settings are read through the lambdas so the app can back
 * them with its preferences.
 */
class StrainGuard(
    private val alertsEnabled: () -> Boolean = { true },
    private val capMinutesSetting: () -> Int = { DEFAULT_CAP_MINUTES },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    enum class Health { UNKNOWN, LEARNING, NOISY, STEADY, WATCH, STRAIN }

    sealed interface Alert {
        data class Strain(val reasons: List<String>) : Alert
        data class DailyCap(val minutes: Int) : Alert
    }

    companion object {
        const val ALERTS_KEY = "safety.strainAlerts"
        const val CAP_KEY = "safety.dailyCapMinutes"
        const val DEFAULT_CAP_MINUTES = 30
        val REST_LENGTH: Duration = Duration.ofMinutes(5)
        val COOLDOWN: Duration = Duration.ofMinutes(5)
        const val CAP_EXTENSION = 5

        private const val HEALTH_WINDOW = 30.0
        private const val HEALTH_MINIMUM = 10.0
        private const val RANGE_WINDOW = 60.0
        private const val RANGE_MINIMUM = 25.0
        private const val RANGE_SHARE = 0.75
        private const val LOUD_WINDOW = 30.0
        private const val LOUD_MINIMUM = 12.0
        private const val SESSION_REFERENCE_LENGTH = 60.0
        private const val SESSION_REFERENCE_MINIMUM = 30.0
    }

    var health = Health.UNKNOWN; private set
    var alert: Alert? = null
    var restUntil: Instant? = null; private set
    /** Practice (capture) time today, including the open session. */
    var todaySeconds = 0.0; private set

    val capMinutes: Int get() = capMinutesSetting().takeIf { it > 0 } ?: DEFAULT_CAP_MINUTES

    private data class Sample(val time: Instant, val hz: Double, val quality: VoiceQuality?)

    private val samples = ArrayDeque<Sample>()
    private var baseline: QualitySummary? = null
    private var topHz: Double? = null
    private val sessionReference = mutableListOf<VoiceQuality>()
    private var cooldownUntil: Instant? = null
    private var extraMinutes = 0
    private var day: LocalDate = LocalDate.now(zone)

    /** [topHz] is the top of the comfortable range (an octave above the baseline median). */
    fun beginSession(priorToday: Double, baseline: QualitySummary?, topHz: Double?, now: Instant = Instant.now()) {
        rollDay(now)
        todaySeconds = priorToday
        this.baseline = baseline
        this.topHz = topHz
        samples.clear()
        sessionReference.clear()
        health = Health.LEARNING
    }

    fun setToday(seconds: Double, now: Instant = Instant.now()) {
        rollDay(now)
        todaySeconds = seconds
    }

    fun endSession() {
        samples.clear()
        health = Health.UNKNOWN
    }

    /** [quality] should be null while [noisy]: noise would look like strain. */
    fun observe(tick: Double, pitch: Double?, quality: VoiceQuality?, noisy: Boolean = false, now: Instant = Instant.now()) {
        rollDay(now)
        todaySeconds += tick
        if (alert == null && todaySeconds >= (capMinutes + extraMinutes) * 60.0) {
            alert = Alert.DailyCap(capMinutes)
        }
        if (pitch == null) return

        samples.addLast(Sample(now, pitch, quality))
        val oldest = now.minusMillis((RANGE_WINDOW * 2 * 1000).toLong())
        while (samples.isNotEmpty() && samples.first().time < oldest) samples.removeFirst()
        if (baseline == null && quality != null && sessionReference.size * tick < SESSION_REFERENCE_LENGTH) {
            sessionReference.add(quality)
        }

        val reasons = healthReasons(tick)
        health = when {
            noisy -> Health.NOISY
            reasons == null -> Health.LEARNING
            reasons.size >= 2 -> Health.STRAIN
            reasons.isEmpty() -> Health.STEADY
            else -> Health.WATCH
        }

        if (!alertsEnabled() || alert != null || now < (cooldownUntil ?: Instant.MIN)) return
        val triggers = if ((reasons?.size ?: 0) >= 2) reasons!!.toMutableList() else mutableListOf()
        if (sustainedHigh(tick)) triggers.add("a long stretch at the top of your range")
        if (sustainedLoud(tick)) triggers.add("speaking loudly for a while")
        if (triggers.isNotEmpty()) {
            alert = Alert.Strain(triggers)
            cooldownUntil = now.plus(COOLDOWN)
            samples.clear()
        }
    }

    fun rest(now: Instant = Instant.now()) {
        alert = null
        restUntil = now.plus(REST_LENGTH)
    }

    fun endRest() { restUntil = null }

    fun dismiss() {
        if (alert is Alert.DailyCap) extraMinutes += CAP_EXTENSION
        alert = null
    }

    fun stopForToday() { alert = null }

    private val reference: QualitySummary?
        get() {
            baseline?.let { return it }
            if (sessionReference.size * 0.2 < SESSION_REFERENCE_MINIMUM) return null
            return QualitySummary.of(sessionReference)
        }

    private fun healthReasons(tick: Double): List<String>? {
        val ref = reference ?: return null
        val recent = voiced(HEALTH_WINDOW).mapNotNull { it.quality }
        if (recent.size * tick < HEALTH_MINIMUM) return null
        val now = VoiceQuality.median(recent) ?: return null
        val reasons = mutableListOf<String>()
        if (now.hnr < ref.hnr - 4 || now.cpp < ref.cpp - 4) reasons.add("a less clear, noisier tone")
        val j = now.jitter; val rj = ref.jitter
        if (j != null && rj != null && j > maxOf(1.6 * rj, rj + 0.5)) reasons.add("less steady pitch")
        val s = now.shimmer; val rs = ref.shimmer
        if (s != null && rs != null && s > maxOf(1.5 * rs, rs + 2)) reasons.add("less steady loudness")
        return reasons
    }

    private fun sustainedHigh(tick: Double): Boolean {
        val top = topHz ?: return false
        val recent = voiced(RANGE_WINDOW)
        if (recent.size * tick < RANGE_MINIMUM) return false
        return recent.count { it.hz >= top } >= RANGE_SHARE * recent.size
    }

    /** 10 dB over the reference, or above −14 dBFS with no reference. */
    private fun sustainedLoud(tick: Double): Boolean {
        val levels = voiced(LOUD_WINDOW).mapNotNull { it.quality?.level }
        if (levels.size * tick < LOUD_MINIMUM) return false
        val level = Stats.median(levels) ?: return false
        val limit = reference?.let { it.level + 10 } ?: -14.0
        return level > limit
    }

    private fun voiced(seconds: Double): List<Sample> {
        val last = samples.lastOrNull() ?: return emptyList()
        val cutoff = last.time.minusMillis((seconds * 1000).toLong())
        return samples.filter { it.time >= cutoff }
    }

    private fun rollDay(now: Instant) {
        val today = LocalDate.ofInstant(now, zone)
        if (today == day) return
        day = today
        todaySeconds = 0.0
        extraMinutes = 0
    }
}
