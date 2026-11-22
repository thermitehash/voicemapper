package com.thrivestorm2.voicemapper.core.analysis

import com.thrivestorm2.voicemapper.core.dsp.VoiceQuality
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Plain versions of the iOS SwiftData models (Store/Models.swift). The app's
// Room entities map to and from these, so all logic stays testable on the JVM.
// Only summaries and per-cell voiced time are kept — never audio.

/** One continuous stretch of capturing. */
data class PracticeSession(
    val start: Instant,
    /** Wall-clock seconds capturing. */
    val duration: Double,
    val voicedSeconds: Double,
    val medianHz: Double,
    val p10Hz: Double,
    val p90Hz: Double,
    /** Median resonance score (0–100); null without reliable readings. */
    val medianResonance: Double?,
    val quality: QualitySummary? = null,
    val noisyShare: Double? = null,
    val cells: List<GridCell> = emptyList(),
    val id: Long = 0,
)

/** Voiced seconds in one grid cell (one semitone × one 5-point resonance band). */
data class GridCell(val pitchMidi: Int, val resonanceBin: Int, val seconds: Double) {
    companion object {
        const val RESONANCE_BIN_WIDTH = 5.0
        fun bin(score: Double): Int = (score.coerceIn(0.0, 99.999) / RESONANCE_BIN_WIDTH).toInt()
    }
}

/** Medians of the voice-quality measures over a session or the baseline. */
data class QualitySummary(
    val hnr: Double,
    val cpp: Double,
    val jitter: Double?,
    val shimmer: Double?,
    val level: Double,
    val weight: Double,
) {
    companion object {
        fun of(samples: List<VoiceQuality>): QualitySummary? {
            val m = VoiceQuality.median(samples) ?: return null
            return QualitySummary(m.hnr, m.cpp, m.jitter, m.shimmer, m.level,
                Stats.median(samples.map { it.weight }) ?: m.weight)
        }
    }
}

/** The user's reference voice from a 30-second reading. */
data class Baseline(
    val date: Instant,
    val medianHz: Double,
    val medianResonance: Double?,
    val quality: QualitySummary? = null,
)

/** One finished exercise (S14). */
data class ExerciseResult(
    val date: Instant,
    val exerciseId: String,
    val stage: Int,
    /** Completed with real voiced time and no strain alert. */
    val passed: Boolean,
    val voicedSeconds: Double,
    val strainAlerts: Int,
    val targetMidi: Int? = null,
    val medianHz: Double? = null,
    /** p10–p90 pitch spread in semitones (intonation, PLAN §5). */
    val semitoneRange: Double? = null,
    val medianResonance: Double? = null,
    val id: Long = 0,
)

/** Time window for History, the heat map and the grid overlay. */
enum class HistoryPeriod(val label: String) {
    TODAY("Today"), WEEK("Week"), MONTH("Month"), ALL("All");

    /** Earliest session start included; null = no limit. */
    fun cutoff(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant? = when (this) {
        TODAY -> LocalDate.ofInstant(now, zone).atStartOfDay(zone).toInstant()
        WEEK -> now.atZone(zone).minusDays(7).toInstant()
        MONTH -> now.atZone(zone).minusDays(30).toInstant()
        ALL -> null
    }
}

data class HeatCell(val midi: Int, val bin: Int, val seconds: Double)
data class HistoryPoint(val date: Instant, val score: Double, val midi: Double)

object HeatmapBinner {
    fun sessions(all: List<PracticeSession>, period: HistoryPeriod, now: Instant = Instant.now(),
                 zone: ZoneId = ZoneId.systemDefault()): List<PracticeSession> {
        val cutoff = period.cutoff(now, zone) ?: return all
        return all.filter { it.start >= cutoff }
    }

    fun cells(sessions: List<PracticeSession>): List<HeatCell> {
        val totals = HashMap<Int, Double>()
        for (s in sessions) for (c in s.cells) totals.merge(c.pitchMidi * 100 + c.resonanceBin, c.seconds, Double::plus)
        return totals.map { (k, v) -> HeatCell(k / 100, k % 100, v) }
    }

    fun points(sessions: List<PracticeSession>): List<HistoryPoint> =
        sessions.sortedBy { it.start }.mapNotNull { s ->
            val r = s.medianResonance ?: return@mapNotNull null
            if (s.medianHz <= 0) null else HistoryPoint(s.start, r, Note.midiValue(s.medianHz))
        }
}

/** Which exercises are done today, as one stored string "yyyy-MM-dd|id,id". */
object ExerciseLog {
    const val KEY = "exercises.doneToday"

    fun done(stored: String, date: LocalDate): Set<String> {
        val parts = stored.split("|", limit = 2)
        if (parts.size != 2 || parts[0] != date.toString()) return emptySet()
        return parts[1].split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun marking(id: String, stored: String, date: LocalDate): String =
        date.toString() + "|" + (done(stored, date) + id).sorted().joinToString(",")
}
