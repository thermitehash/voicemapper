package com.thrivestorm2.voicemapper.core.analysis

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// Ports of iOS Analysis/NoteMapper, GoalRange, ResonanceGoal, RangeTable,
// VoicePerception and GoalPreset. Keep the numbers in step with the Swift files.

object Stats {
    /** Median (mean of the middle two for even counts), as in iOS `median(_:)`. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Nearest-rank percentile, as in iOS `percentile(_:_:)`. */
    fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return s[min(s.size - 1, ((s.size - 1) * p).roundToInt())]
    }
}

/** A pitch as a named equal-tempered note (A4 = 440 Hz). */
data class Note(val midi: Int) {
    companion object {
        private val names = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
        fun fromFrequency(frequency: Double) = Note(swiftRound(midiValue(frequency)))
        /** Fractional MIDI number: 69 + 12·log2(f/440). */
        fun midiValue(frequency: Double) = 69 + 12 * log2(frequency / 440)
    }

    /** e.g. "G4". Swift's integer division truncates toward zero, as Kotlin's does. */
    val name: String get() = names[((midi % 12) + 12) % 12] + (midi / 12 - 1)
    val frequency: Double get() = 440 * 2.0.pow((midi - 69) / 12.0)
}

/** Swift's `.rounded()` (half away from zero); Kotlin's roundToInt rounds half up. */
fun swiftRound(v: Double): Int = (if (v >= 0) kotlin.math.floor(v + 0.5) else -kotlin.math.floor(-v + 0.5)).toInt()

/** Pitch goal as a span of MIDI notes (inclusive). */
class GoalRange private constructor(val low: Int, val high: Int) {
    companion object {
        fun of(low: Int, high: Int): GoalRange? = if (low > 0 && high >= low) GoalRange(low, high) else null
    }

    enum class Direction { HIGHER, LOWER }

    fun contains(midi: Int) = midi in low..high

    /** 0–1: 1 inside the range, falling linearly to 0 at 6 semitones outside it. */
    fun closeness(frequency: Double): Double {
        val m = Note.midiValue(frequency)
        val distance = maxOf(low - m, m - high, 0.0)
        return max(0.0, 1 - distance / 6)
    }

    fun direction(frequency: Double): Direction? {
        val m = Note.midiValue(frequency)
        if (m < low - 0.5) return Direction.HIGHER
        if (m > high + 0.5) return Direction.LOWER
        return null
    }

    override fun equals(other: Any?) = other is GoalRange && other.low == low && other.high == high
    override fun hashCode() = low * 1000 + high
}

/** Resonance goal as scores on the 0–100 scale. */
class ResonanceGoal private constructor(val low: Double, val high: Double) {
    companion object {
        const val MINIMUM_WIDTH = 4.0
        fun of(low: Double, high: Double): ResonanceGoal? =
            if (high - low >= MINIMUM_WIDTH) ResonanceGoal(max(0.0, low), min(100.0, high)) else null
    }

    enum class Direction { BRIGHTER, DARKER }

    fun direction(score: Double): Direction? = when {
        score < low -> Direction.BRIGHTER
        score > high -> Direction.DARKER
        else -> null
    }

    override fun equals(other: Any?) = other is ResonanceGoal && other.low == low && other.high == high
    override fun hashCode() = (low * 1000 + high).hashCode()
}

data class Rgb(val r: Double, val g: Double, val b: Double)

/** How a speaking voice at a given pitch is typically perceived (Gelfer & Mikos 2005; Hillenbrand & Clark 2009). */
enum class PerceivedGender(val letter: String, val meaning: String, val typicalFrequency: Double, val typicalResonance: Double) {
    MALE("M", "usually read male", 120.0, 25.0),
    AMBIGUOUS("A", "ambiguous / androgynous", 160.0, 50.0),
    FEMALE("F", "usually read female", 210.0, 72.0);

    fun rgb(colourBlind: Boolean): Rgb = if (colourBlind) when (this) {
        // Okabe & Ito (2008) blue / grey / orange.
        MALE -> Rgb(0.0, 0.447, 0.698)
        AMBIGUOUS -> Rgb(0.6, 0.6, 0.6)
        FEMALE -> Rgb(0.902, 0.624, 0.0)
    } else when (this) {
        MALE -> Rgb(0.0, 0.48, 1.0)
        AMBIGUOUS -> Rgb(0.69, 0.32, 0.87)
        FEMALE -> Rgb(1.0, 0.45, 0.75)
    }

    /** 0.15–1: how typical a resonance score is within the zone. */
    fun resonanceCommonness(score: Double): Double {
        val d = (score - typicalResonance) / 18
        return max(0.15, exp(-d * d / 2))
    }

    /** 0.2–1: how typical a pitch is within the zone. */
    fun commonness(frequency: Double): Double {
        val st = 12 * log2(frequency / typicalFrequency)
        return max(0.2, exp(-st * st / (2 * 3 * 3)))
    }

    companion object {
        fun of(frequency: Double) = when {
            frequency < 145 -> MALE
            frequency < 175 -> AMBIGUOUS
            else -> FEMALE
        }
    }
}

enum class VoiceType(val label: String) {
    BASS("Bass"), BARITONE("Bari"), TENOR("Tenor"), ALTO("Alto"), MEZZO("Mezzo"), SOPRANO("Sop");

    companion object {
        fun of(midi: Int) = when {
            midi < 48 -> BASS
            midi < 52 -> BARITONE
            midi < 55 -> TENOR
            midi < 65 -> ALTO
            midi < 69 -> MEZZO
            else -> SOPRANO
        }
    }
}

data class RangeRow(val note: Note) {
    val id get() = note.midi
    val frequency get() = note.frequency
    val voiceType get() = VoiceType.of(note.midi)
    val perceived get() = PerceivedGender.of(note.frequency)
    val commonness get() = perceived.commonness(note.frequency)
}

object RangeTable {
    /** Natural notes E2–G4, highest first. */
    val rows: List<RangeRow> = (40..67).reversed().map { Note(it) }.filter { "♯" !in it.name }.map { RangeRow(it) }

    fun rowId(note: Note): Int = rows.minBy { abs(it.id - note.midi) }.id
}

/** Smooth male / ambiguous / female blend of a pitch + resonance position. */
object VoicePerception {
    data class Blend(val male: Double, val ambiguous: Double, val female: Double)

    const val CENTER_FREQUENCY = 165.0
    private const val PITCH_SLOPE = 0.8
    private const val RESONANCE_SLOPE = 0.06
    private const val AMBIGUOUS_HALF_WIDTH = 1.1

    fun blend(frequency: Double, score: Double): Blend {
        val st = 12 * log2(frequency / CENTER_FREQUENCY)
        val z = PITCH_SLOPE * st + RESONANCE_SLOPE * (score - 50)
        val female = sigmoid(z - AMBIGUOUS_HALF_WIDTH)
        val male = 1 - sigmoid(z + AMBIGUOUS_HALF_WIDTH)
        return Blend(male, max(0.0, 1 - male - female), female)
    }

    fun color(b: Blend, colourBlind: Boolean): Rgb {
        val m = PerceivedGender.MALE.rgb(colourBlind)
        val a = PerceivedGender.AMBIGUOUS.rgb(colourBlind)
        val f = PerceivedGender.FEMALE.rgb(colourBlind)
        return Rgb(
            m.r * b.male + a.r * b.ambiguous + f.r * b.female,
            m.g * b.male + a.g * b.ambiguous + f.g * b.female,
            m.b * b.male + a.b * b.ambiguous + f.b * b.female,
        )
    }

    fun commonness(frequency: Double, score: Double, b: Blend): Double =
        listOf(PerceivedGender.MALE to b.male, PerceivedGender.AMBIGUOUS to b.ambiguous, PerceivedGender.FEMALE to b.female)
            .sumOf { (zone, w) -> w * zone.commonness(frequency) * zone.resonanceCommonness(score) }

    /** Time-weighted blend over a session's grid cells; null without data. */
    fun blend(cells: List<GridCell>): Blend? {
        var male = 0.0; var amb = 0.0; var female = 0.0; var total = 0.0
        for (c in cells) {
            if (c.seconds <= 0) continue
            val hz = 440 * 2.0.pow((c.pitchMidi - 69) / 12.0)
            val score = (c.resonanceBin + 0.5) * GridCell.RESONANCE_BIN_WIDTH
            val b = blend(hz, score)
            male += b.male * c.seconds; amb += b.ambiguous * c.seconds; female += b.female * c.seconds
            total += c.seconds
        }
        if (total <= 0) return null
        return Blend(male / total, amb / total, female / total)
    }

    fun description(b: Blend): String {
        fun pct(v: Double) = swiftRound(v * 100)
        val lead = when {
            b.female >= 0.6 -> "Mostly reads feminine"
            b.male >= 0.6 -> "Mostly reads masculine"
            b.ambiguous >= max(b.male, b.female) -> "Mostly reads androgynous"
            else -> "Mixed"
        }
        return "$lead: ${pct(b.female)}% feminine, ${pct(b.ambiguous)}% androgynous, ${pct(b.male)}% masculine"
    }

    private fun sigmoid(z: Double) = 1 / (1 + exp(-z))
}

/** Goal presets (PLAN §6). Alphabetical, so the order doesn't suggest a ranking or a default. */
enum class GoalPreset(val title: String, val zone: PerceivedGender) {
    ANDROGYNOUS("Androgynous", PerceivedGender.AMBIGUOUS),
    FEMININE("Feminine", PerceivedGender.FEMALE),
    MASCULINE("Masculine", PerceivedGender.MALE);

    /** ±2 semitones around the zone's typical pitch, trimmed to stay inside the zone. */
    val range: GoalRange get() {
        val center = Note.fromFrequency(zone.typicalFrequency).midi
        val notes = (center - 2..center + 2).filter { PerceivedGender.of(Note(it).frequency) == zone }
        return GoalRange.of(notes.firstOrNull() ?: center, notes.lastOrNull() ?: center)!!
    }

    /** ±9 points around the zone's typical resonance. */
    val resonance: ResonanceGoal get() = ResonanceGoal.of(zone.typicalResonance - 9, zone.typicalResonance + 9)!!

    val summary: String get() =
        "${Note(range.low).name}–${Note(range.high).name} · resonance ${resonance.low.toInt()}–${resonance.high.toInt()}"

    companion object {
        fun matching(low: Int, high: Int, resonanceLow: Double, resonanceHigh: Double): GoalPreset? =
            entries.firstOrNull {
                it.range.low == low && it.range.high == high &&
                    it.resonance.low == resonanceLow && it.resonance.high == resonanceHigh
            }

        fun label(low: Int, high: Int, resonanceLow: Double, resonanceHigh: Double): String {
            matching(low, high, resonanceLow, resonanceHigh)?.let { return it.title }
            val anySet = GoalRange.of(low, high) != null || ResonanceGoal.of(resonanceLow, resonanceHigh) != null
            return if (anySet) "Custom" else "Not set"
        }
    }
}
