package com.thrivestorm2.voicemapper.core.dsp

import com.thrivestorm2.voicemapper.core.analysis.Stats
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Voice-quality measures for one voiced frame (PLAN §5). Port of iOS
 * `DSP/VoiceQualityAnalyzer.swift`. Heuristics for comparing the user with
 * their own baseline, not clinical values.
 */
data class VoiceQuality(
    /** H1 − H2, dB. Higher = lighter/breathier source. */
    val h1h2: Double,
    /** Alpha ratio (1–5 kHz minus 50 Hz–1 kHz), dB. */
    val alphaRatio: Double,
    /** Cepstral peak prominence, dB. Higher = clearer. */
    val cpp: Double,
    /** Harmonics-to-noise ratio, dB. */
    val hnr: Double,
    /** Local jitter, %. */
    val jitter: Double?,
    /** Local shimmer, %. */
    val shimmer: Double?,
    /** RMS level, dBFS. */
    val level: Double,
) {
    /** 0 (lighter) … 1 (heavier). Shown neutrally. */
    val weight: Double get() = weight(h1h2, alphaRatio)

    companion object {
        fun weight(h1h2: Double, alphaRatio: Double): Double {
            fun unit(v: Double) = v.coerceIn(0.0, 1.0)
            return 0.5 * unit((12 - h1h2) / 16) + 0.5 * unit((alphaRatio + 28) / 20)
        }

        /** Field-by-field median; null fields are skipped. */
        fun median(values: Collection<VoiceQuality>): VoiceQuality? {
            val h1h2 = Stats.median(values.map { it.h1h2 }) ?: return null
            val alpha = Stats.median(values.map { it.alphaRatio }) ?: return null
            val cpp = Stats.median(values.map { it.cpp }) ?: return null
            val hnr = Stats.median(values.map { it.hnr }) ?: return null
            val level = Stats.median(values.map { it.level }) ?: return null
            return VoiceQuality(h1h2, alpha, cpp, hnr,
                Stats.median(values.mapNotNull { it.jitter }),
                Stats.median(values.mapNotNull { it.shimmer }), level)
        }
    }
}

class VoiceQualityAnalyzer(val sampleRate: Double) {
    companion object {
        private const val SILENCE_LEVEL = -55.0

        /** HNR from the normalised autocorrelation peak near the period (Boersma 1993). */
        fun hnr(x: FloatArray, offset: Int, n: Int, period: Double): Double? {
            val t0 = period.roundToInt()
            val radius = max(2, (0.03 * period).toInt())
            val length = n - t0 - radius - 2
            if (t0 - radius - 1 < 2 || length <= 2 * t0) return null
            fun r(lag: Int): Double {
                var xy = 0f; var xx = 0f; var yy = 0f
                for (i in 0 until length) {
                    val a = x[offset + i]; val b = x[offset + i + lag]
                    xy += a * b; xx += a * a; yy += b * b
                }
                val d = xx.toDouble() * yy.toDouble()
                return if (d > 0) xy / sqrt(d) else 0.0
            }
            var best = t0
            var bestR = -1.0
            for (lag in (t0 - radius)..(t0 + radius)) {
                val v = r(lag)
                if (v > bestR) { bestR = v; best = lag }
            }
            val a = r(best - 1)
            val c = r(best + 1)
            val curve = a - 2 * bestR + c
            var peak = bestR
            if (curve < 0) {
                val shift = 0.5 * (a - c) / curve
                peak = bestR - 0.25 * (a - c) * shift
            }
            peak = peak.coerceIn(1e-4, 0.9999)
            return 10 * log10(peak / (1 - peak))
        }

        /** Local jitter and shimmer (%) by picking one waveform peak per cycle. */
        fun perturbation(x: FloatArray, offset: Int, n: Int, period: Double): Pair<Double?, Double?> {
            if (period < 8) return null to null
            var top = Float.NEGATIVE_INFINITY
            var bottom = Float.POSITIVE_INFINITY
            for (i in 0 until n) { val v = x[offset + i]; if (v > top) top = v; if (v < bottom) bottom = v }
            val sign = if (top >= -bottom) 1f else -1f
            fun at(i: Int) = sign * x[offset + i]
            fun peak(lo: Int, hi: Int): Int {
                var best = lo
                for (i in lo..hi) if (at(i) > at(best)) best = i
                return best
            }
            val times = mutableListOf<Double>()
            val amps = mutableListOf<Double>()
            var index = peak(1, min(n - 2, period.toInt()))
            while (true) {
                val a = at(index - 1).toDouble(); val b = at(index).toDouble(); val c = at(index + 1).toDouble()
                val curve = a - 2 * b + c
                val shift = if (curve < 0) 0.5 * (a - c) / curve else 0.0
                times.add(index + shift)
                amps.add(b - 0.25 * (a - c) * shift)
                val lo = index + (0.75 * period).toInt()
                val hi = index + (1.25 * period).toInt()
                if (hi > n - 2) break
                index = peak(lo, hi)
            }
            if (times.size < 5) return null to null
            val periods = times.zipWithNext { p, q -> q - p }
            if (!periods.all { it > 0.8 * period && it < 1.2 * period } || !amps.all { it > 0 }) return null to null
            fun local(v: List<Double>): Double {
                val diffs = v.zipWithNext { p, q -> abs(q - p) }
                return 100 * (diffs.sum() / diffs.size) / (v.sum() / v.size)
            }
            return local(periods) to local(amps)
        }
    }

    /** Samples [analyze] reads: the power of two covering ≥ 80 ms. */
    val frameLength: Int = run {
        var n = 1024
        while (n < 0.08 * sampleRate) n *= 2
        n
    }
    private val fft = Fft(frameLength)
    // vDSP hanningDenormalized: 0.5·(1 − cos(2πn/N)).
    private val window = DoubleArray(frameLength) { 0.5 * (1 - cos(2 * PI * it / frameLength)) }
    private val re = DoubleArray(frameLength)
    private val im = DoubleArray(frameLength)
    private val spectrum = DoubleArray(frameLength / 2 + 1)

    /** Null for quiet frames or an F0 the frame is too short to measure. */
    fun analyze(x: FloatArray, offset: Int, count: Int, f0: Double): VoiceQuality? {
        val n = frameLength
        if (count < n || f0 <= 0) return null
        val period = sampleRate / f0
        if (n < 4 * period) return null

        var ms = 0.0
        for (i in 0 until n) { val v = x[offset + i]; ms += v * v }
        val level = 10 * log10(ms / n + 1e-12)
        if (level <= SILENCE_LEVEL) return null

        val hnr = hnr(x, offset, n, period) ?: return null
        val (jitter, shimmer) = perturbation(x, offset, n, period)

        for (i in 0 until n) { re[i] = x[offset + i] * window[i]; im[i] = 0.0 }
        fft.transform(re, im)
        val half = n / 2
        for (k in 0..half) spectrum[k] = 10 * log10(re[k] * re[k] + im[k] * im[k] + 1e-20)
        val binWidth = sampleRate / n

        val h1 = harmonicPeak(f0, binWidth)
        val h2 = harmonicPeak(2 * f0, binWidth)
        val alpha = bandPower(1000.0, min(5000.0, sampleRate / 2), binWidth) - bandPower(50.0, 1000.0, binWidth)
        val cpp = cepstralPeakProminence(f0) ?: return null
        return VoiceQuality(h1 - h2, alpha, cpp, hnr, jitter, shimmer, level)
    }

    private fun harmonicPeak(frequency: Double, binWidth: Double): Double {
        val center = frequency / binWidth
        val radius = max(1.0, 0.1 * center)
        val lo = max(1, kotlin.math.floor(center - radius).toInt())
        val hi = min(frameLength / 2, kotlin.math.ceil(center + radius).toInt())
        if (lo > hi) return -200.0
        var best = -200.0
        for (k in lo..hi) best = max(best, spectrum[k])
        return best
    }

    private fun bandPower(lowHz: Double, highHz: Double, binWidth: Double): Double {
        val lo = max(1, (lowHz / binWidth).toInt())
        val hi = min(frameLength / 2, (highHz / binWidth).toInt())
        if (lo >= hi) return -200.0
        var sum = 0.0
        for (k in lo..hi) sum += 10.0.pow(spectrum[k] / 10)
        return 10 * log10(sum + 1e-20)
    }

    /** Cepstrum of the dB spectrum; peak near the period minus a regression line (Hillenbrand 1994). */
    private fun cepstralPeakProminence(f0: Double): Double? {
        val n = frameLength
        val half = n / 2
        for (k in 0..half) { re[k] = spectrum[k]; im[k] = 0.0 }
        for (k in 1 until half) { re[n - k] = spectrum[k]; im[n - k] = 0.0 }
        fft.transform(re, im)
        val qLow = max(2, (sampleRate * 0.001).toInt())
        val qHigh = min(half - 1, (sampleRate / 55).toInt())
        if (qHigh <= qLow + 10) return null
        // vDSP's zrip output is 2× the DFT and is divided by 2n in the Swift
        // code, so |Re X[q]| / n here gives the same values.
        fun dB(q: Int) = 20 * log10(abs(re[q]) / n + 1e-9)

        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (q in qLow..qHigh) {
            val xq = q.toDouble(); val y = dB(q)
            sx += xq; sy += y; sxx += xq * xq; sxy += xq * y
        }
        val count = (qHigh - qLow + 1).toDouble()
        val slope = (count * sxy - sx * sy) / (count * sxx - sx * sx)
        val intercept = (sy - slope * sx) / count

        val q0 = sampleRate / f0
        val lo = max(qLow, (0.85 * q0).toInt())
        val hi = min(qHigh, (1.15 * q0).toInt())
        if (lo > hi) return null
        var peak = lo
        for (q in lo + 1..hi) if (dB(q) > dB(peak)) peak = q
        return dB(peak) - (intercept + slope * peak)
    }
}

/** In-place iterative radix-2 complex FFT (forward, unscaled). */
class Fft(private val n: Int) {
    init { require(n > 0 && n and (n - 1) == 0) { "FFT size must be a power of two" } }
    private val cosTable = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinTable = DoubleArray(n / 2) { -sin(2 * PI * it / n) }

    fun transform(re: DoubleArray, im: DoubleArray) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val halfSize = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                for (k in 0 until halfSize) {
                    val wr = cosTable[k * step]; val wi = sinTable[k * step]
                    val a = start + k; val b = a + halfSize
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                start += size
            }
            size *= 2
        }
    }
}
