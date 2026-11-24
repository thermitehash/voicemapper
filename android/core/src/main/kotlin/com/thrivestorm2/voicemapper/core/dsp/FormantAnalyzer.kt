package com.thrivestorm2.voicemapper.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * LPC formant estimator. Port of iOS `DSP/FormantAnalyzer.swift`; keep the two
 * in step. Downsamples to ~11 kHz, pre-emphasis + Hamming window,
 * autocorrelation + Levinson–Durbin, polynomial roots → formants.
 */
class FormantAnalyzer(sampleRate: Double, poles: Poles = Poles.STANDARD) {
    data class Formant(val frequency: Double, val bandwidth: Double)

    /**
     * How many LPC poles to fit. STANDARD is two per kHz plus four (16 at
     * 12 kHz); HIGH_PITCH is two fewer, for voices above ~160 Hz, whose sparse
     * harmonics otherwise catch a spare pole and push every formant up a slot
     * (S18; see iOS `FormantAnalyzer.Poles`).
     */
    enum class Poles { STANDARD, HIGH_PITCH }

    companion object {
        private const val TARGET_RATE = 11_025.0
        private const val MIN_FORMANT = 180.0
        private const val MAX_BANDWIDTH = 600.0

        /** LPC coefficients [1, a1, …, ap], or null if the model is unstable. */
        fun levinson(r: DoubleArray, p: Int): DoubleArray? {
            val a = DoubleArray(p + 1)
            a[0] = 1.0
            var error = r[0]
            for (i in 1..p) {
                var acc = r[i]
                for (j in 1 until i) acc += a[j] * r[i - j]
                val k = -acc / error
                if (abs(k) >= 1) return null
                val previous = a.copyOf()
                for (j in 1 until i) a[j] = previous[j] + k * previous[i - j]
                a[i] = k
                error *= 1 - k * k
            }
            return a
        }

        /** All complex roots of the monic polynomial c[0]·zⁿ + … + c[n], by Aberth–Ehrlich iteration. */
        fun roots(c: DoubleArray): List<Complex> {
            val degree = c.size - 1
            if (degree <= 0) return emptyList()
            fun evaluate(z: Complex): Pair<Complex, Complex> {
                var p = Complex(c[0], 0.0)
                var dp = Complex(0.0, 0.0)
                for (k in 1..degree) {
                    dp = dp * z + p
                    p = p * z + Complex(c[k], 0.0)
                }
                return p to dp
            }
            val z = Array(degree) { k ->
                val angle = 2 * PI * (k + 0.25) / degree
                Complex(0.9 * cos(angle), 0.9 * sin(angle))
            }
            val one = Complex(1.0, 0.0)
            repeat(80) {
                var largestStep = 0.0
                for (i in 0 until degree) {
                    val (p, dp) = evaluate(z[i])
                    if (p.magnitude < 1e-14) continue
                    val ratio = p / dp
                    var sum = Complex(0.0, 0.0)
                    for (j in 0 until degree) if (j != i) sum = sum + one / (z[i] - z[j])
                    val step = ratio / (one - ratio * sum)
                    z[i] = z[i] - step
                    largestStep = max(largestStep, step.magnitude)
                }
                if (largestStep < 1e-12) return z.toList()
            }
            return z.toList()
        }
    }

    private val factor = max(1, (sampleRate / TARGET_RATE).roundToInt())
    /** Rate after downsampling. */
    val analysisRate = sampleRate / factor
    // Two poles per kHz plus two for the glottal source and lip radiation.
    private val standardOrder = 4 + (analysisRate / 1000).toInt()
    private val order = if (poles == Poles.STANDARD) standardOrder else standardOrder - 2
    private val analysisCount = (analysisRate * 0.040).toInt()
    private val lowPass: FloatArray = if (factor > 1) {
        val taps = 16 * factor + 1
        val cutoff = 0.9 * 0.5 / factor
        val mid = (taps - 1) / 2.0
        val filter = FloatArray(taps) { i ->
            val n = i - mid
            val sinc = if (n == 0.0) 2 * cutoff else sin(2 * PI * cutoff * n) / (PI * n)
            val hamming = 0.54 - 0.46 * cos(2 * PI * i / (taps - 1))
            (sinc * hamming).toFloat()
        }
        val sum = filter.sum()
        FloatArray(taps) { filter[it] / sum }
    } else floatArrayOf(1f)

    /** Input samples [analyze] reads (≈43 ms at 48 kHz). */
    val frameLength = (analysisCount - 1) * factor + lowPass.size

    // vDSP's Hamming window: 0.54 − 0.46·cos(2πn/N), n in 0 until N.
    private val window = FloatArray(analysisCount) { (0.54 - 0.46 * cos(2 * PI * it / analysisCount)).toFloat() }
    private val decimated = FloatArray(analysisCount)
    private val emphasized = FloatArray(analysisCount)

    /** Formants sorted by frequency, or null if the frame is too short or silent. */
    fun analyze(x: FloatArray, offset: Int = 0, count: Int = x.size - offset): List<Formant>? {
        if (count < frameLength) return null
        val n = analysisCount

        // vDSP_desamp: C[i] = Σ A[i·factor + p]·F[p]
        for (i in 0 until n) {
            var s = 0f
            val base = offset + i * factor
            for (p in lowPass.indices) s += x[base + p] * lowPass[p]
            decimated[i] = s
        }

        val alpha = exp(-2 * PI * 50 / analysisRate).toFloat()
        emphasized[0] = decimated[0]
        for (i in 1 until n) emphasized[i] = decimated[i] - alpha * decimated[i - 1]
        for (i in 0 until n) emphasized[i] *= window[i]

        val r = DoubleArray(order + 1)
        for (lag in 0..order) {
            var sum = 0f
            for (i in 0 until n - lag) sum += emphasized[i] * emphasized[i + lag]
            r[lag] = sum.toDouble()
        }
        if (r[0] <= 1e-9) return null
        r[0] *= 1 + 1e-9

        val a = levinson(r, order) ?: return null
        val roots = roots(a)

        val nyquist = analysisRate / 2
        val formants = mutableListOf<Formant>()
        for (z in roots) {
            if (z.im <= 0) continue
            val frequency = atan2(z.im, z.re) * analysisRate / (2 * PI)
            val bandwidth = -ln(z.magnitude) * analysisRate / PI
            if (frequency < MIN_FORMANT || frequency > nyquist - 100 || bandwidth <= 0 || bandwidth > MAX_BANDWIDTH) continue
            formants.add(Formant(frequency, bandwidth))
        }
        return formants.sortedBy { it.frequency }
    }
}

/** Just enough complex arithmetic for root finding. */
data class Complex(val re: Double, val im: Double) {
    val magnitude: Double get() = sqrt(re * re + im * im)
    operator fun plus(b: Complex) = Complex(re + b.re, im + b.im)
    operator fun minus(b: Complex) = Complex(re - b.re, im - b.im)
    operator fun times(b: Complex) = Complex(re * b.re - im * b.im, re * b.im + im * b.re)
    operator fun div(b: Complex): Complex {
        val d = b.re * b.re + b.im * b.im
        if (d <= 0) return Complex(Double.POSITIVE_INFINITY, 0.0)
        return Complex((re * b.re + im * b.im) / d, (im * b.re - re * b.im) / d)
    }
}
