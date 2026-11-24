package com.thrivestorm2.voicemapper.core

import com.thrivestorm2.voicemapper.core.analysis.ResonanceScorer
import com.thrivestorm2.voicemapper.core.dsp.PitchDetector
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
            val s = formantStats(synthesize(v.f0, v.formants, v.bandwidths), f0 = v.f0)
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

        // Same mouth shape, different pitch → same score (iOS "pitch independence", S18).
        c.group("pitch independence: fixed formants, 110–240 Hz")
        val fixedSets = listOf("neutral" to neutral) + vowels.filter { "/u/" !in it.label }.map { it.label to it.formants }
        for ((name, formants) in fixedSets) {
            val scores = (110..240 step 10).mapNotNull { f0 ->
                formantStats(synthesize(f0.toDouble(), formants, bw, seconds = 0.5), f0 = f0.toDouble()).spacing?.let { ResonanceScorer.score(it) }
            }
            if (scores.size != 14) { c.expect(false, "pitch independence $name: missing readings"); continue }
            val span = scores.max() - scores.min()
            c.expect(span <= 6.0) { "pitch independence $name: score spans ${fmt(span, 1)} points across 110–240 Hz" }
        }

        rhoticTests(c, bw)
        referenceTests(c)
        c.finish()
    }

    /** Mean displayed score of ResonanceTracker over a stream, pitch from PitchDetector. */
    private fun trackerMeanScore(x: FloatArray): Double? {
        val detector = PitchDetector(RATE)
        var sum = 0.0
        var n = 0
        val tracker = ResonanceTracker(RATE) { r -> if (r != null) { sum += r.score; n++ } }
        var i = 0
        while (i + 512 <= x.size) {
            val pitch = if (i + detector.frameLength <= x.size) detector.detect(x, i, detector.frameLength)?.frequency else null
            tracker.process(x.copyOfRange(i, i + 512), pitch = pitch)
            i += 512
        }
        return if (n > 0) sum / n else null
    }

    /** iOS DSPCheck `rhoticTests`: r-coloured vowels mustn't darken speech (S18). */
    private fun rhoticTests(c: Checks, bw: List<Double>) {
        c.group("rhotic gate: plain speech vs the same speech with r-coloured vowels")
        val means = mapOf(
            "m" to mapOf("iy" to listOf(139.0, 343.0, 2323.0, 3001.0, 3658.0), "eh" to listOf(127.0, 588.0, 1803.0, 2604.0, 3699.0),
                "ae" to listOf(126.0, 591.0, 1930.0, 2595.0, 3642.0), "ah" to listOf(127.0, 756.0, 1309.0, 2535.0, 3684.0),
                "uh" to listOf(129.0, 621.0, 1181.0, 2548.0, 3564.0), "ih" to listOf(136.0, 429.0, 2034.0, 2687.0, 3628.0),
                "er" to listOf(131.0, 475.0, 1379.0, 1711.0, 3335.0)),
            "w" to mapOf("iy" to listOf(227.0, 437.0, 2761.0, 3373.0, 4353.0), "eh" to listOf(213.0, 727.0, 2063.0, 2953.0, 4312.0),
                "ae" to listOf(214.0, 676.0, 2335.0, 2973.0, 4315.0), "ah" to listOf(212.0, 921.0, 1526.0, 2832.0, 4291.0),
                "uh" to listOf(218.0, 760.0, 1416.0, 2901.0, 4133.0), "ih" to listOf(225.0, 484.0, 2369.0, 3057.0, 4333.0),
                "er" to listOf(218.0, 524.0, 1588.0, 1930.0, 3914.0)),
        )
        val order = listOf("iy", "eh", "ae", "ah", "uh", "ih", "iy", "ae", "ah")
        for ((sex, m) in means) {
            fun segment(v: String): FloatArray {
                val f = m.getValue(v)
                return synthesize(f[0], f.subList(1, 5) + (if (sex == "m") 4500.0 else 5000.0), bw, seconds = 0.4)
            }
            var plain = FloatArray(0)
            var mixed = FloatArray(0)
            order.forEachIndexed { i, v ->
                val seg = segment(v)
                plain += seg; mixed += seg
                if (i % 2 == 1) mixed += segment("er")
            }
            val p = trackerMeanScore(plain)
            val r = trackerMeanScore(mixed)
            if (p == null || r == null) { c.expect(false, "rhotic $sex: no reading"); continue }
            println("  $sex: plain ${fmt(p, 1)}, with r ${fmt(r, 1)}")
            c.expect(abs(r - p) <= 3) { "rhotic $sex: r-coloured speech reads ${fmt(r - p, 1)} points off plain speech" }
        }
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
            val f = formantStats(x, rate, f0)
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
