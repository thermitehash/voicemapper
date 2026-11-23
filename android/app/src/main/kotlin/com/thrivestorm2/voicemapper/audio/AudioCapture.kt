package com.thrivestorm2.voicemapper.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
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
 * Mic capture for analysis. Android counterpart of iOS `AudioEngine` (the
 * capture half; hear-yourself comes later). Runs the same trackers on a
 * dedicated audio thread and publishes readings as StateFlows.
 *
 * Phones clean up the mic signal (gain control, noise suppression, echo
 * cancelling) and that skews pitch, formants and the health measures, so we
 * ask for the UNPROCESSED source where the phone supports it, else
 * VOICE_RECOGNITION (which Android's compatibility rules require to be close
 * to unprocessed), and switch off any effects still attached.
 */
class AudioCapture(context: Context) {
    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 48_000
        private const val BLOCK = 1024
        /** A room louder than this is noisy whatever the voice level … */
        const val NOISY_FLOOR = -48.0
        /** … and the voice should be at least this far above the room. */
        const val MINIMUM_VOICE_MARGIN = 18.0
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    /** Debug builds only: show a made-up reading for store screenshots (the emulator has no voice input). */
    fun debugShowReading(pitch: PitchReading, resonance: ResonanceReading, quality: VoiceQuality) {
        _pitch.value = pitch; _resonance.value = resonance; _quality.value = quality; _isListening.value = true
    }

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    /** Which mic source the phone gave us, for Settings ▸ About this phone. */
    private val _sourceDescription = MutableStateFlow("")
    val sourceDescription: StateFlow<String> = _sourceDescription.asStateFlow()
    private val _headphonesConnected = MutableStateFlow(false)
    val headphonesConnected: StateFlow<Boolean> = _headphonesConnected.asStateFlow()
    private val _outputIsBluetooth = MutableStateFlow(false)
    val outputIsBluetooth: StateFlow<Boolean> = _outputIsBluetooth.asStateFlow()

    @Volatile private var running = false
    private var thread: Thread? = null
    private var noisySince: Instant? = null
    private var quietSince: Instant? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshRoute()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refreshRoute()
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, main)
        refreshRoute()
    }

    fun setListening(on: Boolean) {
        if (on == running) return
        if (on) start() else stop()
    }

    @SuppressLint("MissingPermission") // The UI asks for RECORD_AUDIO before calling this.
    private fun start() {
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuffer <= 0) { fail("This phone's microphone doesn't support 48 kHz float capture."); return }
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
            fail("Couldn't open the microphone: ${e.message}"); return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); fail("Couldn't open the microphone."); return }

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
        _isListening.value = true
        _lastError.value = null
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = FloatArray(BLOCK)
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(buffer, 0, BLOCK, AudioRecord.READ_BLOCKING)
                    if (n <= 0) { if (n < 0) Log.w(TAG, "read error $n"); continue }
                    pitchTracker.process(buffer, n)
                    val f0 = pitchTracker.currentFrequency
                    resonanceTracker.process(buffer, n, f0)
                    qualityTracker.process(buffer, n, f0)
                    noiseMeter.process(buffer, n)
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture failed", e)
                main.post { fail("The microphone stopped: ${e.message}") }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }, "VoiceMapper-audio").also { it.start() }
    }

    private fun stop() {
        running = false
        thread?.join(500)
        thread = null
        _isListening.value = false
        _pitch.value = null
        _resonance.value = null
        _quality.value = null
        _noiseFloor.value = null
        _isNoisy.value = false
        noisySince = null
        quietSince = null
    }

    private fun fail(message: String) {
        running = false
        _isListening.value = false
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

    private fun refreshRoute() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val wired = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET)
        val bluetooth = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP); add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (android.os.Build.VERSION.SDK_INT >= 31) { add(AudioDeviceInfo.TYPE_BLE_HEADSET) }
        }
        _outputIsBluetooth.value = outputs.any { it.type in bluetooth }
        _headphonesConnected.value = outputs.any { it.type in wired || it.type in bluetooth }
    }

    fun release() {
        stop()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }
}
