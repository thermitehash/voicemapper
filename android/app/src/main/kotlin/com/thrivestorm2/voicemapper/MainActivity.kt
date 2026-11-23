package com.thrivestorm2.voicemapper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.thrivestorm2.voicemapper.ui.AppRoot

/**
 * Single activity. FragmentActivity (not ComponentActivity) because the
 * biometric prompt for the app lock needs it.
 */
class MainActivity : FragmentActivity() {
    private val model: VoiceModel by viewModels()
    private var afterPermission: (() -> Unit)? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterPermission?.invoke()
        afterPermission = null
        micDenied = !granted
    }

    /** True after the user turned down the mic; the UI explains where to turn it on. */
    var micDenied = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            // Like iOS on backgrounding: stop capture, save the open session, lock if enabled.
            override fun onStop(owner: LifecycleOwner) {
                model.stopAudio()
                model.finish()
                model.lockIfEnabled()
            }
        })
        model.authenticate = ::authenticate
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        // Debug builds only, for emulator screenshots (like iOS -debugTab):
        //   adb shell am start -n …/.MainActivity --es debugTab history --ez onboardingDone true --ez debugSeed true
        var initialTab: String? = null
        if (debuggable && savedInstanceState == null) {
            intent.getStringExtra("debugTab")?.let { initialTab = it }
            if (intent.hasExtra("onboardingDone")) model.prefs.onboardingDone = intent.getBooleanExtra("onboardingDone", true)
            if (intent.getBooleanExtra("debugSeed", false)) DebugSeed.seed(model)
            intent.getStringExtra("debugGoal")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 2 }?.let {
                model.prefs.goalLow = it[0]; model.prefs.goalHigh = it[1]
            }
            if (intent.getBooleanExtra("debugReading", false)) model.audio.debugShowReading(
                com.thrivestorm2.voicemapper.core.dsp.PitchReading(196.0, com.thrivestorm2.voicemapper.core.analysis.Note.fromFrequency(196.0)),
                com.thrivestorm2.voicemapper.core.dsp.ResonanceReading(62.0, 1150.0, listOf(600.0, 1750.0, 2900.0)),
                com.thrivestorm2.voicemapper.core.dsp.VoiceQuality(8.0, -20.0, 18.0, 20.0, 0.6, 3.5, -24.0))
            intent.getStringExtra("debugExercise")?.let { id -> model.runningExercise = com.thrivestorm2.voicemapper.core.analysis.Exercise.with(id) }
        }
        setContent { AppRoot(model, ::withMicPermission, initialTab) }
    }

    private fun authenticate(reason: String, done: (Boolean, String?) -> Unit) {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true, null)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done(false, errString.toString())
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(reason)
            .setAllowedAuthenticators(VoiceModel.LOCK_AUTHENTICATORS)
            .build())
    }

    /** Runs [action] once RECORD_AUDIO is granted, asking first if needed. */
    fun withMicPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            afterPermission = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
