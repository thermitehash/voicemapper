package com.thrivestorm2.voicemapper.core.analysis

import com.thrivestorm2.voicemapper.core.dsp.FormantAnalyzer

/**
 * Formants → formant spacing ΔF → 0–100 resonance score. Port of iOS
 * `Analysis/ResonanceScorer.swift` (uniform-tube fit, Reby & McComb 2003).
 */
object ResonanceScorer {
    const val SPEED_OF_SOUND = 35_000.0
    const val DARK_SPACING = 900.0
    const val BRIGHT_SPACING = 1300.0

    private val ranges = listOf(
        200.0..1200.0,   // F1
        800.0..3300.0,   // F2
        1400.0..4300.0,  // F3
        2600.0..5300.0,  // F4
    )
    private const val MAX_LOW_BANDWIDTH = 400.0

    /** ΔF in Hz, or null unless F1–F3 are all plausible. */
    fun spacing(formants: List<FormantAnalyzer.Formant>): Double? {
        val used = assigned(formants)
        if (used.size < 3) return null
        var numerator = 0.0
        var denominator = 0.0
        used.forEachIndexed { i, f ->
            val k = (2 * i + 1) / 2.0
            numerator += k * f
            denominator += k * k
        }
        return numerator / denominator
    }

    /** The peaks taken as F1, F2, F3 (and F4), in order. */
    fun assigned(formants: List<FormantAnalyzer.Formant>): List<Double> {
        if (formants.size < 3) return emptyList()
        val used = mutableListOf<Double>()
        for (formant in formants) {
            val slot = used.size
            if (slot >= ranges.size) break
            if (slot < 2 && formant.bandwidth > MAX_LOW_BANDWIDTH) continue
            val f = formant.frequency
            if (f < ranges[slot].start) continue
            val last = used.lastOrNull()
            if (last != null && f - last < 150) continue
            if (f > ranges[slot].endInclusive) break
            used.add(f)
        }
        return used
    }

    /** 0 (darkest) … 100 (brightest). */
    fun score(spacing: Double): Double {
        val t = (spacing - DARK_SPACING) / (BRIGHT_SPACING - DARK_SPACING)
        return (t * 100).coerceIn(0.0, 100.0)
    }

    /** Apparent vocal tract length in cm. */
    fun tractLength(spacing: Double): Double = SPEED_OF_SOUND / (2 * spacing)
}
