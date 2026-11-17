import Foundation

/// Goal presets from PLAN §6. Each one sets both the pitch goal (`GoalRange`)
/// and the resonance goal (`ResonanceGoal`) around the typical values of a
/// perceived-gender zone in `PerceivedGender`, so the numbers come from the same
/// research as the range table and grid. They're starting points; the user
/// can fine-tune them on the Range tab and the Live grid.
enum GoalPreset: String, CaseIterable, Identifiable, Sendable {
    case feminine, masculine, androgynous

    var id: String { rawValue }

    var title: String {
        switch self {
        case .feminine: "Feminine"
        case .masculine: "Masculine"
        case .androgynous: "Androgynous"
        }
    }

    var zone: PerceivedGender {
        switch self {
        case .feminine: .female
        case .masculine: .male
        case .androgynous: .ambiguous
        }
    }

    /// ±2 semitones around the zone's most common speaking pitch, trimmed so
    /// every note stays inside the zone.
    var range: GoalRange {
        let center = Note(frequency: zone.typicalFrequency).midi
        let notes = ((center - 2)...(center + 2)).filter {
            PerceivedGender(frequency: Note(midi: $0).frequency) == zone
        }
        return GoalRange(low: notes.first ?? center, high: notes.last ?? center)!
    }

    /// ±9 points (half a zone spread) around the zone's typical resonance.
    var resonance: ResonanceGoal {
        ResonanceGoal(low: zone.typicalResonance - 9, high: zone.typicalResonance + 9)!
    }

    var summary: String {
        let r = range
        return "\(Note(midi: r.low).name)–\(Note(midi: r.high).name) · resonance \(Int(resonance.low))–\(Int(resonance.high))"
    }

    /// Writes both goals to the same `@AppStorage` keys the rest of the app reads.
    func apply(to defaults: UserDefaults = .standard) {
        defaults.set(range.low, forKey: GoalRange.lowKey)
        defaults.set(range.high, forKey: GoalRange.highKey)
        defaults.set(resonance.low, forKey: ResonanceGoal.lowKey)
        defaults.set(resonance.high, forKey: ResonanceGoal.highKey)
    }

    /// The preset the stored goal matches, if any. Anything else is "custom".
    static func matching(low: Int, high: Int, resonanceLow: Double, resonanceHigh: Double) -> GoalPreset? {
        allCases.first {
            $0.range.low == low && $0.range.high == high
                && $0.resonance.low == resonanceLow && $0.resonance.high == resonanceHigh
        }
    }

    /// "Feminine", "Custom" or "Not set", for Settings.
    static func label(low: Int, high: Int, resonanceLow: Double, resonanceHigh: Double) -> String {
        if let p = matching(low: low, high: high, resonanceLow: resonanceLow, resonanceHigh: resonanceHigh) {
            return p.title
        }
        let anySet = GoalRange(low: low, high: high) != nil
            || ResonanceGoal(low: resonanceLow, high: resonanceHigh) != nil
        return anySet ? "Custom" : "Not set"
    }
}
