package com.thrivestorm2.voicemapper.core.analysis

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The long arc from new learner to fine tuner (docs/PROGRESSION.md). Port of
 * iOS `Analysis/Progression.swift`. Stages are suggested, never locked;
 * strain alerts and capped-out days hold a stage.
 */
enum class Stage(val number: Int, val title: String, val focus: String, val minimumDays: Int) {
    FOUNDATIONS(1, "Foundations", "Easy breathing and voicing, comfort and awareness.", 5),
    PITCH(2, "Pitch", "Find and hold notes, moving step by step from your baseline towards your goal.", 7),
    RESONANCE(3, "Resonance", "Change brightness and darkness without changing pitch.", 7),
    COMBINING(4, "Combining", "Pitch and resonance together, on the grid.", 7),
    SPEECH(5, "Speech", "Carry it into reading, phrases and intonation.", 7),
    FINE_TUNING(6, "Fine-tuning", "Vocal weight, consistency and keeping your voice steady over time.", 0);

    val next: Stage? get() = entries.firstOrNull { it.number == number + 1 }

    companion object {
        fun of(stored: Int): Stage = entries.firstOrNull { it.number == stored } ?: FOUNDATIONS
    }
}

object Progression {
    const val STAGE_KEY = "progression.stage"
    const val STAGE_START_KEY = "progression.stageStart"
    const val HOLD_STEP_KEY = "progression.holdStep"

    const val SAFETY_WINDOW_DAYS = 3
    const val PASSES_PER_STEP = 2
    const val PASSES_TO_MOVE_ON = 3
    const val RESONANCE_SECONDS_PER_DAY = 120.0
    const val ZONE_SHARE = 0.5
    const val ZONE_MINIMUM_SECONDS = 300.0
    const val DAYS_NEEDED = 3

    val resonanceExerciseIds = setOf("forwardback", "vowels")
    val combiningExerciseIds = setOf("hold", "vowels", "phrases")

    data class Criterion(val text: String, val met: Boolean)

    data class Input(
        val now: Instant = Instant.now(),
        val stage: Stage,
        /** Null = count from the first practice day. */
        val stageStart: Instant?,
        val sessions: List<PracticeSession>,
        val results: List<ExerciseResult>,
        val strainDates: List<Instant>,
        val baselineHz: Double?,
        val goal: GoalRange?,
        val resonanceGoal: ResonanceGoal?,
        val holdStep: Int,
        val capMinutes: Int,
        val zone: ZoneId = ZoneId.systemDefault(),
    ) {
        fun day(t: Instant): LocalDate = LocalDate.ofInstant(t, zone)
        fun startOfDay(d: LocalDate): Instant = d.atStartOfDay(zone).toInstant()
        val today: LocalDate get() = day(now)
    }

    data class Status(val criteria: List<Criterion>, val ready: Boolean)

    fun status(i: Input): Status {
        val c = criteria(i)
        return Status(c, i.stage.next != null && c.isNotEmpty() && c.all { it.met })
    }

    fun criteria(i: Input): List<Criterion> {
        val list = mutableListOf<Criterion>()
        val days = practiceDays(i, i.stageStart)

        when (i.stage) {
            Stage.FOUNDATIONS -> {
                list += Criterion("Practised on ${minOf(days.size, 5)} of 5 days", days.size >= 5)
                val warmDays = dayCount(i, i.results.filter { it.exerciseId == Exercise.WARM_UP_ID && it.passed && within(i, it.date) })
                val most = days.isNotEmpty() && warmDays >= days.size * 0.6
                list += Criterion(
                    if (days.isEmpty()) "Warm-up on most practice days" else "Warm-up on most practice days ($warmDays of ${days.size})",
                    most && warmDays >= 3)
                list += Criterion(if (i.baselineHz == null) "Record your baseline (Settings)" else "Baseline recorded", i.baselineHz != null)
            }
            Stage.PITCH -> {
                list += minimumDays(i, days)
                val final = finalHoldStep(i.goal, i.baselineHz)
                val step = currentHoldStep(i.holdStep, i.goal, i.baselineHz)
                if (i.goal != null) {
                    list += Criterion(
                        if (step == final) "Hold target has reached your goal (${Note(final).name})"
                        else "Hold target stepping towards your goal (${Note(step).name} → ${Note(final).name})",
                        step == final)
                }
                val passes = holdPassDays(i, step)
                list += Criterion("Hold drill passed on ${minOf(passes, PASSES_TO_MOVE_ON)} of $PASSES_TO_MOVE_ON days at ${Note(step).name}",
                    passes >= PASSES_TO_MOVE_ON)
            }
            Stage.RESONANCE -> {
                list += minimumDays(i, days)
                val band = i.resonanceGoal
                if (band != null) {
                    val n = resonanceDays(i, band)
                    list += Criterion("2+ minutes inside your resonance goal in one session, on ${minOf(n, DAYS_NEEDED)} of $DAYS_NEEDED days",
                        n >= DAYS_NEEDED)
                } else {
                    val n = exerciseDays(i, resonanceExerciseIds)
                    list += Criterion("Resonance exercises on ${minOf(n, DAYS_NEEDED)} of $DAYS_NEEDED days", n >= DAYS_NEEDED)
                }
            }
            Stage.COMBINING -> {
                list += minimumDays(i, days)
                val share = zoneShareLastWeek(i)
                if (share != null) {
                    list += Criterion("Time inside your goal zone this week: ${swiftRound(share * 100)}% (aim for 50%)", share >= ZONE_SHARE)
                } else if (i.goal == null && i.resonanceGoal == null) {
                    val n = exerciseDays(i, combiningExerciseIds)
                    list += Criterion("Combining exercises on ${minOf(n, DAYS_NEEDED)} of $DAYS_NEEDED days", n >= DAYS_NEEDED)
                } else {
                    list += Criterion("Practise 5+ minutes this week so the heat map can measure time in your goal zone", false)
                }
            }
            Stage.SPEECH -> {
                list += minimumDays(i, days)
                val readings = i.results.filter { it.exerciseId == Exercise.READING_ID && it.passed && within(i, it.date) }
                val goal = i.goal
                if (goal != null) {
                    val inGoal = readings.filter { r -> r.medianHz?.let { goal.contains(Note.fromFrequency(it).midi) } ?: false }
                    val n = dayCount(i, inGoal)
                    list += Criterion("Reading with its median pitch in your goal range on ${minOf(n, DAYS_NEEDED)} of $DAYS_NEEDED days",
                        n >= DAYS_NEEDED)
                } else {
                    val n = dayCount(i, readings)
                    list += Criterion("Reading on ${minOf(n, DAYS_NEEDED)} of $DAYS_NEEDED days", n >= DAYS_NEEDED)
                }
                val n = exerciseDays(i, setOf(Exercise.INTONATION_ID))
                list += Criterion("Intonation exercise on ${minOf(n, DAYS_NEEDED)} of $DAYS_NEEDED days", n >= DAYS_NEEDED)
            }
            Stage.FINE_TUNING -> {
                val start = i.startOfDay(i.today.minusDays(27))
                val month = practiceDays(i, start).size
                list += Criterion("Practised on $month of the last 28 days", month >= 12)
            }
        }
        list += safety(i)
        return list
    }

    fun safety(i: Input): List<Criterion> {
        val since = i.startOfDay(i.today.minusDays((SAFETY_WINDOW_DAYS - 1).toLong()))
        val strain = i.strainDates.any { it >= since }
        val perDay = HashMap<LocalDate, Double>()
        for (s in i.sessions) if (s.start >= since) perDay.merge(i.day(s.start), s.duration, Double::plus)
        val capped = perDay.values.any { it >= i.capMinutes * 60.0 }
        return listOf(
            Criterion("No strain alerts in the last $SAFETY_WINDOW_DAYS days", !strain),
            Criterion("Stayed under your daily cap in the last $SAFETY_WINDOW_DAYS days", !capped),
        )
    }

    // Hold steps (stage 2)

    fun firstHoldStep(goal: GoalRange?, baselineHz: Double?): Int {
        if (baselineHz != null) return Note.fromFrequency(baselineHz).midi
        if (goal != null) return (goal.low + goal.high) / 2
        return 57
    }

    fun finalHoldStep(goal: GoalRange?, baselineHz: Double?): Int {
        if (goal != null) return (goal.low + goal.high) / 2
        return firstHoldStep(goal, baselineHz)
    }

    fun currentHoldStep(stored: Int, goal: GoalRange?, baselineHz: Double?): Int {
        val first = firstHoldStep(goal, baselineHz)
        if (stored <= 0) return first
        val final = finalHoldStep(goal, baselineHz)
        return stored.coerceIn(minOf(first, final), maxOf(first, final))
    }

    fun nextHoldStep(i: Input): Int {
        val step = currentHoldStep(i.holdStep, i.goal, i.baselineHz)
        val final = finalHoldStep(i.goal, i.baselineHz)
        if (step == final || holdPassDays(i, step) < PASSES_PER_STEP) return step
        return step + if (final > step) 1 else -1
    }

    fun holdPassDays(i: Input, step: Int): Int = dayCount(i, i.results.filter {
        it.exerciseId == Exercise.HOLD_ID && it.passed && it.targetMidi == step && within(i, it.date)
    })

    // Measures

    fun practiceDays(i: Input, since: Instant?): Set<LocalDate> {
        val from = since?.let { i.startOfDay(i.day(it)) } ?: Instant.MIN
        val days = i.sessions.filter { it.start >= from }.map { i.day(it.start) }.toMutableSet()
        days += i.results.filter { it.passed && it.date >= from }.map { i.day(it.date) }
        return days
    }

    private fun minimumDays(i: Input, days: Set<LocalDate>): Criterion {
        val need = i.stage.minimumDays
        return Criterion("Practised on ${minOf(days.size, need)} of $need days in this stage", days.size >= need)
    }

    private fun within(i: Input, date: Instant): Boolean {
        val start = i.stageStart ?: return true
        return date >= i.startOfDay(i.day(start))
    }

    private fun dayCount(i: Input, results: List<ExerciseResult>) = results.map { i.day(it.date) }.toSet().size

    private fun exerciseDays(i: Input, ids: Set<String>) =
        dayCount(i, i.results.filter { it.exerciseId in ids && it.passed && within(i, it.date) })

    private fun resonanceDays(i: Input, band: ResonanceGoal): Int =
        i.sessions.filter { within(i, it.start) }
            .filter { s -> s.cells.filter { inBand(it.resonanceBin, band) }.sumOf { it.seconds } >= RESONANCE_SECONDS_PER_DAY }
            .map { i.day(it.start) }.toSet().size

    fun zoneShareLastWeek(i: Input): Double? {
        if (i.goal == null && i.resonanceGoal == null) return null
        val since = i.startOfDay(i.today.minusDays(6))
        var total = 0.0
        var inside = 0.0
        for (s in i.sessions) {
            if (s.start < since) continue
            for (c in s.cells) {
                total += c.seconds
                val pitchOk = i.goal?.contains(c.pitchMidi) ?: true
                val resOk = i.resonanceGoal?.let { inBand(c.resonanceBin, it) } ?: true
                if (pitchOk && resOk) inside += c.seconds
            }
        }
        return if (total >= ZONE_MINIMUM_SECONDS) inside / total else null
    }

    private fun inBand(bin: Int, band: ResonanceGoal): Boolean {
        val centre = (bin + 0.5) * GridCell.RESONANCE_BIN_WIDTH
        return centre >= band.low && centre <= band.high
    }

    // History markers

    data class StageChange(val date: Instant, val stage: Stage)

    fun stageChanges(results: List<ExerciseResult>): List<StageChange> {
        val changes = mutableListOf<StageChange>()
        var current = Stage.FOUNDATIONS
        for (r in results.sortedBy { it.date }) {
            val s = Stage.of(r.stage)
            if (s != current) { changes += StageChange(r.date, s); current = s }
        }
        return changes
    }

    // Today's plan

    data class PlanItem(val exercise: Exercise, val role: Role) {
        enum class Role { WARM_UP, STAGE, REVIEW, COOL_DOWN }
    }

    data class Plan(val items: List<PlanItem>, val trimmed: Boolean) {
        val minutes: Int get() = items.sumOf { it.exercise.minutes }
    }

    /** Days since 1 Jan 1 AD, 1-based — what iOS `Calendar.ordinality(of: .day, in: .era)` returns. */
    private fun ordinalDay(day: LocalDate) = day.toEpochDay() + 719_163

    /** Warm-up → up to 3 stage exercises → 1 review → cool-down, cut to fit [remainingMinutes]. */
    fun plan(stage: Stage, remainingMinutes: Int, day: LocalDate = LocalDate.now()): Plan {
        val pool = Exercise.all.filter { !it.isWarmUp && !it.isCoolDown }
        val core = pool.filter { stage.number in it.stages }.take(3).toMutableList()
        val earlier = pool.filter { stage.number !in it.stages && (it.stages.minOrNull() ?: 99) < stage.number }
        var review: Exercise? = if (earlier.isEmpty()) null else earlier[(ordinalDay(day) % earlier.size).toInt()]

        val warm = Exercise.with(Exercise.WARM_UP_ID) ?: return Plan(emptyList(), false)
        val cool = Exercise.with(Exercise.COOL_DOWN_ID) ?: return Plan(emptyList(), false)
        val fixed = warm.minutes + cool.minutes
        fun total() = fixed + core.sumOf { it.minutes } + (review?.minutes ?: 0)

        var trimmed = false
        while (total() > remainingMinutes && (review != null || core.size > 1)) {
            trimmed = true
            if (review != null) review = null else core.removeAt(core.lastIndex)
        }
        if (total() > remainingMinutes) return Plan(emptyList(), true)
        val items = mutableListOf(PlanItem(warm, PlanItem.Role.WARM_UP))
        items += core.map { PlanItem(it, PlanItem.Role.STAGE) }
        review?.let { items += PlanItem(it, PlanItem.Role.REVIEW) }
        items += PlanItem(cool, PlanItem.Role.COOL_DOWN)
        return Plan(items, trimmed)
    }
}
