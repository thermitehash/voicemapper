package com.thrivestorm2.voicemapper.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thrivestorm2.voicemapper.core.analysis.Baseline
import com.thrivestorm2.voicemapper.core.analysis.ExerciseResult
import com.thrivestorm2.voicemapper.core.analysis.GoalRange
import com.thrivestorm2.voicemapper.core.analysis.HistoryPeriod
import com.thrivestorm2.voicemapper.core.analysis.InstantMillis
import com.thrivestorm2.voicemapper.core.analysis.PracticeSession
import com.thrivestorm2.voicemapper.core.analysis.ResonanceGoal
import com.thrivestorm2.voicemapper.core.analysis.StrainGuard
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Everything the app keeps, in one JSON file in app-private storage (the
 * counterpart of the iOS SwiftData store). Only summaries — never audio.
 * Excluded from cloud backup and device transfer (see data_extraction_rules).
 */
@Serializable
data class StoreData(
    val sessions: List<PracticeSession> = emptyList(),
    val baseline: Baseline? = null,
    val results: List<ExerciseResult> = emptyList(),
    val strainEvents: List<@Serializable(with = InstantMillis::class) Instant> = emptyList(),
)

class Store(context: Context) {
    private val file = File(context.filesDir, "voicemapper.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    var data by mutableStateOf(load())
        private set

    private fun load(): StoreData = runCatching {
        if (file.exists()) json.decodeFromString(StoreData.serializer(), file.readText()) else StoreData()
    }.getOrElse { StoreData() }

    private fun save(new: StoreData) {
        data = new
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(StoreData.serializer(), new))
        tmp.renameTo(file)
    }

    private var nextId: Long = (data.sessions.maxOfOrNull { it.id } ?: 0) + (data.results.maxOfOrNull { it.id } ?: 0) + 1

    fun addSession(s: PracticeSession): PracticeSession {
        val withId = s.copy(id = nextId++)
        save(data.copy(sessions = data.sessions + withId))
        return withId
    }

    fun deleteSession(id: Long) = save(data.copy(sessions = data.sessions.filterNot { it.id == id }))
    fun setBaseline(b: Baseline) = save(data.copy(baseline = b))
    fun addResult(r: ExerciseResult) = save(data.copy(results = data.results + r.copy(id = nextId++)))
    fun addStrainEvent(at: Instant) = save(data.copy(strainEvents = data.strainEvents + at))
    fun deleteAll() { save(StoreData()); file.delete() }

    /** Readable export for Settings ▸ Export (the user chooses where it goes). */
    fun exportJson(): String = pretty.encodeToString(StoreData.serializer(), data)
    private val pretty = Json { prettyPrint = true }
}

/**
 * Settings, under the same keys iOS uses with `@AppStorage`. Each value is
 * Compose state, so screens update when it changes.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("voicemapper", Context.MODE_PRIVATE)

    var goalLow by intPref(GOAL_LOW, 0)
    var goalHigh by intPref(GOAL_HIGH, 0)
    var resonanceLow by doublePref(RES_LOW, 0.0)
    var resonanceHigh by doublePref(RES_HIGH, 0.0)
    var strainAlerts by boolPref(StrainGuard.ALERTS_KEY, true)
    var capMinutes by intPref(StrainGuard.CAP_KEY, StrainGuard.DEFAULT_CAP_MINUTES)
    var colourBlind by boolPref("display.colourBlindPalette", false)
    var onboardingDone by boolPref("onboarding.done", false)
    var appLock by boolPref("security.appLock", false)
    var stage by intPref("progression.stage", 1)
    var stageStart by doublePref("progression.stageStart", 0.0)
    var holdStep by intPref("progression.holdStep", 0)
    var doneToday by stringPref("exercises.doneToday", "")
    var showOverlay by boolPref("showHistoryOverlay", false)
    var showHeat by boolPref("showHeatMap", false)
    var historyPeriod by stringPref("historyPeriod", HistoryPeriod.WEEK.name)

    val goal: GoalRange? get() = GoalRange.of(goalLow, goalHigh)
    val resonanceGoal: ResonanceGoal? get() = ResonanceGoal.of(resonanceLow, resonanceHigh)
    val stageStartInstant: Instant? get() = if (stageStart > 0) Instant.ofEpochMilli((stageStart * 1000).toLong()) else null

    fun clearAll() {
        sp.edit().clear().apply()
        goalLow = 0; goalHigh = 0; resonanceLow = 0.0; resonanceHigh = 0.0
        strainAlerts = true; capMinutes = StrainGuard.DEFAULT_CAP_MINUTES; colourBlind = false
        onboardingDone = false; appLock = false; stage = 1; stageStart = 0.0; holdStep = 0; doneToday = ""
        showOverlay = false; showHeat = false; historyPeriod = HistoryPeriod.WEEK.name
    }

    companion object {
        const val GOAL_LOW = "goalLowMidi"
        const val GOAL_HIGH = "goalHighMidi"
        const val RES_LOW = "goalResonanceLow"
        const val RES_HIGH = "goalResonanceHigh"
    }

    private inner class Pref<T>(private val key: String, initial: T, private val write: SharedPreferences.Editor.(String, T) -> Unit) {
        private val state = mutableStateOf(initial)
        operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = state.value
        operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
            state.value = value
            sp.edit().apply { write(key, value) }.apply()
        }
    }

    private fun intPref(key: String, def: Int) = Pref(key, sp.getInt(key, def)) { k, v -> putInt(k, v) }
    private fun boolPref(key: String, def: Boolean) = Pref(key, sp.getBoolean(key, def)) { k, v -> putBoolean(k, v) }
    private fun stringPref(key: String, def: String) = Pref(key, sp.getString(key, def) ?: def) { k, v -> putString(k, v) }
    private fun doublePref(key: String, def: Double) =
        Pref(key, Double.fromBits(sp.getLong(key, def.toRawBits()))) { k, v -> putLong(k, v.toRawBits()) }
}
