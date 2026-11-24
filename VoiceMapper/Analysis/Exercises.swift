import Foundation

/// The exercise library (docs/WIREFRAMES.md §5, extended in S14).
/// Instructions stay goal-neutral: they describe what to do, not what voice to aim for.
/// Every exercise carries a `basis`: the published research or recognised
/// speech-language pathology practice it rests on, checked in the S14 evidence review.
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

    /// Why this exercise: the evidence it rests on, shown in the run view.
    struct Basis: Hashable, Sendable {
        enum Strength: Hashable, Sendable {
            /// Tested in published studies.
            case research
            /// Standard in speech-language pathology practice and guidelines; little direct testing.
            case clinical
            /// Some related research but no direct support. Labelled as such.
            case limited

            var label: String {
                switch self {
                case .research: "Research-supported"
                case .clinical: "Recognised clinical practice"
                case .limited: "Limited research"
                }
            }
        }

        let strength: Strength
        /// One plain-language line.
        let summary: String
        let citations: [String]
    }

    let id: String
    let title: String
    let symbol: String
    let summary: String
    let kind: Kind
    /// Progression stages (1–6) this exercise belongs to. Warm-up and cool-down are every day.
    let stages: Set<Int>
    let basis: Basis
    /// Shows the resonance score while running (resonance and vowel drills).
    var showsResonance = false

    var isWarmUp: Bool { id == Self.warmUpID }
    var isCoolDown: Bool { id == Self.coolDownID }

    /// Rough length, for the list and the daily plan.
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
    static let holdID = "hold"
    static let readingID = "reading"
    static let intonationID = "intonation"

    static func with(id: String) -> Exercise? { all.first { $0.id == id } }

    // MARK: - Sources (checked in the S14 evidence review)

    enum Source {
        static let titze2006 = "Titze, I. R. (2006). Voice training and therapy with a semi-occluded vocal tract: Rationale and scientific underpinnings. Journal of Speech, Language, and Hearing Research, 49(2), 448–459."
        static let kapsnerSmith2015 = "Kapsner-Smith, M. R., Hunter, E. J., Kirkham, K., Cox, K., & Titze, I. R. (2015). A randomized controlled trial of two semi-occluded vocal tract voice therapy protocols. Journal of Speech, Language, and Hearing Research, 58(3), 535–549."
        static let stemple1994 = "Stemple, J. C., Lee, L., D'Amico, B., & Pickup, B. (1994). Efficacy of vocal function exercises as a method of improving voice production. Journal of Voice, 8(3), 271–278."
        static let verdolini1995 = "Verdolini-Marston, K., Burke, M. K., Lessac, A., Glaze, L., & Caldwell, E. (1995). Preliminary study of two methods of treatment for laryngeal nodules. Journal of Voice, 9(1), 74–85."
        static let yiu2017 = "Yiu, E. M.-L., Lo, M. C. M., & Barrett, E. A. (2017). A systematic review of resonant voice therapy. International Journal of Speech-Language Pathology, 19(1), 17–29."
        static let davies2015 = "Davies, S., Papp, V. G., & Antoni, C. (2015). Voice and communication change for gender nonconforming individuals: Giving voice to the person inside. International Journal of Transgenderism, 16(3), 117–159."
        static let carew2007 = "Carew, L., Dacakis, G., & Oates, J. (2007). The effectiveness of oral resonance therapy on the perception of femininity of voice in male-to-female transsexuals. Journal of Voice, 21(5), 591–603."
        static let kawitzky2020 = "Kawitzky, D., & McAllister, T. (2020). The effect of formant biofeedback on the feminization of voice in transgender women. Journal of Voice, 34(1), 53–67."
        static let hancock2014 = "Hancock, A. B., Colton, L., & Douglas, F. (2014). Intonation and gender perception: Applications for transgender speakers. Journal of Voice, 28(2), 203–209."
        static let hancock2013 = "Hancock, A. B., & Garabedian, L. M. (2013). Transgender voice and communication treatment: A retrospective chart review of 25 cases. International Journal of Language & Communication Disorders, 48(1), 54–65."
        static let leung2018 = "Leung, Y., Oates, J., & Chan, S. P. (2018). Voice, articulation, and prosody contribute to listener perceptions of speaker gender: A systematic review and meta-analysis. Journal of Speech, Language, and Hearing Research, 61(2), 266–297."
        static let boone1993 = "Boone, D. R., & McFarlane, S. C. (1993). A critical view of the yawn-sigh as a voice therapy technique. Journal of Voice, 7(1), 75–80."
        static let ragan2016 = "Ragan, K. (2016). The impact of vocal cool-down exercises: A subjective study of singers' and listeners' perceptions. Journal of Voice, 30(6), 764.e1–764.e9."
        static let zhang2022 = "Zhang, Z., Zhang, J., & Kreiman, J. (2022). Effects of laryngeal manipulations on voice gender perception. Proc. Interspeech 2022, 1856–1860."
        static let fairbanks1960 = "Fairbanks, G. (1960). Voice and Articulation Drillbook (2nd ed.). Harper & Row."
    }

    // MARK: - Library

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
            ]),
            stages: [1, 2, 3, 4, 5, 6],
            basis: Basis(
                strength: .research,
                summary: "Hums, trills and sirens are semi-occluded vocal tract exercises: partly closing the lips makes voicing easier and lowers how hard the vocal folds collide. A trial in adults with tired or hoarse voices found that this kind of practice helped. The yawn-sigh widens the throat (seen on endoscopy). The breathing step is common practice but has little direct evidence.",
                citations: [Source.titze2006, Source.kapsnerSmith2015, Source.boone1993])),
        Exercise(
            id: "hums", title: "Easy hums into words", symbol: "waveform",
            summary: "Feel the buzz at the front of your face, then carry it into words.",
            kind: .guided([
                Step(text: "Say \"mm-hmm\" as if agreeing with someone, easily and at a comfortable volume. Notice the buzz on your lips.", seconds: 30),
                Step(text: "Hum \"mmm\" and open into a vowel: \"mmm-mee\", \"mmm-may\", \"mmm-moh\". Keep the buzz as you open.", seconds: 40),
                Step(text: "Hum into words: \"many\", \"money\", \"morning\", \"mellow\". Easy and light, no pushing.", seconds: 40),
                Step(text: "Short phrases with the same easy buzz: \"my mum\", \"more milk\", \"may I\".", seconds: 40),
            ]),
            stages: [1],
            basis: Basis(
                strength: .research,
                summary: "Resonant voice therapy aims for an easy voice with vibration felt at the front of the mouth, built up from hums into words and phrases. A systematic review rated its evidence as moderate. The studies treated voice problems (an early trial: 13 women with vocal nodules), not voice training for gender.",
                citations: [Source.verdolini1995, Source.yiu2017])),
        Exercise(
            id: "slides", title: "Pitch slides", symbol: "arrow.up.right",
            summary: "Smooth glides through your range on \"ng\".",
            kind: .guided([
                Step(text: "Slide on \"ng\" (as in \"sing\") from low to high and back down, slowly and smoothly.", seconds: 30),
                Step(text: "Slide from where you are now towards your goal notes and pause there briefly.", seconds: 30),
                Step(text: "Small slides around your goal: up a little, back, down a little, back.", seconds: 30),
            ]),
            stages: [1, 2],
            basis: Basis(
                strength: .research,
                summary: "Glides are part of Vocal Function Exercises, which improved voice production in a controlled trial of 35 women with healthy voices. Gliding on a semi-occluded sound like \"ng\" is a recognised way to work the middle and upper range without strain.",
                citations: [Source.stemple1994, Source.titze2006, Source.davies2015])),
        Exercise(
            id: holdID, title: "Hold target pitch", symbol: "scope",
            summary: "Hold your target note steadily for 10 seconds, three times.",
            kind: .hold(seconds: 10, reps: 3),
            stages: [2, 4],
            basis: Basis(
                strength: .clinical,
                summary: "In clinical guidance for trans and gender-diverse people, clinicians pick a training pitch that's easy to produce and practise holding it. They often start near your usual pitch and move it gradually, a step at a time. Sustained notes are also part of Vocal Function Exercises.",
                citations: [Source.davies2015, Source.stemple1994])),
        Exercise(
            id: "forwardback", title: "Resonance: forward ↔ back", symbol: "mouth",
            summary: "Move your tongue and lips to change how bright or dark you sound.",
            kind: .guided([
                Step(text: "Say \"ee\" with your tongue high and forward and your lips slightly spread. Watch the score.", seconds: 20),
                Step(text: "Now a deep \"oo\": tongue pulled well back, lips rounded. Your everyday \"oo\" may sit further forward. Watch the score move.", seconds: 20),
                Step(text: "Glide slowly between the two shapes on one note. Notice what changes in your mouth.", seconds: 30),
                Step(text: "Pick the tongue and lip shape you're aiming for and keep it while you say \"hee\", \"hey\", \"hi\".", seconds: 30),
            ]),
            stages: [3],
            basis: Basis(
                strength: .research,
                summary: "Tongue position and lip shape change the vocal tract resonances (formants). In a clinical study of 10 trans women, forward tongue carriage and slightly spread lips raised all three formants. Rounding the lips and drawing the tongue back works the other way by the same physics, but no trial has tested it for darkening a voice. This replaces the \"big dog / small dog\" drill, which comes from online voice-training communities and has no published support.",
                citations: [Source.carew2007, Source.davies2015]),
            showsResonance: true),
        Exercise(
            id: "vowels", title: "Vowel ladder (ee/ah)", symbol: "textformat.abc",
            summary: "Sustained vowels on one note, keeping resonance steady.",
            kind: .guided([
                Step(text: "Sustain \"ee\" on a comfortable note near your goal. Hold it steady, without gliding.", seconds: 15),
                Step(text: "Same note, now \"ah\". Try to keep the resonance score close to your \"ee\".", seconds: 15),
                Step(text: "Back to \"ee\".", seconds: 15),
                Step(text: "\"ah\" again, keeping the same mouth space.", seconds: 15),
                Step(text: "Glide slowly \"ee-ah-ee-ah\" on one breath.", seconds: 20),
            ]),
            stages: [3, 4, 6],
            basis: Basis(
                strength: .research,
                summary: "Using one vowel's mouth shape as a model for the others is a described clinical technique. On-screen formant feedback, like the resonance meter here, helped trans women brighten their resonance in a small study.",
                citations: [Source.davies2015, Source.kawitzky2020]),
            showsResonance: true),
        Exercise(
            id: "phrases", title: "Words and phrases", symbol: "text.bubble",
            summary: "Keep pitch and resonance together from single words to short phrases.",
            kind: .guided([
                Step(text: "Find your practice note with a short hum. Then say everyday words on it: \"hi\", \"bye\", \"yes\", \"no\", \"thanks\".", seconds: 30),
                Step(text: "Same words, now spoken naturally, letting the pitch move a little. Keep the resonance you're working on.", seconds: 30),
                Step(text: "Two- and three-word phrases: \"good morning\", \"see you soon\", \"how are you?\"", seconds: 40),
                Step(text: "Longer phrases you actually say, like your name, ordering a coffee, or answering the phone.", seconds: 40),
            ]),
            stages: [4, 5],
            basis: Basis(
                strength: .clinical,
                summary: "Clinical guidance for trans and gender-diverse people builds from sounds to words to longer phrases while keeping the voice easy. Practising everyday words and real-life phrases is a recommended way to carry the voice into daily life.",
                citations: [Source.davies2015]),
            showsResonance: true),
        Exercise(
            id: readingID, title: "Reading: Rainbow Passage", symbol: "book",
            summary: "Read aloud and carry your practice into speech.",
            kind: .reading(seconds: 45),
            stages: [5, 6],
            basis: Basis(
                strength: .clinical,
                summary: "The Rainbow Passage is a standard text for voice assessment. Reading aloud is the step between phrases and free conversation in clinical guidance for trans and gender-diverse voice training.",
                citations: [Source.fairbanks1960, Source.davies2015])),
        Exercise(
            id: intonationID, title: "Intonation", symbol: "point.topleft.down.to.point.bottomright.curvepath",
            summary: "Explore how your pitch rises and falls across a sentence.",
            kind: .guided([
                Step(text: "Say \"Really\" three ways: as a question (rising), as a statement (falling), and surprised (up and down).", seconds: 30),
                Step(text: "\"It's a lovely day.\" Say it flat, then let the pitch move more. Notice how each version feels.", seconds: 30),
                Step(text: "\"Are you coming tonight?\" Ask with a rise at the end, then say \"I'm coming tonight\" with a fall.", seconds: 30),
                Step(text: "Tell a short story about your day, using the amount of melody that feels like you.", seconds: 40),
            ]),
            stages: [5],
            basis: Basis(
                strength: .research,
                summary: "Intonation affects how voices are perceived. In a study of cis and trans speakers, listeners heard speakers with more upward glides and a wider semitone range as female. Other studies found the opposite, so there's no single right amount. The app shows your range but doesn't set a target.",
                citations: [Source.hancock2014, Source.leung2018])),
        Exercise(
            id: "weight", title: "Lighter ↔ heavier", symbol: "scalemass",
            summary: "Contrast a lighter and a heavier voice quality on the same note.",
            kind: .guided([
                Step(text: "On a comfortable note, say \"hey\" with a light, gentle start, like calling softly across a room.", seconds: 20),
                Step(text: "Same note and volume, now a fuller, heavier \"hey\". Don't push or get louder.", seconds: 20),
                Step(text: "Go slowly from light to full and back on \"mmm-ah\". Notice what changes in your throat.", seconds: 30),
                Step(text: "Pick the weight you're aiming for and keep it on a few words: \"hello\", \"lovely\", \"any time\".", seconds: 30),
            ]),
            stages: [6],
            basis: Basis(
                strength: .limited,
                summary: "Vocal weight is mostly a voice-training idea. A computer-model study suggests that voice quality (spectral shape) affects gender perception alongside pitch, and a review found mixed results for breathiness. No study has tested a weight drill directly. Never press or squeeze to get a heavier sound.",
                citations: [Source.zhang2022, Source.leung2018, Source.davies2015])),
        Exercise(
            id: coolDownID, title: "Cool-down", symbol: "moon.stars",
            summary: "Gentle downward hums and trills to finish.",
            kind: .guided([
                Step(text: "Hum gently on a low, comfortable note, sliding downward.", seconds: 30),
                Step(text: "Lip trills sliding from a middle note down to a low one.", seconds: 30),
                Step(text: "Yawn-sigh downward a few times.", seconds: 30),
                Step(text: "Say a few words in your relaxed, natural voice. Then rest and sip some water.", seconds: 20),
            ]),
            stages: [1, 2, 3, 4, 5, 6],
            basis: Basis(
                strength: .limited,
                summary: "Singers report that cool-downs help, but objective measures haven't shown a clear effect yet. The hums and trills used here are the same semi-occluded exercises as the warm-up.",
                citations: [Source.ragan2016, Source.titze2006])),
    ]
}

enum RainbowPassage {
    static let text = "When the sunlight strikes raindrops in the air, they act as a prism and form a rainbow. The rainbow is a division of white light into many beautiful colours. These take the shape of a long round arch, with its path high above, and its two ends apparently beyond the horizon. There is, according to legend, a boiling pot of gold at one end. People look, but no one ever finds it. When a man looks for something beyond his reach, his friends say he is looking for the pot of gold at the end of the rainbow."
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
