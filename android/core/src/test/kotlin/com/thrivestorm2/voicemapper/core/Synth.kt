package com.thrivestorm2.voicemapper.core

import com.thrivestorm2.voicemapper.core.analysis.ResonanceScorer
import com.thrivestorm2.voicemapper.core.dsp.FormantAnalyzer
import com.thrivestorm2.voicemapper.core.dsp.ResonanceTracker
import com.thrivestorm2.voicemapper.core.dsp.PitchDetector
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log2

// Port of Tools/DSPCheck/main.swift's synthesis and analysis helpers.

const val RATE = 48_000.0

data class VowelSpec(val label: String, val f0: Double, val formants: List<Double>, val bandwidths: List<Double>)

/** Approximate adult vowel averages (Hillenbrand et al. 1995); same values as the Swift suite. */
val vowels: List<VowelSpec> = run {
    val bw = listOf(80.0, 100.0, 150.0, 200.0, 250.0)
    listOf(
        VowelSpec("men  /i/ heed", 138.0, listOf(342.0, 2322.0, 3000.0, 3657.0, 4500.0), bw),
        VowelSpec("men  /æ/ had", 127.0, listOf(588.0, 1952.0, 2601.0, 3500.0, 4500.0), bw),
        VowelSpec("men  /ɑ/ hod", 123.0, listOf(768.0, 1333.0, 2522.0, 3400.0, 4500.0), bw),
        VowelSpec("men  /u/ who'd", 143.0, listOf(378.0, 997.0, 2343.0, 3357.0, 4500.0), bw),
        VowelSpec("women /i/ heed", 227.0, listOf(437.0, 2761.0, 3372.0, 4352.0, 5000.0), bw),
        VowelSpec("women /æ/ had", 220.0, listOf(669.0, 2349.0, 2972.0, 4200.0, 5000.0), bw),
        VowelSpec("women /ɑ/ hod", 212.0, listOf(936.0, 1551.0, 2815.0, 4000.0, 5000.0), bw),
        VowelSpec("women /u/ who'd", 235.0, listOf(459.0, 1105.0, 2735.0, 4115.0, 5000.0), bw),
    )
}

/** Deterministic noise, bit-identical to the Swift LCG. */
class Lcg {
    private var state: ULong = 0x9E3779B97F4A7C15uL
    fun next(): Double {
        state = state * 6364136223846793005uL + 1442695040888963407uL
        return (state shr 11).toDouble() / (1uL shl 53).toDouble() * 2 - 1
    }
}

fun synthesize(
    f0: Double, formants: List<Double>, bandwidths: List<Double>, seconds: Double = 1.0,
    jitter: Double = 0.005, shimmer: Double = 0.03, noise: Double = 0.002, alternate: Double = 0.0,
): FloatArray {
    val rng = Lcg()
    val count = (RATE * seconds).toInt()
    val source = DoubleArray(count)
    var start = 0.0
    var pulse = 0
    while (start.toInt() < count) {
        val period = RATE / f0 * (1 + jitter * rng.next())
        var amp = 1 + shimmer * rng.next()
        if (alternate > 0 && pulse % 2 == 1) amp *= 1 - alternate
        val open = 0.4 * period
        val close = 0.16 * period
        var i = ceil(start).toInt()
        while (i < start + period && i < count) {
            val p = i - start
            val v = when {
                p < open -> 0.5 * (1 - cos(PI * p / open))
                p < open + close -> cos(PI / 2 * (p - open) / close)
                else -> 0.0
            }
            source[i] = amp * v
            i++
        }
        start += period; pulse++
    }
    val y = source.copyOf()
    for ((f, b) in formants.zip(bandwidths)) {
        val c = -exp(-2 * PI * b / RATE)
        val bb = 2 * exp(-PI * b / RATE) * cos(2 * PI * f / RATE)
        val a = 1 - bb - c
        var y1 = 0.0; var y2 = 0.0
        for (i in 0 until count) {
            val out = a * y[i] + bb * y1 + c * y2
            y2 = y1; y1 = out; y[i] = out
        }
    }
    var prev = 0.0
    for (i in 0 until count) { val v = y[i]; y[i] = v - prev; prev = v }
    val peak = y.maxOf { abs(it) }
    return FloatArray(count) { (0.3 * y[it] / peak + noise * rng.next()).toFloat() }
}

/** Middle-element median, as the Swift suite uses. */
fun medianOf(v: List<Double>): Double? = if (v.isEmpty()) null else v.sorted()[v.size / 2]

fun cents(measured: Double, truth: Double) = 1200 * log2(measured / truth)

data class PitchStats(val median: Double?, val voiced: Double, val gross: Double)

fun pitchStats(x: FloatArray, truth: Double?, rate: Double = RATE): PitchStats {
    val detector = PitchDetector(rate)
    val hop = 512
    val values = mutableListOf<Double>()
    var frames = 0; var gross = 0
    var start = 0
    while (start + detector.frameLength <= x.size) {
        frames++
        detector.detect(x, start, detector.frameLength)?.let { r ->
            values += r.frequency
            if (truth != null && abs(cents(r.frequency, truth)) > 50) gross++
        }
        start += hop
    }
    return PitchStats(medianOf(values), values.size.toDouble() / maxOf(frames, 1), gross.toDouble() / maxOf(values.size, 1))
}

data class FormantStats(val formants: List<Double?>, val spacing: Double?, val accepted: Double)

/** Like iOS DSPCheck: the LPC order (or blend) the tracker uses at pitch [f0]; null = standard. */
fun formantStats(x: FloatArray, rate: Double = RATE, f0: Double? = null): FormantStats {
    val w = f0?.let { ResonanceTracker.highPitchShare(it) } ?: 0.0
    val standard = FormantAnalyzer(rate)
    val high = FormantAnalyzer(rate, FormantAnalyzer.Poles.HIGH_PITCH)
    val hop = 2048
    val perSlot = List(4) { mutableListOf<Double>() }
    val spacings = mutableListOf<Double>()
    var frames = 0
    var start = 0
    while (start + standard.frameLength <= x.size) {
        frames++
        val lf = if (w < 1) standard.analyze(x, start, standard.frameLength) else null
        val hf = if (w > 0) high.analyze(x, start, standard.frameLength) else null
        val ls = lf?.let { ResonanceScorer.spacing(it) }
        val hs = hf?.let { ResonanceScorer.spacing(it) }
        val frame: Pair<Double, List<FormantAnalyzer.Formant>>? = when {
            ls != null && hs != null -> Pair(ls + w * (hs - ls), if (w < 0.5) lf!! else hf!!)
            ls != null -> Pair(ls, lf!!)
            hs != null -> Pair(hs, hf!!)
            else -> null
        }
        if (frame != null) {
            spacings += frame.first
            ResonanceScorer.assigned(frame.second).forEachIndexed { i, v -> if (i < 4) perSlot[i] += v }
        }
        start += hop
    }
    return FormantStats(perSlot.map { medianOf(it) }, medianOf(spacings), spacings.size.toDouble() / maxOf(frames, 1))
}

fun trueSpacing(formants: List<Double>): Double {
    var num = 0.0; var den = 0.0
    formants.take(4).forEachIndexed { i, f -> val k = (2 * i + 1) / 2.0; num += k * f; den += k * k }
    return num / den
}

/** Mono-mixed PCM WAV reader (16/24/32-bit int or 32-bit float). Returns the samples and
 * their native rate: the DSP takes any rate, so no resampling is needed. */
fun readWav(file: File): Pair<FloatArray, Double>? {
    if (!file.exists()) return null
    val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    fun tag() = String(ByteArray(4).also { bytes.get(it) }, Charsets.US_ASCII)
    if (bytes.remaining() < 12 || tag() != "RIFF") return null
    bytes.int
    if (tag() != "WAVE") return null
    var format = 0; var channels = 0; var rate = 0; var bits = 0
    var data: FloatArray? = null
    while (bytes.remaining() >= 8) {
        val id = tag()
        val size = bytes.int
        val body = bytes.position()
        when (id) {
            "fmt " -> { format = bytes.short.toInt(); channels = bytes.short.toInt(); rate = bytes.int; bytes.int; bytes.short; bits = bytes.short.toInt() }
            "data" -> {
                if (channels == 0) return null
                val width = bits / 8
                val frameBytes = width * channels
                val n = minOf(size, bytes.limit() - body) / frameBytes
                data = FloatArray(n) { i ->
                    var sum = 0f
                    for (c in 0 until channels) {
                        val pos = body + i * frameBytes + c * width
                        sum += when {
                            format == 3 && bits == 32 -> bytes.getFloat(pos)
                            bits == 16 -> bytes.getShort(pos) / 32768f
                            bits == 24 -> ((bytes.get(pos).toInt() and 0xff) or ((bytes.get(pos + 1).toInt() and 0xff) shl 8) or (bytes.get(pos + 2).toInt() shl 16)) / 8388608f
                            bits == 32 -> bytes.getInt(pos) / 2147483648f
                            else -> 0f
                        }
                    }
                    sum / channels
                }
            }
        }
        val next = body + size + (size and 1)
        if (next > bytes.limit()) break
        bytes.position(next)
    }
    return data?.let { it to rate.toDouble() }
}
