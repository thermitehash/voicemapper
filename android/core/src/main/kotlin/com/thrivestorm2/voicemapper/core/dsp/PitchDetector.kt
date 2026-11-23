package com.thrivestorm2.voicemapper.core.dsp

import kotlin.math.max
import kotlin.math.sqrt

/**
 * YIN pitch detector (de Cheveigné & Kawahara 2002). Port of iOS
 * `DSP/PitchDetector.swift`; keep the two in step. Works on one frame of mono
 * samples; see [frameLength]. Not thread-safe: one instance per stream.
 */
class PitchDetector(val sampleRate: Double) {
    data class Result(val frequency: Double, /** 0–1: how clean the periodicity is. */ val confidence: Double)

    companion object {
        const val MIN_FREQUENCY = 60.0
        const val MAX_FREQUENCY = 1000.0
        /** Samples compared at each lag. */
        const val WINDOW_LENGTH = 1024
        private const val DIP_THRESHOLD = 0.15f
        private const val VOICED_LIMIT = 0.4f
        private const val OCTAVE_DIP_LIMIT = 0.25f
        // Harmonic guard (S13) and its stronger second rule (S15); see the Swift file.
        private const val HARMONIC_RATIO = 0.5f
        private const val HARMONIC_GAP = 0.07f
        private const val HARMONIC_STRONG_RATIO = 0.1f
        private const val HARMONIC_FLOOR = 0.03f
        private const val HARMONIC_STRONG_MIN_HZ = 300.0
        private const val SILENCE_RMS = 0.003f
    }

    private val tauMin = max(2, (sampleRate / MAX_FREQUENCY).toInt())
    private val tauMax = (sampleRate / MIN_FREQUENCY).toInt()
    private val diff = FloatArray(tauMax + 2)
    private val cmnd = FloatArray(tauMax + 2)
    private val cumulative = DoubleArray(WINDOW_LENGTH + tauMax + 3)

    /** Samples [detect] needs: the window plus the longest lag (and one more for the dip search). */
    val frameLength: Int get() = WINDOW_LENGTH + tauMax + 2

    /** Returns null for silence or unvoiced sound. Reads `x[offset until offset + frameLength]`. */
    fun detect(x: FloatArray, offset: Int = 0, count: Int = x.size - offset): Result? {
        val window = WINDOW_LENGTH
        if (count < frameLength) return null

        var meanSquare = 0f
        for (i in 0 until window) { val v = x[offset + i]; meanSquare += v * v }
        meanSquare /= window
        if (sqrt(meanSquare) < SILENCE_RMS) return null

        // Prefix sums of x², so the energy of any shifted window is O(1).
        cumulative[0] = 0.0
        for (i in 0 until frameLength) {
            val v = x[offset + i].toDouble()
            cumulative[i + 1] = cumulative[i] + v * v
        }
        val energy0 = cumulative[window].toFloat()

        // d(τ) = Σ (x[j] − x[j+τ])² = e(0) + e(τ) − 2·r(τ)
        for (tau in 1..tauMax + 1) {
            var r = 0f
            for (j in 0 until window) r += x[offset + j] * x[offset + j + tau]
            val shifted = (cumulative[tau + window] - cumulative[tau]).toFloat()
            diff[tau] = max(0f, energy0 + shifted - 2 * r)
        }

        // Cumulative mean normalised difference.
        cmnd[0] = 1f
        var running = 0f
        for (tau in 1..tauMax + 1) {
            running += diff[tau]
            cmnd[tau] = if (running > 0) diff[tau] * tau / running else 1f
        }

        var best = -1
        var clear = true
        var tau = tauMin
        while (tau <= tauMax) {
            if (cmnd[tau] < DIP_THRESHOLD) {
                while (tau + 1 <= tauMax && cmnd[tau + 1] < cmnd[tau]) tau++
                best = tau
                break
            }
            tau++
        }
        if (best < 0) {
            // No clear dip: fall back to the deepest one, if voiced at all.
            best = argMin(tauMin, tauMax)
            if (cmnd[best] >= VOICED_LIMIT) return null
            clear = false
        }

        // Harmonic guard: a much deeper multiple-lag dip is the real period.
        if (clear && cmnd[best] > HARMONIC_FLOOR) {
            val first = best
            for (k in 2..6) {
                val lag = first * k
                if (lag + 2 > tauMax) break
                val alt = argMin(lag - 2, lag + 2)
                val gapRule = cmnd[alt] < cmnd[first] * HARMONIC_RATIO && cmnd[first] - cmnd[alt] > HARMONIC_GAP
                val strongRule = cmnd[alt] < cmnd[first] * HARMONIC_STRONG_RATIO &&
                    sampleRate / alt >= HARMONIC_STRONG_MIN_HZ
                if (gapRule || strongRule) { best = alt; break }
            }
        }

        // Octave-down guard, only for frames without a clear dip.
        val half = best / 2
        if (!clear && half >= tauMin) {
            val lo = max(tauMin, half - 2)
            val hi = minOf(tauMax, half + 2)
            val alt = argMin(lo, hi)
            if (cmnd[alt] < OCTAVE_DIP_LIMIT) best = alt
        }

        // Parabolic interpolation around the dip for sub-sample accuracy.
        var lag = best.toDouble()
        if (best > tauMin && best < tauMax) {
            val a = cmnd[best - 1].toDouble()
            val b = cmnd[best].toDouble()
            val c = cmnd[best + 1].toDouble()
            val denominator = a - 2 * b + c
            if (denominator > 0) lag += (a - c) / (2 * denominator)
        }
        return Result(sampleRate / lag, max(0f, 1 - cmnd[best]).toDouble())
    }

    /** First index of the smallest value in cmnd[lo..hi] (matches Swift's `min(by:)`). */
    private fun argMin(lo: Int, hi: Int): Int {
        var best = lo
        for (i in lo + 1..hi) if (cmnd[i] < cmnd[best]) best = i
        return best
    }
}
