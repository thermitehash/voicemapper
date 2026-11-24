package com.thrivestorm2.voicemapper.core.dsp

import com.thrivestorm2.voicemapper.core.analysis.Note
import com.thrivestorm2.voicemapper.core.analysis.ResonanceScorer
import com.thrivestorm2.voicemapper.core.analysis.Stats
import kotlin.math.log2

/** What the pitch tracker reports while the user is voicing. */
data class PitchReading(val frequency: Double, val note: Note)

/**
 * Ports of iOS `PitchTracker`, `ResonanceTracker` and `VoiceQualityTracker`.
 * Each is fed from the audio thread only; make new ones when the stream restarts.
 */
class PitchTracker(sampleRate: Double, private val onReading: (PitchReading?) -> Unit) {
    companion object {
        private const val HOP = 1024
        private const val MEDIAN_COUNT = 5
        private const val DROPOUT_FRAMES = 6
        private const val SUSPECT_DROP = 4.0
        private const val JUMP_FRAMES = 10
    }

    private val detector = PitchDetector(sampleRate)
    private val samples = SampleBuffer()
    private val recent = ArrayDeque<Double>()
    private var unvoicedRun = 0
    private var hasReading = false
    private var lastFrequency: Double? = null
    private var jumpRun = 0

    /** Latest smoothed F0, null while unvoiced. */
    val currentFrequency: Double? get() = if (hasReading) lastFrequency else null

    fun process(incoming: FloatArray, length: Int = incoming.size) {
        samples.append(incoming, 0, length)
        val needed = detector.frameLength
        while (samples.count >= needed) {
            val result = detector.detect(samples.data, samples.start, samples.count)
            samples.removeFirst(HOP)
            update(result)
        }
    }

    private fun update(result: PitchDetector.Result?) {
        if (result == null) {
            unvoicedRun++
            if (unvoicedRun >= DROPOUT_FRAMES && hasReading) {
                hasReading = false
                jumpRun = 0
                recent.clear()
                onReading(null)
            }
            return
        }
        unvoicedRun = 0
        val last = lastFrequency
        if (hasReading && last != null && 12 * log2(result.frequency / last) < -SUSPECT_DROP) {
            jumpRun++
            if (jumpRun < JUMP_FRAMES) return
            recent.clear()
        }
        jumpRun = 0
        recent.addLast(result.frequency)
        if (recent.size > MEDIAN_COUNT) recent.removeFirst()
        val median = recent.sorted()[recent.size / 2]
        hasReading = true
        lastFrequency = median
        onReading(PitchReading(median, Note.fromFrequency(median)))
    }
}

/** What the resonance tracker reports while the user holds a voiced sound. */
data class ResonanceReading(val score: Double, val spacing: Double, val formants: List<Double>)

class ResonanceTracker(sampleRate: Double, private val onReading: (ResonanceReading?) -> Unit) {
    companion object {
        private const val HOP = 2048
        private const val MEDIAN_COUNT = 15
        private const val FOLLOW_RATE = 0.2
        private const val MINIMUM_FRAMES = 3
        private const val DROPOUT_FRAMES = 8
        /** Above this F0 LPC can't find formants reliably (S13 check). */
        const val MAX_PITCH = 350.0
        /**
         * Frames whose F3 is below this share of the running median F3 are
         * r-coloured ("her", "car") and skipped, so rhotic accents don't read
         * darker than non-rhotic ones (S18; see the iOS tracker).
         */
        private const val RHOTIC_F3_RATIO = 0.8
        private const val F3_WINDOW_COUNT = 60
        private const val F3_MINIMUM_FRAMES = 10
        /** Standard and high-pitch LPC orders are blended across this pitch band. */
        private const val BLEND_LOW = 150.0
        private const val BLEND_HIGH = 175.0

        /** 0 → standard order only, 1 → high-pitch order only. */
        fun highPitchShare(pitch: Double): Double = ((pitch - BLEND_LOW) / (BLEND_HIGH - BLEND_LOW)).coerceIn(0.0, 1.0)
    }

    private class Frame(val spacing: Double, val formants: List<FormantAnalyzer.Formant>)

    private val standard = FormantAnalyzer(sampleRate)
    private val highPitch = FormantAnalyzer(sampleRate, FormantAnalyzer.Poles.HIGH_PITCH)
    private val samples = SampleBuffer()
    private val recentF3 = ArrayDeque<Double>()
    private val recent = ArrayDeque<Double>()
    private var missedRun = 0
    private var hasReading = false
    private var smoothed: Double? = null

    /** [pitch] is the current smoothed F0, null when unvoiced. */
    fun process(incoming: FloatArray, length: Int = incoming.size, pitch: Double?) {
        samples.append(incoming, 0, length)
        val needed = maxOf(standard.frameLength, HOP)
        while (samples.count >= needed) {
            val frame = if (pitch != null && pitch <= MAX_PITCH) analyze(pitch) else null
            samples.removeFirst(HOP)
            update(frame)
        }
    }

    /** Spacing from the LPC order that suits this pitch, blended across the band. */
    private fun analyze(pitch: Double): Frame? {
        val w = highPitchShare(pitch)
        var low: Frame? = null
        var high: Frame? = null
        if (w < 1) standard.analyze(samples.data, samples.start, samples.count)?.let { f ->
            ResonanceScorer.spacing(f)?.let { low = Frame(it, f) }
        }
        if (w > 0) highPitch.analyze(samples.data, samples.start, samples.count)?.let { f ->
            ResonanceScorer.spacing(f)?.let { high = Frame(it, f) }
        }
        val l = low
        val h = high
        return when {
            l != null && h != null -> Frame(l.spacing + w * (h.spacing - l.spacing), if (w < 0.5) l.formants else h.formants)
            l != null -> l
            else -> h
        }
    }

    private fun update(frame: Frame?) {
        if (frame == null) {
            missedRun++
            if (missedRun >= DROPOUT_FRAMES) {
                recent.clear()
                smoothed = null
                if (hasReading) { hasReading = false; onReading(null) }
            }
            return
        }
        // Not a miss: an "r" stretch shouldn't blank the reading.
        if (isRhotic(frame.formants)) return
        missedRun = 0
        recent.addLast(frame.spacing)
        if (recent.size > MEDIAN_COUNT) recent.removeFirst()
        if (recent.size < MINIMUM_FRAMES) return
        val median = recent.sorted()[recent.size / 2]
        val value = smoothed?.let { it + FOLLOW_RATE * (median - it) } ?: median
        smoothed = value
        hasReading = true
        onReading(ResonanceReading(ResonanceScorer.score(value), value, frame.formants.take(4).map { it.frequency }))
    }

    /** Records this frame's F3 and reports whether it is r-coloured. */
    private fun isRhotic(formants: List<FormantAnalyzer.Formant>): Boolean {
        val assigned = ResonanceScorer.assigned(formants)
        if (assigned.size < 3) return false
        val f3 = assigned[2]
        val reference = if (recentF3.size >= F3_MINIMUM_FRAMES) recentF3.sorted()[recentF3.size / 2] else null
        recentF3.addLast(f3)
        if (recentF3.size > F3_WINDOW_COUNT) recentF3.removeFirst()
        return reference != null && f3 < RHOTIC_F3_RATIO * reference
    }
}

class VoiceQualityTracker(sampleRate: Double, private val onReading: (VoiceQuality?) -> Unit) {
    companion object {
        private const val WINDOW_COUNT = 24
        private const val MINIMUM_FRAMES = 6
        private const val DROPOUT_FRAMES = 8
        private val PITCH_RANGE = 70.0..800.0
    }

    private val analyzer = VoiceQualityAnalyzer(sampleRate)
    private val hop = analyzer.frameLength / 2
    private val samples = SampleBuffer()
    private val recent = ArrayDeque<VoiceQuality>()
    private var missedRun = 0
    private var hasReading = false

    fun process(incoming: FloatArray, length: Int = incoming.size, pitch: Double?) {
        samples.append(incoming, 0, length)
        while (samples.count >= analyzer.frameLength) {
            val quality = if (pitch != null && pitch in PITCH_RANGE)
                analyzer.analyze(samples.data, samples.start, samples.count, pitch) else null
            samples.removeFirst(hop)
            update(quality)
        }
    }

    private fun update(quality: VoiceQuality?) {
        if (quality == null) {
            missedRun++
            if (missedRun >= DROPOUT_FRAMES) {
                recent.clear()
                if (hasReading) { hasReading = false; onReading(null) }
            }
            return
        }
        missedRun = 0
        recent.addLast(quality)
        if (recent.size > WINDOW_COUNT) recent.removeFirst()
        if (recent.size < MINIMUM_FRAMES) return
        hasReading = true
        onReading(VoiceQuality.median(recent))
    }
}

/** Background noise floor: 10th-percentile level of short buffers over ~3 s. */
class NoiseMeter(private val sampleRate: Double, private val onFloor: (Double) -> Unit) {
    companion object {
        private const val WINDOW = 3.0
        private const val REPORT_INTERVAL = 0.25
    }

    private val levels = ArrayDeque<Double>()
    private var sinceReport = 0.0

    fun process(samples: FloatArray, length: Int = samples.size) {
        if (length <= 0) return
        var ms = 0.0
        for (i in 0 until length) ms += samples[i] * samples[i]
        ms /= length
        val seconds = length / sampleRate
        levels.addLast(10 * kotlin.math.log10(ms + 1e-12))
        val capacity = (WINDOW / seconds).toInt()
        while (levels.size > capacity) levels.removeFirst()
        sinceReport += seconds
        if (sinceReport < REPORT_INTERVAL || levels.size < capacity / 2) return
        sinceReport = 0.0
        Stats.percentile(levels.toList(), 0.1)?.let(onFloor)
    }
}
