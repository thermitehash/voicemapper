import Foundation

/// The exercise list from docs/WIREFRAMES.md §5, in the order shown.
/// Instructions stay goal-neutral: they describe what to do, not what voice to aim for.
struct Exercise: Identifiable, Hashable, Sendable {
    enum Kind: Hashable, Sendable {
        /// Timed steps with an instruction each; time runs while the mic is on.
        case guided([Step])
        /// Hold the target note for `seconds`, `reps` times. WIREFRAMES §6.
        case hold(seconds: Double, reps: Int)
        /// Read the Rainbow Passage aloud for `seconds` of voiced time.
        case reading(seconds: Double)
    }

    struct Step: Hashable, Sendable {
        let text: String
        let seconds: Double
    }

    let id: String
    let title: String
    let symbol: String
    let summary: String
    let kind: Kind
    /// Shows the resonance score while running (resonance and vowel drills).
    var showsResonance = false

    var isWarmUp: Bool { id == Self.warmUpID }
    var isCoolDown: Bool { id == Self.coolDownID }

    /// Rough length, for the list.
    var minutes: Int {
        let seconds: Double = switch kind {
        case .guided(let steps): steps.reduce(0) { $0 + $1.seconds }
        case .hold(let s, let reps): s * Double(reps) * 2
        case .reading(let s): s * 1.5
        }
        return max(1, Int((seconds / 60).rounded()))
    }

    static let warmUpID = "warmup"
    static let coolDownID = "cooldown"

    static let all: [Exercise] = [
        Exercise(
            id: warmUpID, title: "Warm-up", symbol: "sun.horizon",
            summary: "Breath, hums, trills and easy sirens. Do this first each day.",
            kind: .guided([
                Step(text: "Breathe in through your nose for 4, then out on a gentle \"sss\" for 8. Keep your shoulders loose.", seconds: 30),
                Step(text: "Hum on a comfortable note with your lips lightly closed. Feel the buzz in your lips and nose.", seconds: 30),
                Step(text: "Lip trills: blow through loose lips so they flutter, then add a little voice. Tongue trills work too.", seconds: 30),
                Step(text: "Siren gently up and down on a hum or trill. Stay comfortable and don't push at the top.", seconds: 40),
                Step(text: "Yawn-sigh: start a yawn, then sigh gently from a middle note down to a low one.", seconds: 30),
            ])),
        Exercise(
            id: "slides", title: "Pitch slides", symbol: "arrow.up.right",
            summary: "Smooth glides through your range on \"ng\".",
            kind: .guided([
                Step(text: "Slide on \"ng\" (as in \"sing\") from low to high and back down, slowly and smoothly.", seconds: 30),
                Step(text: "Slide from where you are now towards your goal notes and pause there briefly.", seconds: 30),
                Step(text: "Small slides around your goal: up a little, back, down a little, back.", seconds: 30),
            ])),
        Exercise(
            id: "hold", title: "Hold target pitch", symbol: "scope",
            summary: "Hold your target note steadily for 10 seconds, three times.",
            kind: .hold(seconds: 10, reps: 3)),
        Exercise(
            id: "dog", title: "Resonance: big → small dog", symbol: "pawprint",
            summary: "Feel the space in your throat change from dark to bright.",
            kind: .guided([
                Step(text: "Pant like a big dog: tongue relaxed, throat wide, a dark \"huh huh\". Watch the score move down.", seconds: 20),
                Step(text: "Now pant like a small dog: slight smile, tongue forward and high, a bright \"hee hee\". Watch it move up.", seconds: 20),
                Step(text: "Switch slowly between big and small dog. Notice what changes in your throat and mouth.", seconds: 30),
                Step(text: "Keep the shape you're aiming for and say \"hee\", \"hey\", \"hi\".", seconds: 30),
            ]),
            showsResonance: true),
        Exercise(
            id: "vowels", title: "Vowel ladder (ee/ah)", symbol: "textformat.abc",
            summary: "Sustained vowels on one note, keeping resonance steady.",
            kind: .guided([
                Step(text: "Sustain \"ee\" on a comfortable note near your goal.", seconds: 15),
                Step(text: "Same note, now \"ah\". Try to keep the resonance score close to your \"ee\".", seconds: 15),
                Step(text: "Back to \"ee\".", seconds: 15),
                Step(text: "\"ah\" again, keeping the same mouth space.", seconds: 15),
                Step(text: "Glide slowly \"ee-ah-ee-ah\" on one breath.", seconds: 20),
            ]),
            showsResonance: true),
        Exercise(
            id: "reading", title: "Reading: Rainbow Passage", symbol: "book",
            summary: "Read aloud and carry your practice into speech.",
            kind: .reading(seconds: 45)),
        Exercise(
            id: coolDownID, title: "Cool-down", symbol: "moon.stars",
            summary: "Gentle downward hums and trills to finish.",
            kind: .guided([
                Step(text: "Hum gently on a low, comfortable note, sliding downward.", seconds: 30),
                Step(text: "Lip trills sliding from a middle note down to a low one.", seconds: 30),
                Step(text: "Yawn-sigh downward a few times.", seconds: 30),
                Step(text: "Say a few words in your relaxed, natural voice. Then rest and sip some water.", seconds: 20),
            ])),
    ]
}

enum RainbowPassage {
    static let text = "When the sunlight strikes raindrops in the air, they act as a prism and form a rainbow. The rainbow is a division of white light into many beautiful colors. These take the shape of a long round arch, with its path high above, and its two ends apparently beyond the horizon. There is, according to legend, a boiling pot of gold at one end. People look, but no one ever finds it. When a man looks for something beyond his reach, his friends say he is looking for the pot of gold at the end of the rainbow."
    static let citation = "The Rainbow Passage (first paragraph), from Fairbanks, G. (1960), Voice and Articulation Drillbook, 2nd ed., Harper & Row."
}

/// Which exercises are done today, stored as one `@AppStorage` string
/// ("yyyy-MM-dd|id,id"), so it resets itself each day.
enum ExerciseLog {
    static let key = "exercises.doneToday"

    static func done(in stored: String, on date: Date = .now) -> Set<String> {
        let parts = stored.split(separator: "|", maxSplits: 1).map(String.init)
        guard parts.count == 2, parts[0] == dayStamp(date) else { return [] }
        return Set(parts[1].split(separator: ",").map(String.init))
    }

    static func marking(_ id: String, in stored: String, on date: Date = .now) -> String {
        let ids = done(in: stored, on: date).union([id])
        return dayStamp(date) + "|" + ids.sorted().joined(separator: ",")
    }

    private static func dayStamp(_ date: Date) -> String {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }
}
