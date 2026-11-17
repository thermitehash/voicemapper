import SwiftUI

/// Feminine / masculine / androgynous / custom goal (PLAN §6). Used in
/// onboarding and from Settings ▸ Goal.
struct GoalPickerView: View {
    @AppStorage(GoalRange.lowKey) private var goalLow = 0
    @AppStorage(GoalRange.highKey) private var goalHigh = 0
    @AppStorage(ResonanceGoal.lowKey) private var resLow = 0.0
    @AppStorage(ResonanceGoal.highKey) private var resHigh = 0.0
    @State private var customChosen = false

    private var current: GoalPreset? {
        GoalPreset.matching(low: goalLow, high: goalHigh, resonanceLow: resLow, resonanceHigh: resHigh)
    }
    private var isCustom: Bool {
        current == nil && (customChosen || GoalRange(low: goalLow, high: goalHigh) != nil
                           || ResonanceGoal(low: resLow, high: resHigh) != nil)
    }

    var body: some View {
        Section {
            ForEach(GoalPreset.allCases) { preset in
                Button {
                    preset.apply()
                    customChosen = false
                } label: {
                    row(title: preset.title, detail: preset.summary, color: preset.zone.color,
                        selected: current == preset)
                }
            }
            Button {
                customChosen = true
            } label: {
                row(title: "Custom", detail: "Set your own range and resonance target", color: .gray,
                    selected: isCustom)
            }
        } footer: {
            VStack(alignment: .leading, spacing: 8) {
                if isCustom {
                    Text("Tap a note and then another on the Range tab to set your pitch range. Drag on the Live grid to set your resonance target. Your current settings are kept.")
                }
                Text("Listed alphabetically. These are typical ranges from voice research, not rules. Any voice can be your goal, and you can change it at any time. Pitch is only part of it: resonance and intonation matter too.")
            }
        }
    }

    private func row(title: String, detail: String, color: Color, selected: Bool) -> some View {
        HStack(spacing: 12) {
            Circle().fill(color).frame(width: 12, height: 12)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(.primary)
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if selected {
                Image(systemName: "checkmark").foregroundStyle(.tint).fontWeight(.semibold)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Settings ▸ Goal.
struct GoalSettingsView: View {
    @AppStorage(Progression.stageKey) private var stage = 1

    var body: some View {
        List {
            GoalPickerView()
            Section("Progression") {
                let current = Progression.stage(stored: stage)
                LabeledContent("Current stage", value: "\(current.rawValue). \(current.title)")
                NavigationLink("Choose stage") { StagePickerView() }
            }
        }
            .navigationTitle("Goal")
            .navigationBarTitleDisplayMode(.inline)
    }
}
