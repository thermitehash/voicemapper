// Mapping-layer tests (S15). Compiles the app's Analysis/ and Store/Models
// code for macOS and checks it against known values. Run:
//
//   Tools/MappingTests/run.sh        exits 1 on any failure
//
// These measure whether the mapping is right (Hz → note, zones, goals,
// scores, bins, strain rules, progression), not just that the code runs.

import Foundation

// MARK: - Harness

@MainActor final class Results {
    static let shared = Results()
    var count = 0
    var failures: [String] = []
    var group = ""
}

@MainActor func group(_ name: String) {
    Results.shared.group = name
    print("· \(name)")
}

@MainActor func expect(_ ok: Bool, _ message: @autoclosure () -> String) {
    Results.shared.count += 1
    if !ok { Results.shared.failures.append("[\(Results.shared.group)] \(message())") }
}

@MainActor func near(_ a: Double?, _ b: Double, _ tolerance: Double, _ label: String) {
    guard let a else { expect(false, "\(label): nil, expected \(b)"); return }
    expect(abs(a - b) <= tolerance, "\(label): \(a), expected \(b) ± \(tolerance)")
}

let calendar = Calendar.current
let now = calendar.date(from: DateComponents(year: 2026, month: 6, day: 15, hour: 18))!
func daysAgo(_ n: Int, hour: Int = 18) -> Date {
    let d = calendar.date(byAdding: .day, value: -n, to: now)!
    return calendar.date(bySettingHour: hour, minute: 0, second: 0, of: d)!
}

// MARK: - Note

@MainActor func testNote() {
    group("Note Hz ↔ MIDI ↔ name")
    expect(Note(frequency: 440).midi == 69, "440 Hz → MIDI 69")
    expect(Note(midi: 69).name == "A4", "MIDI 69 → A4")
    near(Note(midi: 69).frequency, 440, 1e-9, "A4 Hz")
    near(Note(midi: 60).frequency, 261.63, 0.005, "C4 Hz")
    expect(Note(midi: 60).name == "C4", "MIDI 60 → C4")
    expect(Note(midi: 61).name == "C♯4", "MIDI 61 → C♯4")
    expect(Note(midi: 59).name == "B3", "MIDI 59 → B3")
    expect(Note(midi: 21).name == "A0", "MIDI 21 → A0")
    for m in 24...96 {
        expect(Note(frequency: Note(midi: m).frequency).midi == m, "round trip MIDI \(m)")
    }
    // Rounding to the nearest semitone: ±0.49 st stays, 0.51 st moves.
    expect(Note(frequency: 440 * pow(2, 0.49 / 12)).midi == 69, "+0.49 st rounds to A4")
    expect(Note(frequency: 440 * pow(2, 0.51 / 12)).midi == 70, "+0.51 st rounds to A♯4")
    expect(Note(frequency: 440 * pow(2, -0.49 / 12)).midi == 69, "−0.49 st rounds to A4")
    near(Note.midiValue(frequency: 880), 81, 1e-9, "880 Hz midiValue")
}

// MARK: - Zones and presets

@MainActor func testZones() {
    group("PerceivedGender zone boundaries (145 / 175 Hz)")
    expect(PerceivedGender(frequency: 144.99) == .male, "144.99 Hz → male")
    expect(PerceivedGender(frequency: 145) == .ambiguous, "145 Hz → ambiguous")
    expect(PerceivedGender(frequency: 174.99) == .ambiguous, "174.99 Hz → ambiguous")
    expect(PerceivedGender(frequency: 175) == .female, "175 Hz → female")
    for zone in [PerceivedGender.male, .ambiguous, .female] {
        expect(PerceivedGender(frequency: zone.typicalFrequency) == zone, "\(zone) typical pitch inside its zone")
        near(zone.commonness(at: zone.typicalFrequency), 1, 1e-9, "\(zone) commonness peak")
        near(zone.resonanceCommonness(at: zone.typicalResonance), 1, 1e-9, "\(zone) resonance commonness peak")
    }

    group("GoalPreset ranges stay inside their zones")
    let titles = GoalPreset.allCases.map(\.title)
    expect(titles == titles.sorted(), "presets listed alphabetically: \(titles)")
    for p in GoalPreset.allCases {
        let r = p.range
        expect(r.low <= r.high, "\(p.title) range ordered")
        for m in r.low...r.high {
            expect(PerceivedGender(frequency: Note(midi: m).frequency) == p.zone,
                   "\(p.title): \(Note(midi: m).name) inside \(p.zone)")
        }
        expect(p.resonance.low >= 0 && p.resonance.high <= 100, "\(p.title) resonance within 0–100")
        expect(p.resonance.low <= p.zone.typicalResonance && p.zone.typicalResonance <= p.resonance.high,
               "\(p.title) resonance band contains the zone's typical score")
        expect(GoalPreset.matching(low: r.low, high: r.high, resonanceLow: p.resonance.low,
                                   resonanceHigh: p.resonance.high) == p, "\(p.title) matching round trip")
    }
    expect(GoalPreset.label(low: 0, high: 0, resonanceLow: 0, resonanceHigh: 0) == "Not set", "unset label")
    expect(GoalPreset.label(low: 50, high: 52, resonanceLow: 0, resonanceHigh: 0) == "Custom", "custom label")
}

// MARK: - Resonance scorer and bins

@MainActor func testResonance() {
    group("ResonanceScorer endpoints")
    near(ResonanceScorer.score(spacing: 900), 0, 1e-9, "ΔF 900 → 0")
    near(ResonanceScorer.score(spacing: 1300), 100, 1e-9, "ΔF 1300 → 100")
    near(ResonanceScorer.score(spacing: 1100), 50, 1e-9, "ΔF 1100 → 50")
    near(ResonanceScorer.score(spacing: 700), 0, 1e-9, "clamped below")
    near(ResonanceScorer.score(spacing: 1600), 100, 1e-9, "clamped above")
    // Uniform 17.5 cm tube: odd quarter-wave resonances 500/1500/2500/3500 → ΔF 1000.
    let tube = [500.0, 1500, 2500, 3500].map { FormantAnalyzer.Formant(frequency: $0, bandwidth: 80) }
    near(ResonanceScorer.spacing(of: tube), 1000, 1e-6, "uniform tube ΔF")
    near(ResonanceScorer.tractLength(spacing: 1000), 17.5, 1e-9, "tract length")
    expect(ResonanceScorer.spacing(of: Array(tube.prefix(2))) == nil, "needs 3+ formants")
    let wideF1 = [FormantAnalyzer.Formant(frequency: 500, bandwidth: 600)] + tube.dropFirst()
    expect(ResonanceScorer.assigned(wideF1).first != 500, "very wide F1 skipped")

    group("GridCellCount.bin edges")
    let cases: [(Double, Int)] = [(-5, 0), (0, 0), (4.999, 0), (5, 1), (50, 10), (99.999, 19), (100, 19), (150, 19)]
    for (score, bin) in cases {
        expect(GridCellCount.bin(forResonance: score) == bin, "score \(score) → bin \(bin), got \(GridCellCount.bin(forResonance: score))")
    }
}

// MARK: - Heat map

func session(_ start: Date, duration: Double = 300, medianHz: Double = 180, resonance: Double? = 60,
             cells: [(Int, Int, Double)] = []) -> PracticeSession {
    let s = PracticeSession(start: start, duration: duration, voicedSeconds: duration / 2, medianHz: medianHz,
                            p10Hz: medianHz * 0.9, p90Hz: medianHz * 1.1, medianResonance: resonance)
    for (m, b, sec) in cells { s.cells.append(GridCellCount(pitchMidi: m, resonanceBin: b, seconds: sec)) }
    return s
}

@MainActor func testHeatmap() {
    group("HeatmapBinner totals and periods")
    let a = session(daysAgo(0), cells: [(57, 10, 30), (58, 10, 20)])
    let b = session(daysAgo(1), cells: [(57, 10, 15), (60, 12, 5)])
    let cells = HeatmapBinner.cells(from: [a, b])
    near(cells.reduce(0) { $0 + $1.seconds }, 70, 1e-9, "total seconds preserved")
    near(cells.first { $0.midi == 57 && $0.bin == 10 }?.seconds, 45, 1e-9, "same cell merged across sessions")
    expect(cells.count == 3, "3 distinct cells, got \(cells.count)")

    let old = session(daysAgo(8))
    let ancient = session(daysAgo(40))
    let all = [a, b, old, ancient]
    expect(HeatmapBinner.sessions(all, in: .today, now: now).count == 1, "today: 1 session")
    expect(HeatmapBinner.sessions(all, in: .week, now: now).count == 2, "week: 2 sessions")
    expect(HeatmapBinner.sessions(all, in: .month, now: now).count == 3, "month: 3 sessions")
    expect(HeatmapBinner.sessions(all, in: .all, now: now).count == 4, "all: 4 sessions")
    let points = HeatmapBinner.points(from: [b, session(daysAgo(2), resonance: nil), a])
    expect(points.count == 2, "points skip sessions without resonance")
    expect(points.first?.date == b.start, "points sorted by date")
}

// MARK: - Goals

@MainActor func testGoals() {
    group("GoalRange closeness and direction")
    let g = GoalRange(low: 55, high: 59)!
    let hz = { (m: Double) in 440 * pow(2, (m - 69) / 12) }
    near(g.closeness(of: hz(57)), 1, 1e-9, "inside → 1")
    near(g.closeness(of: hz(55)), 1, 1e-9, "at low edge → 1")
    near(g.closeness(of: hz(52)), 0.5, 1e-9, "3 st below → 0.5")
    near(g.closeness(of: hz(62)), 0.5, 1e-9, "3 st above → 0.5")
    near(g.closeness(of: hz(49)), 0, 1e-9, "6 st below → 0")
    near(g.closeness(of: hz(40)), 0, 1e-9, "far below → 0")
    expect(g.direction(of: hz(54)) == .higher, "1 st below → higher")
    expect(g.direction(of: hz(54.6)) == nil, "within half a semitone → no nudge")
    expect(g.direction(of: hz(57)) == nil, "inside → nil")
    expect(g.direction(of: hz(60)) == .lower, "1 st above → lower")
    expect(GoalRange(low: 0, high: 5) == nil, "unset low → nil")
    expect(GoalRange(low: 60, high: 59) == nil, "inverted → nil")
    expect(g.contains(midi: 55) && g.contains(midi: 59) && !g.contains(midi: 60), "contains is inclusive")

    group("ResonanceGoal")
    expect(ResonanceGoal(low: 50, high: 53) == nil, "narrower than 4 → nil")
    let r = ResonanceGoal(low: 40, high: 60)!
    expect(r.direction(of: 30) == .brighter && r.direction(of: 70) == .darker && r.direction(of: 50) == nil, "direction")
    let clamped = ResonanceGoal(low: -10, high: 120)!
    expect(clamped.low == 0 && clamped.high == 100, "clamped to 0–100")
}

// MARK: - StrainGuard

func quality(hnr: Double = 20, cpp: Double = 20, jitter: Double = 0.5, shimmer: Double = 3, level: Double = -30) -> VoiceQuality {
    VoiceQuality(h1h2: 4, alphaRatio: -18, cpp: cpp, hnr: hnr, jitter: jitter, shimmer: shimmer, level: level)
}

/// Feeds `seconds` of 5 Hz ticks; returns the first alert and when it came.
@MainActor func feed(_ guardian: StrainGuard, seconds: Double, from start: Date, pitch: Double = 180,
                     quality q: VoiceQuality?, noisy: Bool = false) -> (StrainGuard.Alert?, Double) {
    var t = 0.0
    while t < seconds {
        guardian.observe(tick: 0.2, pitch: pitch, quality: q, noisy: noisy, now: start.addingTimeInterval(t))
        if let a = guardian.alert { return (a, t) }
        t += 0.2
    }
    return (nil, t)
}

@MainActor func testStrain() {
    let defaults = UserDefaults.standard
    defaults.set(true, forKey: StrainGuard.alertsKey)
    defaults.set(30, forKey: StrainGuard.capKey)
    let baseline = QualitySummary(Array(repeating: quality(), count: 50))
    let start = Date.now

    group("StrainGuard rules on scripted inputs")
    do {
        let g = StrainGuard()
        g.beginSession(priorToday: 0, baseline: baseline, topHz: 300)
        let (alert, _) = feed(g, seconds: 120, from: start, quality: quality())
        expect(alert == nil, "steady voice at baseline: no alert, got \(String(describing: alert))")
        expect(g.health == .steady, "steady voice → health steady, got \(g.health)")
    }
    do {
        let g = StrainGuard()
        g.beginSession(priorToday: 0, baseline: baseline, topHz: 300)
        let (alert, t) = feed(g, seconds: 60, from: start, quality: quality(hnr: 12, cpp: 12, jitter: 2))
        if case .strain(let reasons)? = alert {
            expect(reasons.count >= 2, "noisier tone + unsteady pitch → 2+ reasons: \(reasons)")
            expect(t >= 9.6 && t <= 12, "alert after ~10 s of voice (50 ticks from t = 0), got \(t) s")
        } else {
            expect(false, "two measures off → strain alert, got \(String(describing: alert))")
        }
    }
    do {
        let g = StrainGuard()
        g.beginSession(priorToday: 0, baseline: baseline, topHz: 300)
        let (alert, _) = feed(g, seconds: 60, from: start, quality: quality(hnr: 12))
        expect(alert == nil, "one measure off → no alert")
        expect(g.health == .watch, "one measure off → health watch, got \(g.health)")
    }
    do {
        let g = StrainGuard()
        g.beginSession(priorToday: 0, baseline: baseline, topHz: 200)
        let (alert, _) = feed(g, seconds: 90, from: start, pitch: 250, quality: quality())
        if case .strain(let reasons)? = alert {
            expect(reasons.contains { $0.contains("top of your range") }, "sustained high reason: \(reasons)")
        } else { expect(false, "a minute above the top of the range → alert") }
    }
    do {
        let g = StrainGuard()
        g.beginSession(priorToday: 0, baseline: baseline, topHz: 300)
        let (alert, _) = feed(g, seconds: 60, from: start, quality: quality(level: -15))
        if case .strain(let reasons)? = alert {
            expect(reasons.contains { $0.contains("loudly") }, "sustained loud reason: \(reasons)")
        } else { expect(false, "15 dB over baseline for 30 s → alert") }
    }
    do {
        let g = StrainGuard()
        g.beginSession(priorToday: 0, baseline: baseline, topHz: 300)
        _ = feed(g, seconds: 30, from: start, quality: quality(), noisy: true)
        expect(g.health == .noisy, "noisy → health noisy, got \(g.health)")
    }
    do {
        defaults.set(false, forKey: StrainGuard.alertsKey)
        let g = StrainGuard()
        g.beginSession(priorToday: 0, baseline: baseline, topHz: 300)
        let (alert, _) = feed(g, seconds: 60, from: start, quality: quality(hnr: 12, cpp: 12, jitter: 2))
        expect(alert == nil, "strain alerts off → no strain alert")
        defaults.set(true, forKey: StrainGuard.alertsKey)
    }
    do {
        let g = StrainGuard()
        g.beginSession(priorToday: 0, baseline: baseline, topHz: 300)
        let (first, t) = feed(g, seconds: 60, from: start, quality: quality(hnr: 12, cpp: 12, jitter: 2))
        expect(first != nil, "cooldown setup: first alert")
        g.rest()
        let (second, _) = feed(g, seconds: 200, from: start.addingTimeInterval(t + 1),
                               quality: quality(hnr: 12, cpp: 12, jitter: 2))
        expect(second == nil, "no second strain alert inside the 5-minute cooldown")
    }

    group("StrainGuard daily cap")
    do {
        let g = StrainGuard()
        g.beginSession(priorToday: 30 * 60 - 1, baseline: baseline, topHz: 300)
        let (alert, t) = feed(g, seconds: 5, from: start, quality: quality())
        expect(alert == .dailyCap(minutes: 30), "cap reached → dailyCap alert, got \(String(describing: alert))")
        expect(t <= 1.2, "cap alert within a second of 30:00, got \(t) s")
        g.dismiss()  // "5 more minutes"
        let (again, _) = feed(g, seconds: 60, from: start.addingTimeInterval(2), quality: quality())
        expect(again == nil, "5 more minutes: no cap alert for the next minute")
    }
}

// MARK: - ExerciseLog

@MainActor func testExerciseLog() {
    group("ExerciseLog day rollover")
    let day1 = daysAgo(1, hour: 23)
    let day2 = daysAgo(0, hour: 0)
    var stored = ExerciseLog.marking("warmup", in: "", on: day1)
    stored = ExerciseLog.marking("hold", in: stored, on: day1)
    expect(ExerciseLog.done(in: stored, on: day1) == ["warmup", "hold"], "both marked on day 1")
    expect(ExerciseLog.done(in: stored, on: day2).isEmpty, "empty after midnight")
    stored = ExerciseLog.marking("slides", in: stored, on: day2)
    expect(ExerciseLog.done(in: stored, on: day2) == ["slides"], "day 2 starts fresh")
    expect(ExerciseLog.done(in: "garbage", on: day2).isEmpty, "malformed string → empty")
}

// MARK: - Progression (S14)

func result(_ id: String, _ date: Date, passed: Bool = true, target: Int? = nil, hz: Double? = nil) -> ExerciseResult {
    let r = ExerciseResult(date: date, exerciseID: id, stage: 1, passed: passed, voicedSeconds: 30, strainAlerts: passed ? 0 : 1)
    r.targetMidi = target
    r.medianHz = hz
    return r
}

func input(_ stage: Stage, sessions: [PracticeSession] = [], results: [ExerciseResult] = [], strain: [Date] = [],
           baselineHz: Double? = 110, goal: GoalRange? = GoalRange(low: 57, high: 61),
           resonance: ResonanceGoal? = nil, holdStep: Int = 0) -> Progression.Input {
    Progression.Input(now: now, stage: stage, stageStart: nil, sessions: sessions, results: results,
                      strainDates: strain, baselineHz: baselineHz, goal: goal, resonanceGoal: resonance,
                      holdStep: holdStep, capMinutes: 30)
}

@MainActor func testProgression() {
    group("Today's plan")
    for stage in Stage.allCases {
        for remaining in 0...40 {
            let plan = Progression.plan(stage: stage, remainingMinutes: remaining, on: now)
            expect(plan.minutes <= remaining, "stage \(stage.rawValue), \(remaining) min left: plan is \(plan.minutes) min")
            if !plan.items.isEmpty {
                expect(plan.items.first?.role == .warmUp && plan.items.last?.role == .coolDown,
                       "stage \(stage.rawValue): starts with warm-up, ends with cool-down")
                let core = plan.items.filter { $0.role == .stage }
                expect(!core.isEmpty && core.count <= 3, "stage \(stage.rawValue): 1–3 stage exercises")
                expect(core.allSatisfy { $0.exercise.stages.contains(stage.rawValue) }, "stage exercises belong to the stage")
                for r in plan.items where r.role == .review {
                    expect(!r.exercise.stages.contains(stage.rawValue) && (r.exercise.stages.min() ?? 9) < stage.rawValue,
                           "review comes from an earlier stage")
                }
            }
        }
        let full = Progression.plan(stage: stage, remainingMinutes: 30, on: now)
        expect(!full.trimmed, "stage \(stage.rawValue): full plan fits in 30 min")
    }
    expect(Progression.plan(stage: .foundations, remainingMinutes: 30, on: now).items.allSatisfy { $0.role != .review },
           "stage 1 has no review")
    expect(Progression.plan(stage: .resonance, remainingMinutes: 30, on: now).items.contains { $0.role == .review },
           "stage 3 has a review")
    expect(Progression.plan(stage: .foundations, remainingMinutes: 2, on: now).items.isEmpty, "no room → empty plan")

    group("Stage 2 hold steps")
    // Baseline 110 Hz = A2 (45); goal 57–61 → final 59.
    expect(Progression.firstHoldStep(goal: GoalRange(low: 57, high: 61), baselineHz: 110) == 45, "starts at baseline")
    expect(Progression.finalHoldStep(goal: GoalRange(low: 57, high: 61), baselineHz: 110) == 59, "ends at goal midpoint")
    let oneDay = [result("hold", daysAgo(1), target: 45), result("hold", daysAgo(1, hour: 19), target: 45)]
    expect(Progression.nextHoldStep(input(.pitch, results: oneDay)) == 45, "2 passes on the same day: stays")
    let twoDays = [result("hold", daysAgo(2), target: 45), result("hold", daysAgo(1), target: 45)]
    expect(Progression.nextHoldStep(input(.pitch, results: twoDays)) == 46, "2 separate days: up 1 semitone")
    let failed = [result("hold", daysAgo(2), target: 45), result("hold", daysAgo(1), passed: false, target: 45)]
    expect(Progression.nextHoldStep(input(.pitch, results: failed)) == 45, "a strain-failed hold doesn't count")
    let wrongStep = [result("hold", daysAgo(2), target: 50), result("hold", daysAgo(1), target: 50)]
    expect(Progression.nextHoldStep(input(.pitch, results: wrongStep)) == 45, "passes at another note don't count")
    let down = input(.pitch, results: [result("hold", daysAgo(2), target: 60), result("hold", daysAgo(1), target: 60)],
                     baselineHz: Note(midi: 60).frequency, goal: GoalRange(low: 45, high: 49))
    expect(Progression.nextHoldStep(down) == 59, "goal below baseline: steps down")
    let atGoal = [result("hold", daysAgo(2), target: 59), result("hold", daysAgo(1), target: 59)]
    expect(Progression.nextHoldStep(input(.pitch, results: atGoal, holdStep: 59)) == 59, "stops at the goal")
    expect(Progression.currentHoldStep(stored: 80, goal: GoalRange(low: 57, high: 61), baselineHz: 110) == 59,
           "stored step past the goal is clamped")
    let ready = (1...7).map { result("hold", daysAgo($0), target: 59) }
    let readyStatus = Progression.status(input(.pitch, sessions: (1...7).map { session(daysAgo($0)) },
                                               results: ready, holdStep: 59))
    expect(readyStatus.ready, "stage 2 ready at the goal with 3+ passing days: \(readyStatus.criteria.filter { !$0.met }.map(\.text))")

    group("Safety holds the stage")
    let practised = (1...6).map { session(daysAgo($0)) }
    let warmups = (1...6).map { result("warmup", daysAgo($0)) }
    let clean = Progression.status(input(.foundations, sessions: practised, results: warmups))
    expect(clean.ready, "stage 1 ready with 6 days, warm-ups and a baseline: \(clean.criteria.filter { !$0.met }.map(\.text))")
    expect(!Progression.status(input(.foundations, sessions: practised, results: warmups, baselineHz: nil)).ready,
           "no baseline → not ready")
    expect(!Progression.status(input(.foundations, sessions: practised, results: warmups, strain: [daysAgo(1)])).ready,
           "strain alert yesterday → held")
    expect(Progression.status(input(.foundations, sessions: practised, results: warmups, strain: [daysAgo(4)])).ready,
           "strain alert 4 days ago → no longer holds")
    let capped = practised + [session(daysAgo(0), duration: 30 * 60)]
    expect(!Progression.status(input(.foundations, sessions: capped, results: warmups)).ready, "a day at the cap → held")
    let fewWarmups = Array(warmups.prefix(2))
    expect(!Progression.status(input(.foundations, sessions: practised, results: fewWarmups)).ready,
           "warm-up on 2 of 6 days → not ready")
    expect(!Progression.status(input(.fineTuning, sessions: practised)).ready, "last stage is never 'ready'")

    group("Goal zone share (stage 4)")
    let goal = GoalRange(low: 57, high: 61)!
    let band = ResonanceGoal(low: 60, high: 80)!
    let mixed = session(daysAgo(1), cells: [(58, 14, 200), (50, 14, 100), (58, 5, 100)])
    near(Progression.zoneShareLastWeek(input(.combining, sessions: [mixed], goal: goal, resonance: band)), 0.5, 1e-9,
         "200 of 400 s in pitch and resonance goal")
    near(Progression.zoneShareLastWeek(input(.combining, sessions: [mixed], goal: goal, resonance: nil)), 0.75, 1e-9,
         "pitch goal only: 300 of 400 s")
    let thin = session(daysAgo(1), cells: [(58, 14, 100)])
    expect(Progression.zoneShareLastWeek(input(.combining, sessions: [thin], goal: goal)) == nil, "under 5 min → no reading")
    let lastMonth = session(daysAgo(10), cells: [(58, 14, 400)])
    expect(Progression.zoneShareLastWeek(input(.combining, sessions: [lastMonth], goal: goal)) == nil, "older than a week ignored")

    group("Resonance days (stage 3)")
    let inBand = (1...3).map { session(daysAgo($0), cells: [(58, 14, 130)]) }  // bin 14 → 72.5, inside 60–80
    let resStatus = Progression.status(input(.resonance, sessions: inBand + (4...8).map { session(daysAgo($0)) },
                                             resonance: band))
    expect(resStatus.criteria.contains { $0.text.contains("resonance goal") && $0.met }, "3 days with 2+ min in band → met")
    let short = (1...3).map { session(daysAgo($0), cells: [(58, 14, 100)]) }
    let shortStatus = Progression.status(input(.resonance, sessions: short, resonance: band))
    expect(shortStatus.criteria.contains { $0.text.contains("resonance goal") && !$0.met }, "under 2 min a day → not met")
}

// MARK: - Exercise library (S14 evidence review)

@MainActor func testExercises() {
    group("Exercise library")
    let ids = Exercise.all.map(\.id)
    expect(Set(ids).count == ids.count, "exercise ids are unique")
    for e in Exercise.all {
        expect(!e.basis.summary.isEmpty && !e.basis.citations.isEmpty, "\(e.id) has a basis with citations")
        expect(!e.stages.isEmpty && e.stages.isSubset(of: Set(1...6)), "\(e.id) stages within 1–6")
        if case .guided(let steps) = e.kind {
            for s in steps {
                let lower = s.text.lowercased()
                for word in ["feminine", "masculine", "female", "male", "woman", "girl", "boy"] {
                    expect(!lower.contains(word), "\(e.id): step text is goal-neutral (found \"\(word)\")")
                }
            }
        }
    }
    expect(!Exercise.all.contains { $0.title.lowercased().contains("dog") }, "big dog / small dog reframed")
    expect(Exercise.with(id: Exercise.warmUpID)?.stages == Set(1...6), "warm-up every stage")
    expect(Exercise.with(id: Exercise.coolDownID)?.stages == Set(1...6), "cool-down every stage")
    for stage in 1...6 {
        let n = Exercise.all.filter { !$0.isWarmUp && !$0.isCoolDown && $0.stages.contains(stage) }.count
        expect(n >= 2, "stage \(stage) has 2+ exercises, got \(n)")
    }
}

// MARK: - Main

@main struct MappingTests {
    @MainActor static func main() {
        testNote()
        testZones()
        testResonance()
        testHeatmap()
        testGoals()
        testStrain()
        testExerciseLog()
        testProgression()
        testExercises()
        let r = Results.shared
        print("\n\(r.count - r.failures.count) of \(r.count) checks passed")
        for f in r.failures { print("FAIL \(f)") }
        exit(r.failures.isEmpty ? 0 : 1)
    }
}
