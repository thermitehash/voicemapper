package com.thrivestorm2.voicemapper.core

import com.thrivestorm2.voicemapper.core.analysis.ResonanceScorer
import com.thrivestorm2.voicemapper.core.dsp.ResonanceTracker
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Port of `Tools/DSPCheck/run.sh --test` (S15) with the same tolerances
 * (docs/ACCURACY.md). Runs the Kotlin DSP on synthetic vowels with known
 * values, plus the real-voice reference set when the WAVs exist.
 */
class DspCheckTest {
    private object Tolerance {
        const val PITCH_CENTS = 5.0
        const val GROSS_SHARE = 0.05
        const val FORMANT_SHARE = 0.10
        const val SPACING_SHARE = 0.03
        const val SCORE_POINTS = 5.0
        const val REAL_PITCH_CENTS = 20.0
    }

    private val bw = listOf(80.0, 100.0, 150.0, 200.0, 250.0)
    private val neutral = listOf(500.0, 1500.0, 2500.0, 3500.0, 4500.0)

    @Test
    fun accuracySuite() {
        val c = Checks("DSP check")

        c.group("pitch: 65–400 Hz in 5 Hz steps × 9 vowels")
        val sets = listOf("neutral" to neutral) + vowels.map { it.label to it.formants }
        for ((name, formants) in sets) {
            var f0 = 65.0
            while (f0 <= 400.0) {
                val s = pitchStats(synthesize(f0, formants, bw, seconds = 0.5), f0)
                val m = s.median
                if (m == null) {
                    c.expect(false, "pitch $name ${f0.toInt()} Hz: no reading")
                } else {
                    val e = cents(m, f0)
                    c.expect(abs(e) <= Tolerance.PITCH_CENTS) { "pitch $name ${f0.toInt()} Hz: median off by ${fmt(e, 1)} cents" }
                    c.expect(s.gross <= Tolerance.GROSS_SHARE) {
                        "pitch $name ${f0.toInt()} Hz: ${fmt(s.gross * 100)}% of frames >50 cents off (octave errors)"
                    }
                }
                f0 += 5
            }
        }

        c.group("pitch: mild diplophonia at speaking pitches")
        for (f0 in listOf(120.0, 160.0, 200.0, 260.0)) for (alt in listOf(0.1, 0.2)) {
            val s = pitchStats(synthesize(f0, neutral, bw, alternate = alt), f0)
            c.expect(s.gross <= 0.1) { "diplophonia ${(alt * 100).toInt()}% at ${f0.toInt()} Hz: ${fmt(s.gross * 100)}% of frames >50 cents off" }
        }

        c.group("formants: ${vowels.size} vowels")
        for (v in vowels) {
            val s = formantStats(synthesize(v.f0, v.formants, v.bandwidths))
            for (i in 0 until 3) {
                val m = s.formants[i]
                if (m == null) { c.expect(false, "${v.label}: F${i + 1} missing"); continue }
                val err = abs(m - v.formants[i]) / v.formants[i]
                c.expect(err <= Tolerance.FORMANT_SHARE) { "${v.label}: F${i + 1} ${fmt(m)} vs ${fmt(v.formants[i])} (${fmt(err * 100, 1)}%)" }
            }
            if ("/u/" in v.label) {
                println("  ${v.label}: ΔF and score skipped (known limit: weak F4 on back rounded vowels)")
                continue
            }
            val dfTrue = trueSpacing(v.formants)
            val df = s.spacing
            if (df == null) { c.expect(false, "${v.label}: no ΔF"); continue }
            val dfErr = abs(df - dfTrue) / dfTrue
            c.expect(dfErr <= Tolerance.SPACING_SHARE) { "${v.label}: ΔF ${fmt(df)} vs ${fmt(dfTrue)} (${fmt(dfErr * 100, 1)}%)" }
            val scoreErr = abs(ResonanceScorer.score(df) - ResonanceScorer.score(dfTrue))
            c.expect(scoreErr <= Tolerance.SCORE_POINTS) { "${v.label}: score off by ${fmt(scoreErr, 1)} points" }
        }

        c.group("resonance gate: tracker above and below 350 Hz")
        for (f0 in listOf(300.0, 340.0, 360.0, 400.0, 450.0)) {
            val x = synthesize(f0, vowels[4].formants, bw)
            var readings = 0
            val tracker = ResonanceTracker(RATE) { if (it != null) readings++ }
            var i = 0
            while (i + 512 <= x.size) {
                tracker.process(x.copyOfRange(i, i + 512), pitch = f0)
                i += 512
            }
            if (f0 > 350) c.expect(readings == 0) { "resonance gate: $readings readings at ${f0.toInt()} Hz (should be none above 350)" }
            else c.expect(readings > 0) { "resonance gate: no readings at ${f0.toInt()} Hz (should read below 350)" }
        }

        referenceTests(c)
        c.finish()
    }

    /** Real-voice set: Tools/DSPCheck/reference/reference.json + WAVs (git-ignored). */
    private fun referenceTests(c: Checks) {
        c.group("real-voice reference set")
        val folder = File(System.getProperty("voicemapper.reference") ?: return)
        val json = File(folder, "reference.json").takeIf { it.exists() }?.readText()
        if (json == null) { println("  no reference.json, skipped"); return }
        val entries = Regex("\\{[^}]*\\}").findAll(json).map { m ->
            fun num(key: String) = Regex("\"$key\"\\s*:\\s*(-?[0-9.]+)").find(m.value)?.groupValues?.get(1)?.toDoubleOrNull()
            val file = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(m.value)?.groupValues?.get(1)
            Triple(file, num("f0"), listOf(num("f1"), num("f2"), num("f3")))
        }.toList()
        var ran = 0
        for ((file, f0, fs) in entries) {
            if (file == null || f0 == null) continue
            val (x, rate) = readWav(File(folder, file)) ?: continue
            ran++
            val p = pitchStats(x, null, rate)
            val m = p.median
            if (m != null) c.expect(abs(cents(m, f0)) <= Tolerance.REAL_PITCH_CENTS) { "$file: F0 ${fmt(m, 1)} vs Praat ${fmt(f0, 1)} (${fmt(cents(m, f0), 1)} cents)" }
            else c.expect(false, "$file: no pitch reading")
            val f = formantStats(x, rate)
            fs.forEachIndexed { i, truth ->
                if (truth == null) return@forEachIndexed
                val v = f.formants[i]
                if (v == null) { c.expect(false, "$file: F${i + 1} missing"); return@forEachIndexed }
                val err = abs(v - truth) / truth
                c.expect(err <= Tolerance.FORMANT_SHARE) { "$file: F${i + 1} ${fmt(v)} vs Praat ${fmt(truth)} (${fmt(err * 100, 1)}%)" }
            }
        }
        println("  $ran of ${entries.size} recordings found")
    }
}
