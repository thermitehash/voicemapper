package com.thrivestorm2.voicemapper.audio

import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.thrivestorm2.voicemapper.core.analysis.Stats
import com.thrivestorm2.voicemapper.core.dsp.NoiseMeter
import com.thrivestorm2.voicemapper.core.dsp.PitchReading
import com.thrivestorm2.voicemapper.core.dsp.PitchTracker
import com.thrivestorm2.voicemapper.core.dsp.ResonanceReading
import com.thrivestorm2.voicemapper.core.dsp.ResonanceTracker
import com.thrivestorm2.voicemapper.core.dsp.VoiceQuality
import com.thrivestorm2.voicemapper.core.dsp.VoiceQualityTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Android counterpart of iOS `AudioEngine`: mic capture for analysis plus the
 * hear-yourself path (mic → delay → voice level → headphones). Both share one
 * capture thread, which runs while either is on, like the shared iOS engine.
 *
 * Phones clean up the mic signal (gain control, noise suppression, echo
 * cancelling) and that skews pitch, formants and the health measures, so we
 * ask for the UNPROCESSED source where the phone supports it, else
 * VOICE_RECOGNITION (which Android's compatibility rules require to be close
 * to unprocessed), and switch off any effects still attached. Like iOS
 * "Automatic", the phone's own mic is preferred even when a headset is in.
 *
 * The mic is read in small chunks so the headphones hear it quickly, and the
 * chunks are regrouped into 1024-sample blocks for the trackers, matching the
 * iOS tap size (the noise meter's window depends on block size).
 */
class AudioCapture(context: Context) {
    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 48_000
        /** Analysis block, same as the iOS tap. */
        private const val BLOCK = 1024
        /** Mic read size: ~4 ms, so hear-yourself adds little delay. */
        private const val CHUNK = 192
        /** A room louder than this is noisy whatever the voice level … */
        const val NOISY_FLOOR = -48.0
        /** … and the voice should be at least this far above the room. */
        const val MINIMUM_VOICE_MARGIN = 18.0
        /** Headphone volume above which a safe-listening warning shows (iOS `safeVolumeCap`). */
        const val SAFE_VOLUME_CAP = 0.75f
        /** Longest delay offered for delayed-feedback drills (iOS `maxDelayMs`). */
        const val MAX_DELAY_MS = 500.0
        const val DEFAULT_VOICE_LEVEL = 0.7f
        private const val KEY_VOICE_LEVEL = "hearYourself.voiceLevel"
        private const val KEY_DELAY_MS = "hearYourself.delayMs"
        // In the companion so they exist before init{} calls refreshRoute().
        private val wiredTypes = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES); add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
        }
        private val bluetoothTypes = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP); add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (android.os.Build.VERSION.SDK_INT >= 31) { add(AudioDeviceInfo.TYPE_BLE_HEADSET) }
        }
        private val headphoneTypes = wiredTypes + bluetoothTypes
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("voicemapper", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private val _pitch = MutableStateFlow<PitchReading?>(null)
    val pitch: StateFlow<PitchReading?> = _pitch.asStateFlow()
    private val _resonance = MutableStateFlow<ResonanceReading?>(null)
    val resonance: StateFlow<ResonanceReading?> = _resonance.asStateFlow()
    private val _quality = MutableStateFlow<VoiceQuality?>(null)
    val quality: StateFlow<VoiceQuality?> = _quality.asStateFlow()
    private val _noiseFloor = MutableStateFlow<Double?>(null)
    val noiseFloor: StateFlow<Double?> = _noiseFloor.asStateFlow()
    private val _isNoisy = MutableStateFlow(false)
    val isNoisy: StateFlow<Boolean> = _isNoisy.asStateFlow()
    /** Mic capture for pitch analysis is on (the Speak button). */
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    private val _isHearingYourself = MutableStateFlow(false)
    val isHearingYourself: StateFlow<Boolean> = _isHearingYourself.asStateFlow()

    /** Debug builds only: show a made-up reading for store screenshots (the emulator has no voice input). */
    fun debugShowReading(pitch: PitchReading, resonance: ResonanceReading, quality: VoiceQuality) {
        _pitch.value = pitch; _resonance.value = resonance; _quality.value = quality; _isListening.value = true
    }

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    /** Which mic source the phone gave us, for Settings ▸ About this phone. */
    private val _sourceDescription = MutableStateFlow("")
    val sourceDescription: StateFlow<String> = _sourceDescription.asStateFlow()
    /** The mic in use, e.g. "Phone mic" or "Headset mic". */
    private val _activeMicrophoneName = MutableStateFlow<String?>(null)
    val activeMicrophoneName: StateFlow<String?> = _activeMicrophoneName.asStateFlow()
    private val _headphonesConnected = MutableStateFlow(false)
    val headphonesConnected: StateFlow<Boolean> = _headphonesConnected.asStateFlow()
    private val _outputIsBluetooth = MutableStateFlow(false)
    val outputIsBluetooth: StateFlow<Boolean> = _outputIsBluetooth.asStateFlow()
    /** Name of the headphones, e.g. "Galaxy Buds2" or "Wired headphones". */
    private val _outputName = MutableStateFlow<String?>(null)
    val outputName: StateFlow<String?> = _outputName.asStateFlow()
    /**
     * Set when the user closes the Bluetooth banner; cleared on every output
     * change and each time hear-yourself turns on, so it is never hidden for good.
     */
    private val _bluetoothBannerDismissed = MutableStateFlow(false)
    val bluetoothBannerDismissed: StateFlow<Boolean> = _bluetoothBannerDismissed.asStateFlow()
    /** Measured mic-to-ear delay (excluding the delay slider). Null when hear-yourself is off. */
    private val _deviceLatencyMs = MutableStateFlow<Double?>(null)
    val deviceLatencyMs: StateFlow<Double?> = _deviceLatencyMs.asStateFlow()
    /** Media volume (0–1): the same as the phone's volume buttons. */
    private val _systemVolume = MutableStateFlow(readVolume())
    val systemVolume: StateFlow<Float> = _systemVolume.asStateFlow()

    /** How loud your voice is in the headphones. */
    private val _voiceLevel = MutableStateFlow(prefs.getFloat(KEY_VOICE_LEVEL, DEFAULT_VOICE_LEVEL))
    val voiceLevel: StateFlow<Float> = _voiceLevel.asStateFlow()
    fun setVoiceLevel(v: Float) {
        _voiceLevel.value = v.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_VOICE_LEVEL, _voiceLevel.value).apply()
    }

    private val _delayMs = MutableStateFlow(prefs.getFloat(KEY_DELAY_MS, 0f).toDouble())
    val delayMs: StateFlow<Double> = _delayMs.asStateFlow()
    fun setDelayMs(v: Double) {
        _delayMs.value = v.coerceIn(0.0, MAX_DELAY_MS)
        prefs.edit().putFloat(KEY_DELAY_MS, _delayMs.value.toFloat()).apply()
    }

    /** Only meaningful with headphones (iOS `isAboveSafeVolume`). */
    fun isAboveSafeVolume(): Boolean = _headphonesConnected.value && _systemVolume.value > SAFE_VOLUME_CAP

    fun setSystemVolume(v: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = Math.round(v.coerceIn(0f, 1f) * max)
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
        _systemVolume.value = readVolume()
    }

    fun dismissBluetoothBanner() { _bluetoothBannerDismissed.value = true }

    @Volatile private var running = false
    /** Read on the audio thread: monitor output is silent unless this is set. */
    @Volatile private var monitoring = false
    /** False while the monitor track is routed to the phone speaker, to prevent feedback. */
    @Volatile private var outputSafe = true
    private var thread: Thread? = null
    private var noisySince: Instant? = null
    private var quietSince: Instant? = null
    private var lastOutputs: Set<Int> = emptySet()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = routeChanged()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = routeChanged()
    }

    private val volumeObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { _systemVolume.value = readVolume() }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, main)
        resolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        refreshRoute()
    }

    // Hear yourself

    /** Turns the hear-yourself path on or off. Only turns on with headphones connected, to prevent speaker feedback. */
    fun setHearYourself(on: Boolean) {
        _lastError.value = null
        if (!on) {
            _isHearingYourself.value = false
            monitoring = false
            _deviceLatencyMs.value = null
            stopIfIdle()
            return
        }
        refreshRoute()
        if (!_headphonesConnected.value) {
            _lastError.value = "Connect headphones to hear yourself."
            return
        }
        if (!ensureRunning()) return
        _bluetoothBannerDismissed.value = false
        _isHearingYourself.value = true
        monitoring = true
    }

    /** Turns mic capture for pitch analysis on or off, without hearing yourself. */
    fun setListening(on: Boolean) {
        _lastError.value = null
        if (!on) {
            _isListening.value = false
            stopIfIdle()
            return
        }
        if (!ensureRunning()) return
        _isListening.value = true
    }

    /** Settings ▸ Delete all data: back to the default hear-yourself settings. */
    fun resetPreferences() {
        setVoiceLevel(DEFAULT_VOICE_LEVEL)
        setDelayMs(0.0)
    }

    private fun stopIfIdle() {
        if (!_isHearingYourself.value && !_isListening.value) stop()
    }

    private fun ensureRunning(): Boolean = running || start()

    @SuppressLint("MissingPermission") // The UI asks for RECORD_AUDIO before calling this.
    private fun start(): Boolean {
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuffer <= 0) { fail("This phone's microphone doesn't support 48 kHz float capture."); return false }
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build())
                .setBufferSizeInBytes(maxOf(minBuffer, BLOCK * 4 * 4))
                .build()
        } catch (e: Exception) {
            fail("Couldn't open the microphone: ${e.message}"); return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); fail("Couldn't open the microphone."); return false }
        // Like iOS Automatic: the phone's own mic, even with a headset plugged in.
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?.let { record.setPreferredDevice(it) }

        val effects = disableEffects(record.audioSessionId)
        val rate = record.sampleRate.toDouble()
        _sourceDescription.value = buildString {
            append(if (unprocessed) "Unprocessed mic" else "Voice-recognition mic (unprocessed not offered)")
            append(" · ${record.sampleRate} Hz")
            if (effects.isNotEmpty()) append(" · switched off: ${effects.joinToString()}")
        }

        val pitchTracker = PitchTracker(rate) { r -> main.post { _pitch.value = r } }
        val resonanceTracker = ResonanceTracker(rate) { r -> main.post { _resonance.value = r } }
        val qualityTracker = VoiceQualityTracker(rate) { r -> main.post { _quality.value = r } }
        val noiseMeter = NoiseMeter(rate) { f -> main.post { updateNoise(f) } }

        running = true
        _lastError.value = null
        _pitch.value = null; _resonance.value = null; _quality.value = null
        resetNoise()
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val chunk = FloatArray(CHUNK)
            val block = FloatArray(BLOCK)
            var filled = 0
            val monitor = Monitor(rate)
            try {
                record.startRecording()
                main.post { _activeMicrophoneName.value = micName(record.routedDevice) }
                while (running) {
                    val n = record.read(chunk, 0, CHUNK, AudioRecord.READ_BLOCKING)
                    if (n <= 0) { if (n < 0) Log.w(TAG, "read error $n"); continue }
                    if (monitoring) monitor.play(chunk, n, record) else monitor.close()
                    var i = 0
                    while (i < n) {
                        val take = minOf(n - i, BLOCK - filled)
                        System.arraycopy(chunk, i, block, filled, take)
                        filled += take; i += take
                        if (filled == BLOCK) {
                            filled = 0
                            pitchTracker.process(block, BLOCK)
                            val f0 = pitchTracker.currentFrequency
                            resonanceTracker.process(block, BLOCK, f0)
                            qualityTracker.process(block, BLOCK, f0)
                            noiseMeter.process(block, BLOCK)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture failed", e)
                main.post { fail("The microphone stopped: ${e.message}"); stop() }
            } finally {
                monitor.close()
                runCatching { record.stop() }
                record.release()
            }
        }, "VoiceMapper-audio").also { it.start() }
        return true
    }

    /**
     * The hear-yourself output: a pure delay line and the voice level, played
     * through a low-latency AudioTrack. Lives on the audio thread only.
     */
    private inner class Monitor(private val rate: Double) {
        private var track: AudioTrack? = null
        private val ring = FloatArray((MAX_DELAY_MS / 1000 * rate).toInt() + CHUNK + 1)
        private var write = 0
        private val out = FloatArray(CHUNK)
        private var framesWritten = 0L
        private var framesRead = 0L
        private var underruns = 0
        private var lastLatencyCheck = 0L
        private val latencies = ArrayDeque<Double>()
        private val inTs = AudioTimestamp()
        private val outTs = AudioTimestamp()

        fun play(input: FloatArray, n: Int, record: AudioRecord) {
            framesRead += n
            val t = track ?: open() ?: return
            val gain = if (outputSafe) _voiceLevel.value else 0f
            val delay = (_delayMs.value / 1000 * rate).toInt().coerceIn(0, ring.size - CHUNK - 1)
            for (i in 0 until n) {
                ring[write] = input[i]
                var r = write - delay
                if (r < 0) r += ring.size
                out[i] = ring[r] * gain
                write = if (write + 1 == ring.size) 0 else write + 1
            }
            // Non-blocking: if the output is full, drop rather than stall the
            // mic (and the analysis). That also keeps the delay from creeping up.
            val w = t.write(out, 0, n, AudioTrack.WRITE_NON_BLOCKING)
            if (w > 0) framesWritten += w
            // Grow the buffer a burst at a time if it underruns (crackles).
            val u = t.underrunCount
            if (u > underruns) {
                underruns = u
                val burst = burstFrames()
                if (t.bufferSizeInFrames + burst <= t.bufferCapacityInFrames) t.setBufferSizeInFrames(t.bufferSizeInFrames + burst)
            }
            measureLatency(t, record)
        }

        private fun open(): AudioTrack? = try {
            val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(minBuffer)
                .build()
                .also { t ->
                    t.setBufferSizeInFrames(maxOf(2 * burstFrames(), 2 * CHUNK))
                    t.addOnRoutingChangedListener({ router -> outputSafe = isSafeOutput(router.routedDevice) }, main)
                    t.play()
                    outputSafe = isSafeOutput(t.routedDevice) || t.routedDevice == null && _headphonesConnected.value
                    underruns = t.underrunCount
                    framesWritten = 0
                    track = t
                }
        } catch (e: Exception) {
            Log.e(TAG, "monitor output failed", e)
            main.post { setHearYourself(false); _lastError.value = "Couldn't play to the headphones: ${e.message}" }
            null
        }

        /**
         * Mic-to-ear delay from the two clocks: how long ago the newest mic
         * sample was captured, plus how long until the newest written sample
         * plays. Median of the last few, published every half second.
         */
        private fun measureLatency(t: AudioTrack, record: AudioRecord) {
            val now = System.nanoTime()
            if (now - lastLatencyCheck < 100_000_000L) return
            lastLatencyCheck = now
            if (record.getTimestamp(inTs, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return
            if (!t.getTimestamp(outTs)) return
            val captured = inTs.nanoTime + ((framesRead - inTs.framePosition) / rate * 1e9).toLong()
            val plays = outTs.nanoTime + ((framesWritten - outTs.framePosition) / rate * 1e9).toLong()
            val ms = ((now - captured) + (plays - now)) / 1e6
            if (ms !in 0.0..2000.0) return
            latencies.addLast(ms)
            while (latencies.size > 9) latencies.removeFirst()
            if (latencies.size >= 5) {
                val median = Stats.percentile(latencies.toList(), 0.5) ?: return
                main.post { if (_isHearingYourself.value) _deviceLatencyMs.value = median }
            }
        }

        fun close() {
            val t = track ?: return
            track = null
            runCatching { t.pause(); t.flush(); t.stop() }
            t.release()
            latencies.clear()
            ring.fill(0f)
        }
    }

    private fun burstFrames(): Int =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.takeIf { it > 0 } ?: 256

    private fun isSafeOutput(device: AudioDeviceInfo?): Boolean =
        device != null && device.type in headphoneTypes

    private fun stop() {
        running = false
        monitoring = false
        thread?.join(500)
        thread = null
        _isHearingYourself.value = false
        _isListening.value = false
        _pitch.value = null
        _resonance.value = null
        _quality.value = null
        _deviceLatencyMs.value = null
        _activeMicrophoneName.value = null
        resetNoise()
    }

    private fun fail(message: String) {
        running = false
        monitoring = false
        _isListening.value = false
        _isHearingYourself.value = false
        _lastError.value = message
    }

    /** Switches off gain control, noise suppression and echo cancelling if the phone attached them. */
    private fun disableEffects(session: Int): List<String> {
        val off = mutableListOf<String>()
        runCatching { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session)?.let { if (it.enabled) { it.enabled = false; off += "auto gain" } } }
        runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.let { if (it.enabled) { it.enabled = false; off += "noise suppression" } } }
        runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.let { if (it.enabled) { it.enabled = false; off += "echo cancelling" } } }
        return off
    }

    /** Same rule as iOS: noisy after 1.5 s of noise, quiet again after 3 s. */
    private fun updateNoise(floor: Double) {
        if (!running) return
        _noiseFloor.value = floor
        val noisyNow = floor > NOISY_FLOOR || (_quality.value?.let { it.level - floor < MINIMUM_VOICE_MARGIN } ?: false)
        val now = Instant.now()
        if (noisyNow) {
            quietSince = null
            if (noisySince == null) noisySince = now
            if (!_isNoisy.value && now.toEpochMilli() - noisySince!!.toEpochMilli() >= 1500) _isNoisy.value = true
        } else {
            noisySince = null
            if (quietSince == null) quietSince = now
            if (_isNoisy.value && now.toEpochMilli() - quietSince!!.toEpochMilli() >= 3000) _isNoisy.value = false
        }
    }

    private fun resetNoise() {
        _noiseFloor.value = null
        _isNoisy.value = false
        noisySince = null
        quietSince = null
    }

    private fun routeChanged() {
        refreshRoute()
        if (_isHearingYourself.value && !_headphonesConnected.value) {
            setHearYourself(false)
            _lastError.value = "Headphones disconnected, so hear-yourself was turned off."
        }
    }

    private fun refreshRoute() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in headphoneTypes }
        val ids = outputs.map { it.id }.toSet()
        if (ids != lastOutputs) { _bluetoothBannerDismissed.value = false; lastOutputs = ids }
        // Prefer wired if both are present: Android plays media through the cable then.
        val current = outputs.firstOrNull { it.type in wiredTypes } ?: outputs.firstOrNull()
        _headphonesConnected.value = current != null
        _outputIsBluetooth.value = current?.type in bluetoothTypes
        _outputName.value = current?.let { d ->
            d.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL }
                ?: if (d.type in bluetoothTypes) "Bluetooth headphones" else "Wired headphones"
        }
    }

    private fun micName(device: AudioDeviceInfo?): String? = when (device?.type) {
        null -> null
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> "Headset mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth mic"
        else -> device.productName?.toString()
    }

    private fun readVolume(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max else 0f
    }

    fun release() {
        stop()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        resolver.unregisterContentObserver(volumeObserver)
    }
}
