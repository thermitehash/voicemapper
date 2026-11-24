package com.thrivestorm2.voicemapper.core

import com.thrivestorm2.voicemapper.core.analysis.*
import com.thrivestorm2.voicemapper.core.dsp.FormantAnalyzer
import com.thrivestorm2.voicemapper.core.dsp.VoiceQuality
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.pow

/** Port of `Tools/MappingTests/main.swift` (S15): same groups, same expected values. */
class MappingTest {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val now: Instant = LocalDateTime.of(2026, 6, 15, 18, 0).atZone(zone).toInstant()
    private fun daysAgo(n: Int, hour: Int = 18): Instant =
        LocalDateTime.of(2026, 6, 15, hour, 0).minusDays(n.toLong()).atZone(zone).toInstant()

    private val c = Checks("Mapping tests")

    @Test
    fun mappingSuite() {
        testNote(); testZones(); testResonance(); testHeatmap(); testGoals()
        testStrain(); testExerciseLog(); testProgression(); testExercises()
        testRegionalSpelling(c)
        c.finish()
    }

    private fun testNote() {
        c.group("Note Hz ↔ MIDI ↔ name")
        c.expect(Note.fromFrequency(440.0).midi == 69, "440 Hz → MIDI 69")
        c.expect(Note(69).name == "A4", "MIDI 69 → A4")
        c.near(Note(69).frequency, 440.0, 1e-9, "A4 Hz")
        c.near(Note(60).frequency, 261.63, 0.005, "C4 Hz")
        c.expect(Note(60).name == "C4", "MIDI 60 → C4")
        c.expect(Note(61).name == "C♯4", "MIDI 61 → C♯4")
        c.expect(Note(59).name == "B3", "MIDI 59 → B3")
        c.expect(Note(21).name == "A0", "MIDI 21 → A0")
        for (m in 24..96) c.expect(Note.fromFrequency(Note(m).frequency).midi == m, "round trip MIDI $m")
        c.expect(Note.fromFrequency(440 * 2.0.pow(0.49 / 12)).midi == 69, "+0.49 st rounds to A4")
        c.expect(Note.fromFrequency(440 * 2.0.pow(0.51 / 12)).midi == 70, "+0.51 st rounds to A♯4")
        c.expect(Note.fromFrequency(440 * 2.0.pow(-0.49 / 12)).midi == 69, "−0.49 st rounds to A4")
        c.near(Note.midiValue(880.0), 81.0, 1e-9, "880 Hz midiValue")
    }

    private fun testZones() {
        c.group("PerceivedGender zone boundaries (145 / 175 Hz)")
        c.expect(PerceivedGender.of(144.99) == PerceivedGender.MALE, "144.99 Hz → male")
        c.expect(PerceivedGender.of(145.0) == PerceivedGender.AMBIGUOUS, "145 Hz → ambiguous")
        c.expect(PerceivedGender.of(174.99) == PerceivedGender.AMBIGUOUS, "174.99 Hz → ambiguous")
        c.expect(PerceivedGender.of(175.0) == PerceivedGender.FEMALE, "175 Hz → female")
        for (z in PerceivedGender.entries) {
            c.expect(PerceivedGender.of(z.typicalFrequency) == z, "$z typical pitch inside its zone")
            c.near(z.commonness(z.typicalFrequency), 1.0, 1e-9, "$z commonness peak")
            c.near(z.resonanceCommonness(z.typicalResonance), 1.0, 1e-9, "$z resonance commonness peak")
        }

        c.group("GoalPreset ranges stay inside their zones")
        val titles = GoalPreset.entries.map { it.title }
        c.expect(titles == titles.sorted(), "presets listed alphabetically: $titles")
        for (p in GoalPreset.entries) {
            val r = p.range
            c.expect(r.low <= r.high, "${p.title} range ordered")
            for (m in r.low..r.high) c.expect(PerceivedGender.of(Note(m).frequency) == p.zone, "${p.title}: ${Note(m).name} inside ${p.zone}")
            c.expect(p.resonance.low >= 0 && p.resonance.high <= 100, "${p.title} resonance within 0–100")
            c.expect(p.resonance.low <= p.zone.typicalResonance && p.zone.typicalResonance <= p.resonance.high,
                "${p.title} resonance band contains the zone's typical score")
            c.expect(GoalPreset.matching(r.low, r.high, p.resonance.low, p.resonance.high) == p, "${p.title} matching round trip")
        }
        c.expect(GoalPreset.label(0, 0, 0.0, 0.0) == "Not set", "unset label")
        c.expect(GoalPreset.label(50, 52, 0.0, 0.0) == "Custom", "custom label")
    }

    private fun testResonance() {
        c.group("ResonanceScorer endpoints")
        c.near(ResonanceScorer.score(900.0), 0.0, 1e-9, "ΔF 900 → 0")
        c.near(ResonanceScorer.score(1300.0), 100.0, 1e-9, "ΔF 1300 → 100")
        c.near(ResonanceScorer.score(1100.0), 50.0, 1e-9, "ΔF 1100 → 50")
        c.near(ResonanceScorer.score(700.0), 0.0, 1e-9, "clamped below")
        c.near(ResonanceScorer.score(1600.0), 100.0, 1e-9, "clamped above")
        val tube = listOf(500.0, 1500.0, 2500.0, 3500.0).map { FormantAnalyzer.Formant(it, 80.0) }
        c.near(ResonanceScorer.spacing(tube), 1000.0, 1e-6, "uniform tube ΔF")
        c.near(ResonanceScorer.tractLength(1000.0), 17.5, 1e-9, "tract length")
        c.expect(ResonanceScorer.spacing(tube.take(2)) == null, "needs 3+ formants")
        val wideF1 = listOf(FormantAnalyzer.Formant(500.0, 600.0)) + tube.drop(1)
        c.expect(ResonanceScorer.assigned(wideF1).firstOrNull() != 500.0, "very wide F1 skipped")

        c.group("GridCell.bin edges")
        val cases = listOf(-5.0 to 0, 0.0 to 0, 4.999 to 0, 5.0 to 1, 50.0 to 10, 99.999 to 19, 100.0 to 19, 150.0 to 19)
        for ((score, bin) in cases) c.expect(GridCell.bin(score) == bin, "score $score → bin $bin, got ${GridCell.bin(score)}")
    }

    private fun session(start: Instant, duration: Double = 300.0, medianHz: Double = 180.0, resonance: Double? = 60.0,
                        cells: List<Triple<Int, Int, Double>> = emptyList()) =
        PracticeSession(start, duration, duration / 2, medianHz, medianHz * 0.9, medianHz * 1.1, resonance,
            cells = cells.map { GridCell(it.first, it.second, it.third) })

    private fun testHeatmap() {
        c.group("HeatmapBinner totals and periods")
        val a = session(daysAgo(0), cells = listOf(Triple(57, 10, 30.0), Triple(58, 10, 20.0)))
        val b = session(daysAgo(1), cells = listOf(Triple(57, 10, 15.0), Triple(60, 12, 5.0)))
        val cells = HeatmapBinner.cells(listOf(a, b))
        c.near(cells.sumOf { it.seconds }, 70.0, 1e-9, "total seconds preserved")
        c.near(cells.firstOrNull { it.midi == 57 && it.bin == 10 }?.seconds, 45.0, 1e-9, "same cell merged across sessions")
        c.expect(cells.size == 3, "3 distinct cells, got ${cells.size}")
        val all = listOf(a, b, session(daysAgo(8)), session(daysAgo(40)))
        c.expect(HeatmapBinner.sessions(all, HistoryPeriod.TODAY, now, zone).size == 1, "today: 1 session")
        c.expect(HeatmapBinner.sessions(all, HistoryPeriod.WEEK, now, zone).size == 2, "week: 2 sessions")
        c.expect(HeatmapBinner.sessions(all, HistoryPeriod.MONTH, now, zone).size == 3, "month: 3 sessions")
        c.expect(HeatmapBinner.sessions(all, HistoryPeriod.ALL, now, zone).size == 4, "all: 4 sessions")
        val points = HeatmapBinner.points(listOf(b, session(daysAgo(2), resonance = null), a))
        c.expect(points.size == 2, "points skip sessions without resonance")
        c.expect(points.firstOrNull()?.date == b.start, "points sorted by date")
    }

    private fun testGoals() {
        c.group("GoalRange closeness and direction")
        val g = GoalRange.of(55, 59)!!
        fun hz(m: Double) = 440 * 2.0.pow((m - 69) / 12)
        c.near(g.closeness(hz(57.0)), 1.0, 1e-9, "inside → 1")
        c.near(g.closeness(hz(55.0)), 1.0, 1e-9, "at low edge → 1")
        c.near(g.closeness(hz(52.0)), 0.5, 1e-9, "3 st below → 0.5")
        c.near(g.closeness(hz(62.0)), 0.5, 1e-9, "3 st above → 0.5")
        c.near(g.closeness(hz(49.0)), 0.0, 1e-9, "6 st below → 0")
        c.near(g.closeness(hz(40.0)), 0.0, 1e-9, "far below → 0")
        c.expect(g.direction(hz(54.0)) == GoalRange.Direction.HIGHER, "1 st below → higher")
        c.expect(g.direction(hz(54.6)) == null, "within half a semitone → no nudge")
        c.expect(g.direction(hz(57.0)) == null, "inside → nil")
        c.expect(g.direction(hz(60.0)) == GoalRange.Direction.LOWER, "1 st above → lower")
        c.expect(GoalRange.of(0, 5) == null, "unset low → nil")
        c.expect(GoalRange.of(60, 59) == null, "inverted → nil")
        c.expect(g.contains(55) && g.contains(59) && !g.contains(60), "contains is inclusive")

        c.group("ResonanceGoal")
        c.expect(ResonanceGoal.of(50.0, 53.0) == null, "narrower than 4 → nil")
        val r = ResonanceGoal.of(40.0, 60.0)!!
        c.expect(r.direction(30.0) == ResonanceGoal.Direction.BRIGHTER && r.direction(70.0) == ResonanceGoal.Direction.DARKER &&
            r.direction(50.0) == null, "direction")
        val clamped = ResonanceGoal.of(-10.0, 120.0)!!
        c.expect(clamped.low == 0.0 && clamped.high == 100.0, "clamped to 0–100")
    }

    private fun quality(hnr: Double = 20.0, cpp: Double = 20.0, jitter: Double = 0.5, shimmer: Double = 3.0, level: Double = -30.0) =
        VoiceQuality(4.0, -18.0, cpp, hnr, jitter, shimmer, level)

    /** Feeds [seconds] of 5 Hz ticks; returns the first alert and when it came. */
    private fun feed(g: StrainGuard, seconds: Double, start: Instant, pitch: Double = 180.0, q: VoiceQuality?,
                     noisy: Boolean = false): Pair<StrainGuard.Alert?, Double> {
        var t = 0.0
        while (t < seconds) {
            g.observe(0.2, pitch, q, noisy, start.plusNanos((t * 1e9).toLong()))
            g.alert?.let { return it to t }
            t += 0.2
        }
        return null to t
    }

    private fun testStrain() {
        var alertsOn = true
        fun guard() = StrainGuard(alertsEnabled = { alertsOn }, capMinutesSetting = { 30 }, zone = zone)
        val baseline = QualitySummary.of(List(50) { quality() })
        val start = now

        c.group("StrainGuard rules on scripted inputs")
        run {
            val g = guard(); g.beginSession(0.0, baseline, 300.0, start)
            val (alert, _) = feed(g, 120.0, start, q = quality())
            c.expect(alert == null, "steady voice at baseline: no alert, got $alert")
            c.expect(g.health == StrainGuard.Health.STEADY, "steady voice → health steady, got ${g.health}")
        }
        run {
            val g = guard(); g.beginSession(0.0, baseline, 300.0, start)
            val (alert, t) = feed(g, 60.0, start, q = quality(hnr = 12.0, cpp = 12.0, jitter = 2.0))
            if (alert is StrainGuard.Alert.Strain) {
                c.expect(alert.reasons.size >= 2, "noisier tone + unsteady pitch → 2+ reasons: ${alert.reasons}")
                c.expect(t in 9.6..12.0, "alert after ~10 s of voice, got $t s")
            } else c.expect(false, "two measures off → strain alert, got $alert")
        }
        run {
            val g = guard(); g.beginSession(0.0, baseline, 300.0, start)
            val (alert, _) = feed(g, 60.0, start, q = quality(hnr = 12.0))
            c.expect(alert == null, "one measure off → no alert")
            c.expect(g.health == StrainGuard.Health.WATCH, "one measure off → health watch, got ${g.health}")
        }
        run {
            val g = guard(); g.beginSession(0.0, baseline, 200.0, start)
            val (alert, _) = feed(g, 90.0, start, pitch = 250.0, q = quality())
            if (alert is StrainGuard.Alert.Strain) c.expect(alert.reasons.any { "top of your range" in it }, "sustained high reason: ${alert.reasons}")
            else c.expect(false, "a minute above the top of the range → alert")
        }
        run {
            val g = guard(); g.beginSession(0.0, baseline, 300.0, start)
            val (alert, _) = feed(g, 60.0, start, q = quality(level = -15.0))
            if (alert is StrainGuard.Alert.Strain) c.expect(alert.reasons.any { "loudly" in it }, "sustained loud reason: ${alert.reasons}")
            else c.expect(false, "15 dB over baseline for 30 s → alert")
        }
        run {
            val g = guard(); g.beginSession(0.0, baseline, 300.0, start)
            feed(g, 30.0, start, q = quality(), noisy = true)
            c.expect(g.health == StrainGuard.Health.NOISY, "noisy → health noisy, got ${g.health}")
        }
        run {
            alertsOn = false
            val g = guard(); g.beginSession(0.0, baseline, 300.0, start)
            val (alert, _) = feed(g, 60.0, start, q = quality(hnr = 12.0, cpp = 12.0, jitter = 2.0))
            c.expect(alert == null, "strain alerts off → no strain alert")
            alertsOn = true
        }
        run {
            val g = guard(); g.beginSession(0.0, baseline, 300.0, start)
            val (first, t) = feed(g, 60.0, start, q = quality(hnr = 12.0, cpp = 12.0, jitter = 2.0))
            c.expect(first != null, "cooldown setup: first alert")
            g.rest(start)
            val (second, _) = feed(g, 200.0, start.plusMillis(((t + 1) * 1000).toLong()), q = quality(hnr = 12.0, cpp = 12.0, jitter = 2.0))
            c.expect(second == null, "no second strain alert inside the 5-minute cooldown")
        }

        c.group("StrainGuard daily cap")
        run {
            val g = guard(); g.beginSession(30 * 60 - 1.0, baseline, 300.0, start)
            val (alert, t) = feed(g, 5.0, start, q = quality())
            c.expect(alert == StrainGuard.Alert.DailyCap(30), "cap reached → dailyCap alert, got $alert")
            c.expect(t <= 1.2, "cap alert within a second of 30:00, got $t s")
            g.dismiss()
            val (again, _) = feed(g, 60.0, start.plusSeconds(2), q = quality())
            c.expect(again == null, "5 more minutes: no cap alert for the next minute")
        }
    }

    private fun testExerciseLog() {
        c.group("ExerciseLog day rollover")
        val day1 = java.time.LocalDate.of(2026, 6, 14)
        val day2 = java.time.LocalDate.of(2026, 6, 15)
        var stored = ExerciseLog.marking("warmup", "", day1)
        stored = ExerciseLog.marking("hold", stored, day1)
        c.expect(ExerciseLog.done(stored, day1) == setOf("warmup", "hold"), "both marked on day 1")
        c.expect(ExerciseLog.done(stored, day2).isEmpty(), "empty after midnight")
        stored = ExerciseLog.marking("slides", stored, day2)
        c.expect(ExerciseLog.done(stored, day2) == setOf("slides"), "day 2 starts fresh")
        c.expect(ExerciseLog.done("garbage", day2).isEmpty(), "malformed string → empty")
    }

    private fun result(id: String, date: Instant, passed: Boolean = true, target: Int? = null, hz: Double? = null, stage: Int = 1) =
        ExerciseResult(date, id, stage, passed, 30.0, if (passed) 0 else 1, targetMidi = target, medianHz = hz)

    private fun input(stage: Stage, sessions: List<PracticeSession> = emptyList(), results: List<ExerciseResult> = emptyList(),
                      strain: List<Instant> = emptyList(), baselineHz: Double? = 110.0, goal: GoalRange? = GoalRange.of(57, 61),
                      resonance: ResonanceGoal? = null, holdStep: Int = 0) =
        Progression.Input(now, stage, null, sessions, results, strain, baselineHz, goal, resonance, holdStep, 30, zone)

    private fun testProgression() {
        val today = java.time.LocalDate.ofInstant(now, zone)
        c.group("Today's plan")
        for (stage in Stage.entries) {
            for (remaining in 0..40) {
                val plan = Progression.plan(stage, remaining, today)
                c.expect(plan.minutes <= remaining, "stage ${stage.number}, $remaining min left: plan is ${plan.minutes} min")
                if (plan.items.isNotEmpty()) {
                    c.expect(plan.items.first().role == Progression.PlanItem.Role.WARM_UP && plan.items.last().role == Progression.PlanItem.Role.COOL_DOWN,
                        "stage ${stage.number}: starts with warm-up, ends with cool-down")
                    val core = plan.items.filter { it.role == Progression.PlanItem.Role.STAGE }
                    c.expect(core.isNotEmpty() && core.size <= 3, "stage ${stage.number}: 1–3 stage exercises")
                    c.expect(core.all { stage.number in it.exercise.stages }, "stage exercises belong to the stage")
                    for (r in plan.items.filter { it.role == Progression.PlanItem.Role.REVIEW }) {
                        c.expect(stage.number !in r.exercise.stages && (r.exercise.stages.minOrNull() ?: 9) < stage.number,
                            "review comes from an earlier stage")
                    }
                }
            }
            c.expect(!Progression.plan(stage, 30, today).trimmed, "stage ${stage.number}: full plan fits in 30 min")
        }
        c.expect(Progression.plan(Stage.FOUNDATIONS, 30, today).items.none { it.role == Progression.PlanItem.Role.REVIEW }, "stage 1 has no review")
        c.expect(Progression.plan(Stage.RESONANCE, 30, today).items.any { it.role == Progression.PlanItem.Role.REVIEW }, "stage 3 has a review")
        c.expect(Progression.plan(Stage.FOUNDATIONS, 2, today).items.isEmpty(), "no room → empty plan")

        c.group("Stage 2 hold steps")
        c.expect(Progression.firstHoldStep(GoalRange.of(57, 61), 110.0) == 45, "starts at baseline")
        c.expect(Progression.finalHoldStep(GoalRange.of(57, 61), 110.0) == 59, "ends at goal midpoint")
        val oneDay = listOf(result("hold", daysAgo(1), target = 45), result("hold", daysAgo(1, 19), target = 45))
        c.expect(Progression.nextHoldStep(input(Stage.PITCH, results = oneDay)) == 45, "2 passes on the same day: stays")
        val twoDays = listOf(result("hold", daysAgo(2), target = 45), result("hold", daysAgo(1), target = 45))
        c.expect(Progression.nextHoldStep(input(Stage.PITCH, results = twoDays)) == 46, "2 separate days: up 1 semitone")
        val failed = listOf(result("hold", daysAgo(2), target = 45), result("hold", daysAgo(1), passed = false, target = 45))
        c.expect(Progression.nextHoldStep(input(Stage.PITCH, results = failed)) == 45, "a strain-failed hold doesn't count")
        val wrongStep = listOf(result("hold", daysAgo(2), target = 50), result("hold", daysAgo(1), target = 50))
        c.expect(Progression.nextHoldStep(input(Stage.PITCH, results = wrongStep)) == 45, "passes at another note don't count")
        val down = input(Stage.PITCH, results = listOf(result("hold", daysAgo(2), target = 60), result("hold", daysAgo(1), target = 60)),
            baselineHz = Note(60).frequency, goal = GoalRange.of(45, 49))
        c.expect(Progression.nextHoldStep(down) == 59, "goal below baseline: steps down")
        val atGoal = listOf(result("hold", daysAgo(2), target = 59), result("hold", daysAgo(1), target = 59))
        c.expect(Progression.nextHoldStep(input(Stage.PITCH, results = atGoal, holdStep = 59)) == 59, "stops at the goal")
        c.expect(Progression.currentHoldStep(80, GoalRange.of(57, 61), 110.0) == 59, "stored step past the goal is clamped")
        val ready = (1..7).map { result("hold", daysAgo(it), target = 59) }
        val readyStatus = Progression.status(input(Stage.PITCH, sessions = (1..7).map { session(daysAgo(it)) }, results = ready, holdStep = 59))
        c.expect(readyStatus.ready, "stage 2 ready at the goal with 3+ passing days: ${readyStatus.criteria.filter { !it.met }.map { it.text }}")

        c.group("Safety holds the stage")
        val practised = (1..6).map { session(daysAgo(it)) }
        val warmups = (1..6).map { result("warmup", daysAgo(it)) }
        val clean = Progression.status(input(Stage.FOUNDATIONS, practised, warmups))
        c.expect(clean.ready, "stage 1 ready with 6 days, warm-ups and a baseline: ${clean.criteria.filter { !it.met }.map { it.text }}")
        c.expect(!Progression.status(input(Stage.FOUNDATIONS, practised, warmups, baselineHz = null)).ready, "no baseline → not ready")
        c.expect(!Progression.status(input(Stage.FOUNDATIONS, practised, warmups, strain = listOf(daysAgo(1)))).ready, "strain alert yesterday → held")
        c.expect(Progression.status(input(Stage.FOUNDATIONS, practised, warmups, strain = listOf(daysAgo(4)))).ready, "strain alert 4 days ago → no longer holds")
        val capped = practised + session(daysAgo(0), duration = 30 * 60.0)
        c.expect(!Progression.status(input(Stage.FOUNDATIONS, capped, warmups)).ready, "a day at the cap → held")
        c.expect(!Progression.status(input(Stage.FOUNDATIONS, practised, warmups.take(2))).ready, "warm-up on 2 of 6 days → not ready")
        c.expect(!Progression.status(input(Stage.FINE_TUNING, practised)).ready, "last stage is never 'ready'")

        c.group("Silent exercises don't count (S16)")
        val silentWarmups = (1..6).map { result("warmup", daysAgo(it), passed = false) }
        c.expect(!Progression.status(input(Stage.FOUNDATIONS, results = silentWarmups)).ready, "tapping through warm-ups without voice → not ready")
        c.expect(Progression.practiceDays(input(Stage.FOUNDATIONS, results = silentWarmups), null).isEmpty(), "unpassed results aren't practice days")

        c.group("History stage markers")
        val path = listOf(result("warmup", daysAgo(9)), result("warmup", daysAgo(5), stage = 2),
            result("hold", daysAgo(4), stage = 2), result("vowels", daysAgo(1), stage = 3))
        val changes = Progression.stageChanges(path)
        c.expect(changes.map { it.stage } == listOf(Stage.PITCH, Stage.RESONANCE), "changes 1→2→3, got ${changes.map { it.stage.number }}")
        c.expect(changes.firstOrNull()?.date == daysAgo(5), "stage 2 marker at its first result")
        c.expect(Progression.stageChanges(listOf(result("warmup", daysAgo(1)))).isEmpty(), "staying in stage 1 → no markers")

        c.group("Goal zone share (stage 4)")
        val goal = GoalRange.of(57, 61)!!
        val band = ResonanceGoal.of(60.0, 80.0)!!
        val mixed = session(daysAgo(1), cells = listOf(Triple(58, 14, 200.0), Triple(50, 14, 100.0), Triple(58, 5, 100.0)))
        c.near(Progression.zoneShareLastWeek(input(Stage.COMBINING, listOf(mixed), goal = goal, resonance = band)), 0.5, 1e-9, "200 of 400 s in pitch and resonance goal")
        c.near(Progression.zoneShareLastWeek(input(Stage.COMBINING, listOf(mixed), goal = goal, resonance = null)), 0.75, 1e-9, "pitch goal only: 300 of 400 s")
        val thin = session(daysAgo(1), cells = listOf(Triple(58, 14, 100.0)))
        c.expect(Progression.zoneShareLastWeek(input(Stage.COMBINING, listOf(thin), goal = goal)) == null, "under 5 min → no reading")
        val lastMonth = session(daysAgo(10), cells = listOf(Triple(58, 14, 400.0)))
        c.expect(Progression.zoneShareLastWeek(input(Stage.COMBINING, listOf(lastMonth), goal = goal)) == null, "older than a week ignored")

        c.group("Resonance days (stage 3)")
        val inBand = (1..3).map { session(daysAgo(it), cells = listOf(Triple(58, 14, 130.0))) }
        val resStatus = Progression.status(input(Stage.RESONANCE, inBand + (4..8).map { session(daysAgo(it)) }, resonance = band))
        c.expect(resStatus.criteria.any { "resonance goal" in it.text && it.met }, "3 days with 2+ min in band → met")
        val short = (1..3).map { session(daysAgo(it), cells = listOf(Triple(58, 14, 100.0))) }
        val shortStatus = Progression.status(input(Stage.RESONANCE, short, resonance = band))
        c.expect(shortStatus.criteria.any { "resonance goal" in it.text && !it.met }, "under 2 min a day → not met")
    }

    private fun testExercises() {
        c.group("Exercise library")
        val ids = Exercise.all.map { it.id }
        c.expect(ids.toSet().size == ids.size, "exercise ids are unique")
        for (e in Exercise.all) {
            c.expect(e.basis.summary.isNotEmpty() && e.basis.citations.isNotEmpty(), "${e.id} has a basis with citations")
            c.expect(e.stages.isNotEmpty() && e.stages.all { it in 1..6 }, "${e.id} stages within 1–6")
            val kind = e.kind
            if (kind is Exercise.Kind.Guided) for (s in kind.steps) {
                val lower = s.text.lowercase()
                for (word in listOf("feminine", "masculine", "female", "male", "woman", "girl", "boy")) {
                    c.expect(word !in lower, "${e.id}: step text is goal-neutral (found \"$word\")")
                }
            }
        }
        c.expect(Exercise.all.none { "dog" in it.title.lowercase() }, "big dog / small dog reframed")
        c.expect(Exercise.with(Exercise.WARM_UP_ID)?.stages == (1..6).toSet(), "warm-up every stage")
        c.expect(Exercise.with(Exercise.COOL_DOWN_ID)?.stages == (1..6).toSet(), "cool-down every stage")
        for (stage in 1..6) {
            val n = Exercise.all.count { !it.isWarmUp && !it.isCoolDown && stage in it.stages }
            c.expect(n >= 2, "stage $stage has 2+ exercises, got $n")
        }
    }

    /** iOS MappingTests `testRegionalSpelling`. */
    private fun testRegionalSpelling(c: Checks) {
        c.group("RegionalSpelling (US / Canada / UK, AU, NZ)")
        val r = RegionalSpelling
        c.expect(r.variant(listOf("en-US"), "GB") == RegionalSpelling.Variant.AMERICAN, "en-US → American")
        c.expect(r.variant(listOf("en-CA"), "US") == RegionalSpelling.Variant.CANADIAN, "en-CA → Canadian")
        c.expect(r.variant(listOf("en-GB"), "US") == RegionalSpelling.Variant.BRITISH, "en-GB → British")
        c.expect(r.variant(listOf("en-AU"), "US") == RegionalSpelling.Variant.AUSTRALIAN, "en-AU → Australian")
        c.expect(r.variant(listOf("en-NZ"), "US") == RegionalSpelling.Variant.NEW_ZEALAND, "en-NZ → New Zealand")
        c.expect(r.variant(listOf("en"), "CA") == RegionalSpelling.Variant.CANADIAN, "en + region CA → Canadian")
        c.expect(r.variant(emptyList(), null) == RegionalSpelling.Variant.BRITISH, "unknown → British")

        val src = "Practise the colour drill; you've practised. Recognised by my mum. Grey, centre, Control Centre."
        for (v in listOf(RegionalSpelling.Variant.BRITISH, RegionalSpelling.Variant.AUSTRALIAN, RegionalSpelling.Variant.NEW_ZEALAND))
            c.expect(r.apply(src, v) == src, "$v spelling unchanged")
        c.expect(r.apply(src, RegionalSpelling.Variant.AMERICAN) ==
            "Practice the color drill; you've practiced. Recognized by my mom. Gray, center, Control Center.") { "American: ${r.apply(src, RegionalSpelling.Variant.AMERICAN)}" }
        c.expect(r.apply(src, RegionalSpelling.Variant.CANADIAN) ==
            "Practise the colour drill; you've practised. Recognized by my mom. Grey, centre, Control Centre.") { "Canadian: ${r.apply(src, RegionalSpelling.Variant.CANADIAN)}" }
        c.expect(r.apply("Daily practice cap, maximum, summer, Colour-blind", RegionalSpelling.Variant.AMERICAN) ==
            "Daily practice cap, maximum, summer, Color-blind", "whole words only")
        val slp = "Not a substitute for a speech-language pathologist."
        c.expect(r.apply(slp, RegionalSpelling.Variant.CANADIAN) == slp, "Canada keeps speech-language pathologist")
        c.expect(r.apply(slp, RegionalSpelling.Variant.BRITISH) == "Not a substitute for a speech and language therapist.", "UK term")
        c.expect(r.apply(slp, RegionalSpelling.Variant.AUSTRALIAN) == "Not a substitute for a speech pathologist.", "AU term")
        c.expect(r.apply(slp, RegionalSpelling.Variant.NEW_ZEALAND) == "Not a substitute for a speech-language therapist.", "NZ term")
        c.expect(r.directory("IS").url.startsWith("https://www.talmein.is/"), "Iceland directory")
        c.expect(r.variant(listOf("is-IS"), "IS") == RegionalSpelling.Variant.BRITISH, "Icelandic device → British spelling")
        for (region in listOf("US", "CA", "GB", "AU", "NZ", "IS", null))
            c.expect(r.directory(region).url.startsWith("https://"), "directory link for ${region ?: "unknown"}")

        val british = Regex("\\b([Pp]ractis|[Cc]olour|[Cc]entre|[Rr]ecognis|[Gg]rey\\b|[Mm]um\\b|speech-language patholog)")
        val texts = Exercise.all.flatMap { e ->
            listOf(e.title, e.summary, e.basis.summary, e.basis.strength.label) + ((e.kind as? Exercise.Kind.Guided)?.steps?.map { it.text } ?: emptyList())
        } + RainbowPassage.TEXT
        for (t in texts) {
            val us = r.apply(t, RegionalSpelling.Variant.AMERICAN)
            c.expect(british.find(us) == null) { "British form left for US: ${us.take(60)}" }
        }

        // UI literals with a British form must go through rs() (or carry a "// regional" note).
        val ui = java.io.File(System.getProperty("voicemapper.appUi") ?: return)
        val files = ui.listFiles { f -> f.extension == "kt" }.orEmpty()
        c.expect(files.size > 5, "found app UI sources")
        for (f in files) f.readLines().forEachIndexed { i, line ->
            if ("rs(" in line || "regional" in line || line.trim().startsWith("//") || "\"" !in line) return@forEachIndexed
            val literals = line.split('"').filterIndexed { k, _ -> k % 2 == 1 }.joinToString(" ")
            c.expect(british.find(literals) == null) { "${f.name}:${i + 1} shows British spelling without rs()" }
        }
    }

}
