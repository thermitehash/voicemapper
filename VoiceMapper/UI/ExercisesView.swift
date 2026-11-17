import SwiftUI

/// Exercise list. docs/WIREFRAMES.md §5.
struct ExercisesView: View {
    @Environment(StrainGuard.self) private var strain
    @AppStorage(StrainGuard.capKey) private var capMinutes = StrainGuard.defaultCapMinutes
    @AppStorage(ExerciseLog.key) private var log = ""
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
                    ForEach(Exercise.all) { exercise in
                        NavigationLink(value: exercise) {
                            ExerciseRow(exercise: exercise, isDone: done.contains(exercise.id),
                                        needsWarmUp: !done.contains(Exercise.warmUpID))
                        }
                    }
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

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: exercise.symbol)
                .frame(width: 28)
                .foregroundStyle(.tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(exercise.title + (exercise.isWarmUp ? " (required)" : ""))
                    .font(.body.weight(exercise.isWarmUp && needsWarmUp ? .semibold : .regular))
                Text(exercise.summary)
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

#Preview {
    ExercisesView()
        .environment(AudioEngine())
        .environment(StrainGuard())
}
