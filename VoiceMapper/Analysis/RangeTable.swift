import Foundation
import SwiftUI

/// How a speaking voice at a given pitch is typically perceived. These are
/// typical ranges from speaking-F0 research (Gelfer & Mikos 2005; Hillenbrand &
/// Clark 2009), not rules: resonance and intonation matter too.
enum PerceivedGender: Sendable {
    case male, ambiguous, female

    /// Single-letter label so colour is never the only cue.
    var letter: String {
        switch self {
        case .male: "M"
        case .ambiguous: "A"
        case .female: "F"
        }
    }

    var meaning: String {
        switch self {
        case .male: "usually read male"
        case .ambiguous: "ambiguous / androgynous"
        case .female: "usually read female"
        }
    }

    /// Most common speaking F0 for the zone (Hz), where its colour is strongest.
    var typicalFrequency: Double {
        switch self {
        case .male: 120
        case .ambiguous: 160
        case .female: 210
        }
    }

    /// Pure zone colour as RGB, shared with the blended grid shading.
    var rgb: (Double, Double, Double) {
        if Palette.colourBlind {
            // Okabe & Ito (2008) blue / grey / orange: distinct under the
            // common colour-vision deficiencies, and in greyscale by lightness.
            switch self {
            case .male: return (0.0, 0.447, 0.698)
            case .ambiguous: return (0.6, 0.6, 0.6)
            case .female: return (0.902, 0.624, 0.0)
            }
        }
        switch self {
        case .male: return (0.0, 0.48, 1.0)
        case .ambiguous: return (0.69, 0.32, 0.87)
        case .female: return (1.0, 0.45, 0.75)
        }
    }

    var color: Color { Color(red: rgb.0, green: rgb.1, blue: rgb.2) }

    /// Resonance score (0–100) where the zone is most common, from average ΔF
    /// of male / female voices (Hillenbrand et al. 1995; see `ResonanceScorer`).
    var typicalResonance: Double {
        switch self {
        case .male: 25
        case .ambiguous: 50
        case .female: 72
        }
    }

    /// 0.15–1: how typical a resonance score is within the zone.
    func resonanceCommonness(at score: Double) -> Double {
        let d = (score - typicalResonance) / 18
        return max(0.15, exp(-d * d / 2))
    }

    /// 0.2–1: how typical a pitch is within the zone, fading with distance in
    /// semitones from the most common speaking pitch.
    func commonness(at frequency: Double) -> Double {
        let semitones = 12 * log2(frequency / typicalFrequency)
        return max(0.2, exp(-semitones * semitones / (2 * 3 * 3)))
    }

    init(frequency: Double) {
        switch frequency {
        case ..<145: self = .male
        case ..<175: self = .ambiguous
        default: self = .female
        }
    }
}

/// Settings ▸ Colour-blind palette. Cached in a static because zone colours
/// are read for every grid cell on every redraw; RootTabView keeps it in sync
/// with the stored setting and rebuilds the tabs when it changes.
enum Palette {
    static let key = "display.colourBlindPalette"
    nonisolated(unsafe) static var colourBlind = UserDefaults.standard.bool(forKey: key)

    /// Heat-map fill: orange normally; with the colour-blind palette orange is
    /// a zone colour, so the heat map switches to the primary colour.
    static var heat: Color { colourBlind ? .primary : .orange }
}

/// Classical voice type whose range the note most typically sits in.
enum VoiceType: String, Sendable {
    case bass = "Bass"
    case baritone = "Bari"
    case tenor = "Tenor"
    case alto = "Alto"
    case mezzo = "Mezzo"
    case soprano = "Sop"

    init(midi: Int) {
        switch midi {
        case ..<48: self = .bass       // below C3
        case 48..<52: self = .baritone // C3–D♯3
        case 52..<55: self = .tenor    // E3–F♯3
        case 55..<65: self = .alto     // G3–E4
        case 65..<69: self = .mezzo    // F4–G♯4
        default: self = .soprano       // A4 and up
        }
    }
}

struct RangeRow: Identifiable, Sendable {
    let note: Note
    var id: Int { note.midi }
    var frequency: Double { note.frequency }
    var voiceType: VoiceType { VoiceType(midi: note.midi) }
    var perceived: PerceivedGender { PerceivedGender(frequency: note.frequency) }
    var commonness: Double { perceived.commonness(at: note.frequency) }
}

enum RangeTable {
    /// Natural notes only (no sharps/flats) from E2 (82 Hz) to G4 (392 Hz), the span of speaking pitch, highest first, so the
    /// whole table fits on one screen without scrolling.
    static let rows: [RangeRow] = (40...67).reversed()
        .map { Note(midi: $0) }
        .filter { !$0.name.contains("♯") }
        .map { RangeRow(note: $0) }

    /// The row nearest a live reading, clamped to the table's ends.
    static func rowID(for note: Note) -> Int {
        rows.min { abs($0.id - note.midi) < abs($1.id - note.midi) }!.id
    }
}
