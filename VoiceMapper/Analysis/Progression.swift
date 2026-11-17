import Foundation

/// The long arc from new learner to fine tuner. docs/PROGRESSION.md.
/// Stages are suggested, never locked: criteria only decide when the app
/// says "ready to move on". Strain alerts and capped-out days hold a stage.
enum Stage: Int, CaseIterable, Identifiable, Sendable {
    case foundations = 1, pitch, resonance, combining, speech, fineTuning

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .foundations: "Foundations"
        case .pitch: "Pitch"
        case .resonance: "Resonance"
        case .combining: "Combining"
        case .speech: "Speech"
        case .fineTuning: "Fine-tuning"
        }
    }

    var focus: String {
        switch self {
        case .foundations: "Easy breathing and voicing, comfort and awareness."
        case .pitch: "Find and hold notes, moving step by step from your baseline towards your goal."
        case .resonance: "Change brightness and darkness without changing pitch."
        case .combining: "Pitch and resonance together, on the grid."
        case .speech: "Carry it into reading, phrases and intonation."
        case .fineTuning: "Vocal weight, consistency and keeping your voice steady over time."
        }
    }

    /// Practice days in the stage before the app suggests moving on:
    /// the voice adapts over weeks, not sessions.
    var minimumDays: Int {
        switch self {
        case .foundations: 5
        case .fineTuning: 0
        default: 7
        }
    }

    var next: Stage? { Stage(rawValue: rawValue + 1) }
}

enum Progression {
    static let stageKey = "progression.stage"
    /// When the current stage began (seconds since 1970); 0 = from the first practice day.
    static let stageStartKey = "progression.stageStart"
    /// Stage 2 hold target (MIDI); 0 = not started yet.
    static let holdStepKey = "progression.holdStep"

    /// Days in a row of practice without strain or hitting the cap before moving on.
    static let safetyWindowDays = 3
    /// Separate passing days at one hold step before it moves 1 semitone.
    static let passesPerStep = 2
    /// Separate passing days needed at the final step.
    static let passesToMoveOn = 3
    /// Voiced seconds inside the resonance band, in one session, to count a stage 3 day.
    static let resonanceSecondsPerDay = 120.0
    /// Share of voiced time inside the goal zone over the past week for stage 4.
    static let zoneShare = 0.5
    static let zoneMinimumSeconds = 300.0
    static let daysNeeded = 3

    static let resonanceExerciseIDs: Set<String> = ["forwardback", "vowels"]
    static let combiningExerciseIDs: Set<String> = ["hold", "vowels", "phrases"]

    static func stage(stored: Int) -> Stage { Stage(rawValue: stored) ?? .foundations }

    /// Sets the stage now (Settings ▸ Goal ▸ Choose stage, or "Move on").
    static func choose(_ stage: Stage, defaults: UserDefaults = .standard) {
        defaults.set(stage.rawValue, forKey: stageKey)
        defaults.set(Date.now.timeIntervalSince1970, forKey: stageStartKey)
    }

    // MARK: - Criteria

    struct Criterion: Identifiable, Equatable {
        let text: String
        let met: Bool
        var id: String { text }
    }

    /// Everything the criteria look at, gathered by the view from SwiftData and settings.
    struct Input {
        var now: Date = .now
        var stage: Stage
        /// Nil = count from the first practice day.
        var stageStart: Date?
        var sessions: [PracticeSession]
        var results: [ExerciseResult]
        var strainDates: [Date]
        var baselineHz: Double?
        var goal: GoalRange?
        var resonanceGoal: ResonanceGoal?
        var holdStep: Int
        var capMinutes: Int
        var calendar: Calendar = .current
    }

    struct Status {
        let criteria: [Criterion]
        /// All criteria met; never true in the last stage.
        let ready: Bool
    }

    static func status(_ input: Input) -> Status {
        let c = criteria(input)
        return Status(criteria: c, ready: input.stage.next != nil && !c.isEmpty && c.allSatisfy(\.met))
    }

    static func criteria(_ i: Input) -> [Criterion] {
        var list: [Criterion] = []
        let days = practiceDays(i, since: i.stageStart)

        switch i.stage {
        case .foundations:
            list.append(.init(text: "Practised on \(min(days.count, 5)) of 5 days", met: days.count >= 5))
            let warmDays = dayCount(i, results: i.results.filter { $0.exerciseID == Exercise.warmUpID && within(i, $0.date) })
            let most = days.count > 0 && Double(warmDays) >= Double(days.count) * 0.6
            list.append(.init(text: days.isEmpty ? "Warm-up on most practice days"
                              : "Warm-up on most practice days (\(warmDays) of \(days.count))",
                              met: most && warmDays >= 3))
            list.append(.init(text: i.baselineHz == nil ? "Record your baseline (Settings)" : "Baseline recorded",
                              met: i.baselineHz != nil))

        case .pitch:
            list.append(minimumDays(i, days))
            let final = finalHoldStep(goal: i.goal, baselineHz: i.baselineHz)
            let step = currentHoldStep(stored: i.holdStep, goal: i.goal, baselineHz: i.baselineHz)
            if i.goal != nil {
                list.append(.init(
                    text: step == final ? "Hold target has reached your goal (\(Note(midi: final).name))"
                        : "Hold target stepping towards your goal (\(Note(midi: step).name) → \(Note(midi: final).name))",
                    met: step == final))
            }
            let passes = holdPassDays(i, at: step)
            list.append(.init(text: "Hold drill passed on \(min(passes, passesToMoveOn)) of \(passesToMoveOn) days at \(Note(midi: step).name)",
                              met: passes >= passesToMoveOn))

        case .resonance:
            list.append(minimumDays(i, days))
            if let band = i.resonanceGoal {
                let n = resonanceDays(i, band: band)
                list.append(.init(text: "2+ minutes inside your resonance goal in one session, on \(min(n, daysNeeded)) of \(daysNeeded) days",
                                  met: n >= daysNeeded))
            } else {
                let n = exerciseDays(i, ids: resonanceExerciseIDs)
                list.append(.init(text: "Resonance exercises on \(min(n, daysNeeded)) of \(daysNeeded) days", met: n >= daysNeeded))
            }

        case .combining:
            list.append(minimumDays(i, days))
            if let share = zoneShareLastWeek(i) {
                list.append(.init(text: "Time inside your goal zone this week: \(Int((share * 100).rounded()))% (aim for 50%)",
                                  met: share >= zoneShare))
            } else if i.goal == nil && i.resonanceGoal == nil {
                let n = exerciseDays(i, ids: combiningExerciseIDs)
                list.append(.init(text: "Combining exercises on \(min(n, daysNeeded)) of \(daysNeeded) days", met: n >= daysNeeded))
            } else {
                list.append(.init(text: "Practise 5+ minutes this week so the heat map can measure time in your goal zone", met: false))
            }

        case .speech:
            list.append(minimumDays(i, days))
            let readings = i.results.filter { $0.exerciseID == Exercise.readingID && within(i, $0.date) }
            if let goal = i.goal {
                let inGoal = readings.filter { r in r.medianHz.map { goal.contains(midi: Note(frequency: $0).midi) } ?? false }
                let n = dayCount(i, results: inGoal)
                list.append(.init(text: "Reading with its median pitch in your goal range on \(min(n, daysNeeded)) of \(daysNeeded) days",
                                  met: n >= daysNeeded))
            } else {
                let n = dayCount(i, results: readings)
                list.append(.init(text: "Reading on \(min(n, daysNeeded)) of \(daysNeeded) days", met: n >= daysNeeded))
            }
            let n = exerciseDays(i, ids: [Exercise.intonationID])
            list.append(.init(text: "Intonation exercise on \(min(n, daysNeeded)) of \(daysNeeded) days", met: n >= daysNeeded))

        case .fineTuning:
            let start = i.calendar.date(byAdding: .day, value: -27, to: i.calendar.startOfDay(for: i.now))!
            let month = practiceDays(i, since: start).count
            list.append(.init(text: "Practised on \(month) of the last 28 days", met: month >= 12))
        }

        list.append(contentsOf: safety(i))
        return list
    }

    /// Strain alerts and capped-out days in the last few days hold the stage.
    static func safety(_ i: Input) -> [Criterion] {
        let since = i.calendar.date(byAdding: .day, value: -(safetyWindowDays - 1), to: i.calendar.startOfDay(for: i.now))!
        let strain = i.strainDates.contains { $0 >= since }
        var perDay: [Date: Double] = [:]
        for s in i.sessions where s.start >= since {
            perDay[i.calendar.startOfDay(for: s.start), default: 0] += s.duration
        }
        let capped = perDay.values.contains { $0 >= Double(i.capMinutes) * 60 }
        return [
            .init(text: "No strain alerts in the last \(safetyWindowDays) days", met: !strain),
            .init(text: "Stayed under your daily cap in the last \(safetyWindowDays) days", met: !capped),
        ]
    }

    // MARK: - Hold steps (stage 2)

    /// Where stepping starts: the baseline, else the goal midpoint, else A3.
    static func firstHoldStep(goal: GoalRange?, baselineHz: Double?) -> Int {
        if let baselineHz { return Note(frequency: baselineHz).midi }
        if let goal { return (goal.low + goal.high) / 2 }
        return 57
    }

    /// Where stepping ends: the goal midpoint (the hold drill's usual target).
    static func finalHoldStep(goal: GoalRange?, baselineHz: Double?) -> Int {
        if let goal { return (goal.low + goal.high) / 2 }
        return firstHoldStep(goal: goal, baselineHz: baselineHz)
    }

    static func currentHoldStep(stored: Int, goal: GoalRange?, baselineHz: Double?) -> Int {
        guard stored > 0 else { return firstHoldStep(goal: goal, baselineHz: baselineHz) }
        let first = firstHoldStep(goal: goal, baselineHz: baselineHz)
        let final = finalHoldStep(goal: goal, baselineHz: baselineHz)
        // Keep the step between start and goal if either has changed since.
        return min(max(stored, min(first, final)), max(first, final))
    }

    /// The step after recording a hold result: 1 semitone towards the goal
    /// once the current step has passed on `passesPerStep` separate days.
    static func nextHoldStep(_ i: Input) -> Int {
        let step = currentHoldStep(stored: i.holdStep, goal: i.goal, baselineHz: i.baselineHz)
        let final = finalHoldStep(goal: i.goal, baselineHz: i.baselineHz)
        guard step != final, holdPassDays(i, at: step) >= passesPerStep else { return step }
        return step + (final > step ? 1 : -1)
    }

    /// Separate days the hold drill was passed (no strain) at `step`.
    static func holdPassDays(_ i: Input, at step: Int) -> Int {
        dayCount(i, results: i.results.filter {
            $0.exerciseID == Exercise.holdID && $0.passed && $0.targetMidi == step && within(i, $0.date)
        })
    }

    // MARK: - Measures

    static func practiceDays(_ i: Input, since: Date?) -> Set<Date> {
        let from = since.map { i.calendar.startOfDay(for: $0) } ?? .distantPast
        var days = Set(i.sessions.filter { $0.start >= from }.map { i.calendar.startOfDay(for: $0.start) })
        days.formUnion(i.results.filter { $0.date >= from }.map { i.calendar.startOfDay(for: $0.date) })
        return days
    }

    private static func minimumDays(_ i: Input, _ days: Set<Date>) -> Criterion {
        let need = i.stage.minimumDays
        return .init(text: "Practised on \(min(days.count, need)) of \(need) days in this stage", met: days.count >= need)
    }

    private static func within(_ i: Input, _ date: Date) -> Bool {
        guard let start = i.stageStart else { return true }
        return date >= i.calendar.startOfDay(for: start)
    }

    private static func dayCount(_ i: Input, results: [ExerciseResult]) -> Int {
        Set(results.map { i.calendar.startOfDay(for: $0.date) }).count
    }

    private static func exerciseDays(_ i: Input, ids: Set<String>) -> Int {
        dayCount(i, results: i.results.filter { ids.contains($0.exerciseID) && $0.passed && within(i, $0.date) })
    }

    /// Days in this stage with a session that spent 2+ minutes inside the resonance band.
    private static func resonanceDays(_ i: Input, band: ResonanceGoal) -> Int {
        let days = i.sessions.filter { within(i, $0.start) }.filter { session in
            session.cells.filter { inBand($0.resonanceBin, band) }.reduce(0) { $0 + $1.seconds } >= resonanceSecondsPerDay
        }
        return Set(days.map { i.calendar.startOfDay(for: $0.start) }).count
    }

    /// Share of voiced grid time inside the goal zone (pitch and/or resonance
    /// goal) over the past 7 days; nil without a goal or with too little data.
    static func zoneShareLastWeek(_ i: Input) -> Double? {
        guard i.goal != nil || i.resonanceGoal != nil else { return nil }
        let since = i.calendar.date(byAdding: .day, value: -6, to: i.calendar.startOfDay(for: i.now))!
        var total = 0.0, inside = 0.0
        for s in i.sessions where s.start >= since {
            for cell in s.cells {
                total += cell.seconds
                let pitchOK = i.goal?.contains(midi: cell.pitchMidi) ?? true
                let resOK = i.resonanceGoal.map { inBand(cell.resonanceBin, $0) } ?? true
                if pitchOK && resOK { inside += cell.seconds }
            }
        }
        return total >= zoneMinimumSeconds ? inside / total : nil
    }

    private static func inBand(_ bin: Int, _ band: ResonanceGoal) -> Bool {
        let centre = (Double(bin) + 0.5) * GridCellCount.resonanceBinWidth
        return centre >= band.low && centre <= band.high
    }

    // MARK: - Today's plan

    struct PlanItem: Identifiable, Hashable {
        enum Role: Hashable { case warmUp, stage, review, coolDown }
        let exercise: Exercise
        let role: Role
        var id: String { exercise.id }
    }

    struct Plan {
        let items: [PlanItem]
        /// Shortened (or empty) to fit what's left of the daily cap.
        let trimmed: Bool
        var minutes: Int { items.reduce(0) { $0 + $1.exercise.minutes } }
    }

    /// Warm-up → up to 3 stage exercises → 1 review from an earlier stage →
    /// cool-down, cut down to fit `remainingMinutes`. The review rotates daily.
    static func plan(stage: Stage, remainingMinutes: Int, on day: Date = .now, calendar: Calendar = .current) -> Plan {
        let pool = Exercise.all.filter { !$0.isWarmUp && !$0.isCoolDown }
        var core = Array(pool.filter { $0.stages.contains(stage.rawValue) }.prefix(3))
        let earlier = pool.filter { !$0.stages.contains(stage.rawValue) && ($0.stages.min() ?? 99) < stage.rawValue }
        var review: Exercise? = earlier.isEmpty ? nil
            : earlier[(calendar.ordinality(of: .day, in: .era, for: day) ?? 0) % earlier.count]

        guard let warm = Exercise.with(id: Exercise.warmUpID),
              let cool = Exercise.with(id: Exercise.coolDownID) else { return Plan(items: [], trimmed: false) }
        let fixed = warm.minutes + cool.minutes
        func total() -> Int { fixed + core.reduce(0) { $0 + $1.minutes } + (review?.minutes ?? 0) }

        var trimmed = false
        while total() > remainingMinutes, review != nil || core.count > 1 {
            trimmed = true
            if review != nil { review = nil } else { core.removeLast() }
        }
        if total() > remainingMinutes {
            return Plan(items: [], trimmed: true)
        }
        var items = [PlanItem(exercise: warm, role: .warmUp)]
        items += core.map { PlanItem(exercise: $0, role: .stage) }
        if let review { items.append(PlanItem(exercise: review, role: .review)) }
        items.append(PlanItem(exercise: cool, role: .coolDown))
        return Plan(items: items, trimmed: trimmed)
    }
}
