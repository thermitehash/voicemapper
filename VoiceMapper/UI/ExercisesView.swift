import SwiftUI
import SwiftData

/// Exercise list (docs/WIREFRAMES.md §5) with the S14 progression path and
/// Today's plan on top (docs/PROGRESSION.md).
struct ExercisesView: View {
    @Environment(StrainGuard.self) private var strain
    @AppStorage(StrainGuard.capKey) private var capMinutes = StrainGuard.defaultCapMinutes
    @AppStorage(ExerciseLog.key) private var log = ""
    @AppStorage(Progression.stageKey) private var stage = 1
    @AppStorage(Progression.stageStartKey) private var stageStart = 0.0
    @AppStorage(Progression.holdStepKey) private var holdStep = 0
    @AppStorage(GoalRange.lowKey) private var goalLow = 0
    @AppStorage(GoalRange.highKey) private var goalHigh = 0
    @AppStorage(ResonanceGoal.lowKey) private var resLow = 0.0
    @AppStorage(ResonanceGoal.highKey) private var resHigh = 0.0
    @Query(sort: \PracticeSession.start) private var sessions: [PracticeSession]
    @Query(sort: \ExerciseResult.date) private var results: [ExerciseResult]
    @Query private var strainEvents: [StrainEvent]
    @Query private var baselines: [Baseline]
    @State private var path = Self.initialPath

    /// Debug builds accept `-debugExercise hold` (an exercise id) to open it directly.
    private static var initialPath: [Exercise] {
        #if DEBUG
        let id = UserDefaults.standard.string(forKey: "debugExercise")
        return Exercise.all.filter { $0.id == id }
        #else
        return []
        #endif
    }

    private var done: Set<String> { ExerciseLog.done(in: log) }
    private var todayMinutes: Int { Int(strain.todaySeconds / 60) }
    private var currentStage: Stage { Progression.stage(stored: stage) }

    private var status: Progression.Status {
        Progression.status(Progression.Input(
            stage: currentStage,
            stageStart: stageStart > 0 ? Date(timeIntervalSince1970: stageStart) : nil,
            sessions: sessions, results: results, strainDates: strainEvents.map(\.date),
            baselineHz: baselines.first?.medianHz,
            goal: GoalRange(low: goalLow, high: goalHigh),
            resonanceGoal: ResonanceGoal(low: resLow, high: resHigh),
            holdStep: holdStep, capMinutes: capMinutes))
    }

    private var plan: Progression.Plan {
        Progression.plan(stage: currentStage, remainingMinutes: capMinutes - todayMinutes)
    }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text("Today")
                            Spacer()
                            Text("\(todayMinutes) / \(capMinutes) min").monospacedDigit()
                        }
                        ProgressView(value: min(Double(todayMinutes) / Double(max(capMinutes, 1)), 1))
                    }
                    .accessibilityElement(children: .combine)
                }

                Section {
                    StagePathHeader(stage: currentStage, status: status) {
                        if let next = currentStage.next { Progression.choose(next) }
                    }
                } footer: {
                    Text("Stages are suggestions. You can move to any stage in Settings ▸ Goal. Voice guidelines recommend seeing a speech-language pathologist at least once before changing your voice on your own.")
                }

                Section {
                    let plan = plan
                    if plan.items.isEmpty {
                        Text("You've reached today's practice cap. Rest is part of training. Come back tomorrow.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(plan.items) { item in
                            NavigationLink(value: item.exercise) {
                                ExerciseRow(exercise: item.exercise, isDone: done.contains(item.exercise.id),
                                            needsWarmUp: !done.contains(Exercise.warmUpID),
                                            role: item.role)
                            }
                        }
                    }
                } header: {
                    Text("Today's plan")
                } footer: {
                    if plan.trimmed && !plan.items.isEmpty {
                        Text("Shortened to fit what's left of your daily cap.")
                    }
                }

                Section {
                    ForEach(Exercise.all) { exercise in
                        NavigationLink(value: exercise) {
                            ExerciseRow(exercise: exercise, isDone: done.contains(exercise.id),
                                        needsWarmUp: !done.contains(Exercise.warmUpID))
                        }
                    }
                } header: {
                    Text("All exercises")
                } footer: {
                    Text("Stop if anything hurts or feels scratchy. Strain alerts and your daily cap stay on during exercises.")
                }
            }
            .navigationTitle("Exercises")
            .navigationDestination(for: Exercise.self) { ExerciseRunView(exercise: $0) }
        }
    }
}

private struct ExerciseRow: View {
    let exercise: Exercise
    let isDone: Bool
    let needsWarmUp: Bool
    var role: Progression.PlanItem.Role?

    private var roleLabel: String? {
        switch role {
        case .review: "Review"
        default: nil
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: exercise.symbol)
                .frame(width: 28)
                .foregroundStyle(.tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(exercise.title + (exercise.isWarmUp ? " (required)" : ""))
                    .font(.body.weight(exercise.isWarmUp && needsWarmUp ? .semibold : .regular))
                Text(roleLabel.map { "\($0) · \(exercise.summary)" } ?? exercise.summary)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if isDone {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                    .accessibilityLabel("Done today")
            } else {
                Text("\(exercise.minutes) min")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// The six stages as steps, the current one highlighted, and the
/// "ready to move on" checks in plain words.
private struct StagePathHeader: View {
    let stage: Stage
    let status: Progression.Status
    let moveOn: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 0) {
                ForEach(Stage.allCases) { s in
                    if s != .foundations {
                        Rectangle()
                            .fill(s.rawValue <= stage.rawValue ? Color.accentColor : Color.secondary.opacity(0.3))
                            .frame(height: 2)
                    }
                    ZStack {
                        Circle()
                            .fill(s == stage ? Color.accentColor
                                  : s.rawValue < stage.rawValue ? Color.accentColor.opacity(0.35) : Color.secondary.opacity(0.15))
                            .frame(width: 28, height: 28)
                        Text("\(s.rawValue)")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(s == stage ? Color.white : Color.primary)
                    }
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Stage \(stage.rawValue) of \(Stage.allCases.count)")

            VStack(alignment: .leading, spacing: 2) {
                Text("Stage \(stage.rawValue): \(stage.title)").font(.headline)
                Text(stage.focus).font(.subheadline).foregroundStyle(.secondary)
            }

            VStack(alignment: .leading, spacing: 6) {
                Text(stage.next == nil ? "Keeping it steady" : "Ready to move on when…")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                ForEach(status.criteria) { c in
                    Label {
                        Text(c.text).font(.subheadline)
                    } icon: {
                        Image(systemName: c.met ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(c.met ? Color.green : Color.secondary)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityValue(c.met ? "Done" : "Not yet")
                }
            }

            if status.ready, let next = stage.next {
                Button("Move on to stage \(next.rawValue): \(next.title)", action: moveOn)
                    .buttonStyle(.borderedProminent)
            }
        }
        .padding(.vertical, 4)
    }
}

/// Settings ▸ Goal ▸ Choose stage. Any stage, any time.
struct StagePickerView: View {
    @AppStorage(Progression.stageKey) private var stage = 1

    var body: some View {
        List {
            Section {
                ForEach(Stage.allCases) { s in
                    Button {
                        Progression.choose(s)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(s.rawValue). \(s.title)").foregroundStyle(.primary)
                                Text(s.focus).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if s.rawValue == stage {
                                Image(systemName: "checkmark").foregroundStyle(.tint)
                            }
                        }
                    }
                    .accessibilityAddTraits(s.rawValue == stage ? .isSelected : [])
                }
            } footer: {
                Text("Stages are suggestions, not locks. Choosing a stage restarts its ready-to-move-on checks from today.")
            }
        }
        .navigationTitle("Choose stage")
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview {
    ExercisesView()
        .environment(AudioEngine())
        .environment(StrainGuard())
}
