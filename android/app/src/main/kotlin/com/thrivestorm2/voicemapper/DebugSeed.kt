package com.thrivestorm2.voicemapper

import com.thrivestorm2.voicemapper.core.analysis.Baseline
import com.thrivestorm2.voicemapper.core.analysis.ExerciseResult
import com.thrivestorm2.voicemapper.core.analysis.GridCell
import com.thrivestorm2.voicemapper.core.analysis.PracticeSession
import com.thrivestorm2.voicemapper.core.analysis.QualitySummary
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.pow

/** Debug builds only: fills History with two weeks of made-up sessions for screenshots. */
object DebugSeed {
    fun seed(model: VoiceModel) {
        if (model.store.data.sessions.isNotEmpty()) return
        val now = Instant.now()
        model.store.setBaseline(Baseline(now.minus(14, ChronoUnit.DAYS), 125.0, 35.0,
            QualitySummary(18.0, 20.0, 0.6, 3.5, -28.0, 0.5)))
        for (d in 13 downTo 0) {
            val t = now.minus(d.toLong(), ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS)
            val progress = (13 - d) / 13.0
            val midi = 47 + (progress * 7).toInt()
            val res = 35 + progress * 30
            val hz = 440 * 2.0.pow((midi - 69) / 12.0)
            val cells = (-2..2).flatMap { dm -> (-1..1).map { db -> GridCell(midi + dm, ((res / 5).toInt() + db).coerceIn(0, 19), 20.0 / (1 + dm * dm + db * db)) } }
            model.store.addSession(PracticeSession(t, 900.0, 420.0, hz, hz * 0.9, hz * 1.12, res,
                QualitySummary(18.0, 20.0, 0.6, 3.5, -28.0, 0.55 - progress * 0.2), if (d == 3) 0.35 else 0.05, cells))
            model.store.addResult(ExerciseResult(t, "warmup", if (d > 7) 1 else 2, true, 60.0, 0))
        }
    }
}
