package com.thrivestorm2.voicemapper.core.analysis

/**
 * The exercise library. Port of iOS `Analysis/Exercises.swift`: the wording is
 * copied exactly, and `ParityTest` checks every string against the Swift file,
 * so the iOS app stays the source of truth. Instructions stay goal-neutral.
 */
data class Exercise(
    val id: String,
    val title: String,
    /** SF Symbol name on iOS; the Android UI maps it to a Material icon. */
    val symbol: String,
    val summary: String,
    val kind: Kind,
    val stages: Set<Int>,
    val basis: Basis,
    val showsResonance: Boolean = false,
) {
    sealed interface Kind {
        data class Guided(val steps: List<Step>) : Kind
        data class Hold(val seconds: Double, val reps: Int) : Kind
        data class Reading(val seconds: Double) : Kind
    }

    data class Step(val text: String, val seconds: Double)

    data class Basis(val strength: Strength, val summary: String, val citations: List<String>) {
        enum class Strength(val label: String) {
            RESEARCH("Research-supported"),
            CLINICAL("Recognised clinical practice"),
            LIMITED("Limited research"),
        }
    }

    val isWarmUp get() = id == WARM_UP_ID
    val isCoolDown get() = id == COOL_DOWN_ID

    /** Rough length, for the list and the daily plan. */
    val minutes: Int get() {
        val seconds = when (kind) {
            is Kind.Guided -> kind.steps.sumOf { it.seconds }
            is Kind.Hold -> kind.seconds * kind.reps * 2
            is Kind.Reading -> kind.seconds * 1.5
        }
        return maxOf(1, swiftRound(seconds / 60))
    }

    object Source {
        const val TITZE_2006 = "Titze, I. R. (2006). Voice training and therapy with a semi-occluded vocal tract: Rationale and scientific underpinnings. Journal of Speech, Language, and Hearing Research, 49(2), 448–459."
        const val KAPSNER_SMITH_2015 = "Kapsner-Smith, M. R., Hunter, E. J., Kirkham, K., Cox, K., & Titze, I. R. (2015). A randomized controlled trial of two semi-occluded vocal tract voice therapy protocols. Journal of Speech, Language, and Hearing Research, 58(3), 535–549."
        const val STEMPLE_1994 = "Stemple, J. C., Lee, L., D'Amico, B., & Pickup, B. (1994). Efficacy of vocal function exercises as a method of improving voice production. Journal of Voice, 8(3), 271–278."
        const val VERDOLINI_1995 = "Verdolini-Marston, K., Burke, M. K., Lessac, A., Glaze, L., & Caldwell, E. (1995). Preliminary study of two methods of treatment for laryngeal nodules. Journal of Voice, 9(1), 74–85."
        const val YIU_2017 = "Yiu, E. M.-L., Lo, M. C. M., & Barrett, E. A. (2017). A systematic review of resonant voice therapy. International Journal of Speech-Language Pathology, 19(1), 17–29."
        const val DAVIES_2015 = "Davies, S., Papp, V. G., & Antoni, C. (2015). Voice and communication change for gender nonconforming individuals: Giving voice to the person inside. International Journal of Transgenderism, 16(3), 117–159."
        const val CAREW_2007 = "Carew, L., Dacakis, G., & Oates, J. (2007). The effectiveness of oral resonance therapy on the perception of femininity of voice in male-to-female transsexuals. Journal of Voice, 21(5), 591–603."
        const val KAWITZKY_2020 = "Kawitzky, D., & McAllister, T. (2020). The effect of formant biofeedback on the feminization of voice in transgender women. Journal of Voice, 34(1), 53–67."
        const val HANCOCK_2014 = "Hancock, A. B., Colton, L., & Douglas, F. (2014). Intonation and gender perception: Applications for transgender speakers. Journal of Voice, 28(2), 203–209."
        const val LEUNG_2018 = "Leung, Y., Oates, J., & Chan, S. P. (2018). Voice, articulation, and prosody contribute to listener perceptions of speaker gender: A systematic review and meta-analysis. Journal of Speech, Language, and Hearing Research, 61(2), 266–297."
        const val BOONE_1993 = "Boone, D. R., & McFarlane, S. C. (1993). A critical view of the yawn-sigh as a voice therapy technique. Journal of Voice, 7(1), 75–80."
        const val RAGAN_2016 = "Ragan, K. (2016). The impact of vocal cool-down exercises: A subjective study of singers' and listeners' perceptions. Journal of Voice, 30(6), 764.e1–764.e9."
        const val ZHANG_2022 = "Zhang, Z., Zhang, J., & Kreiman, J. (2022). Effects of laryngeal manipulations on voice gender perception. Proc. Interspeech 2022, 1856–1860."
        const val FAIRBANKS_1960 = "Fairbanks, G. (1960). Voice and Articulation Drillbook (2nd ed.). Harper & Row."
    }

    companion object {
        const val WARM_UP_ID = "warmup"
        const val COOL_DOWN_ID = "cooldown"
        const val HOLD_ID = "hold"
        const val READING_ID = "reading"
        const val INTONATION_ID = "intonation"

        fun with(id: String): Exercise? = all.firstOrNull { it.id == id }

        private val everyStage = (1..6).toSet()
        private fun guided(vararg steps: Pair<String, Int>) = Kind.Guided(steps.map { Step(it.first, it.second.toDouble()) })

        val all: List<Exercise> = listOf(
            Exercise(
                WARM_UP_ID, "Warm-up", "sun.horizon",
                "Breath, hums, trills and easy sirens. Do this first each day.",
                guided(
                    "Breathe in through your nose for 4, then out on a gentle \"sss\" for 8. Keep your shoulders loose." to 30,
                    "Hum on a comfortable note with your lips lightly closed. Feel the buzz in your lips and nose." to 30,
                    "Lip trills: blow through loose lips so they flutter, then add a little voice. Tongue trills work too." to 30,
                    "Siren gently up and down on a hum or trill. Stay comfortable and don't push at the top." to 40,
                    "Yawn-sigh: start a yawn, then sigh gently from a middle note down to a low one." to 30,
                ),
                everyStage,
                Basis(Basis.Strength.RESEARCH,
                    "Hums, trills and sirens are semi-occluded vocal tract exercises: partly closing the lips makes voicing easier and lowers how hard the vocal folds collide. A trial in adults with tired or hoarse voices found that this kind of practice helped. The yawn-sigh widens the throat (seen on endoscopy). The breathing step is common practice but has little direct evidence.",
                    listOf(Source.TITZE_2006, Source.KAPSNER_SMITH_2015, Source.BOONE_1993)),
            ),
            Exercise(
                "hums", "Easy hums into words", "waveform",
                "Feel the buzz at the front of your face, then carry it into words.",
                guided(
                    "Say \"mm-hmm\" as if agreeing with someone, easily and at a comfortable volume. Notice the buzz on your lips." to 30,
                    "Hum \"mmm\" and open into a vowel: \"mmm-mee\", \"mmm-may\", \"mmm-moh\". Keep the buzz as you open." to 40,
                    "Hum into words: \"many\", \"money\", \"morning\", \"mellow\". Easy and light, no pushing." to 40,
                    "Short phrases with the same easy buzz: \"my mum\", \"more milk\", \"may I\"." to 40,
                ),
                setOf(1),
                Basis(Basis.Strength.RESEARCH,
                    "Resonant voice therapy aims for an easy voice with vibration felt at the front of the mouth, built up from hums into words and phrases. A systematic review rated its evidence as moderate. The studies treated voice problems (an early trial: 13 women with vocal nodules), not voice training for gender.",
                    listOf(Source.VERDOLINI_1995, Source.YIU_2017)),
            ),
            Exercise(
                "slides", "Pitch slides", "arrow.up.right",
                "Smooth glides through your range on \"ng\".",
                guided(
                    "Slide on \"ng\" (as in \"sing\") from low to high and back down, slowly and smoothly." to 30,
                    "Slide from where you are now towards your goal notes and pause there briefly." to 30,
                    "Small slides around your goal: up a little, back, down a little, back." to 30,
                ),
                setOf(1, 2),
                Basis(Basis.Strength.RESEARCH,
                    "Glides are part of Vocal Function Exercises, which improved voice production in a controlled trial of 35 women with healthy voices. Gliding on a semi-occluded sound like \"ng\" is a recognised way to work the middle and upper range without strain.",
                    listOf(Source.STEMPLE_1994, Source.TITZE_2006, Source.DAVIES_2015)),
            ),
            Exercise(
                HOLD_ID, "Hold target pitch", "scope",
                "Hold your target note steadily for 10 seconds, three times.",
                Kind.Hold(10.0, 3),
                setOf(2, 4),
                Basis(Basis.Strength.CLINICAL,
                    "In clinical guidance for trans and gender-diverse people, clinicians pick a training pitch that's easy to produce and practise holding it. They often start near your usual pitch and move it gradually, a step at a time. Sustained notes are also part of Vocal Function Exercises.",
                    listOf(Source.DAVIES_2015, Source.STEMPLE_1994)),
            ),
            Exercise(
                "forwardback", "Resonance: forward ↔ back", "mouth",
                "Move your tongue and lips to change how bright or dark you sound.",
                guided(
                    "Say \"ee\" with your tongue high and forward and your lips slightly spread. Watch the score." to 20,
                    "Now a deep \"oo\": tongue pulled well back, lips rounded. Your everyday \"oo\" may sit further forward. Watch the score move." to 20,
                    "Glide slowly between the two shapes on one note. Notice what changes in your mouth." to 30,
                    "Pick the tongue and lip shape you're aiming for and keep it while you say \"hee\", \"hey\", \"hi\"." to 30,
                ),
                setOf(3),
                Basis(Basis.Strength.RESEARCH,
                    "Tongue position and lip shape change the vocal tract resonances (formants). In a clinical study of 10 trans women, forward tongue carriage and slightly spread lips raised all three formants. Rounding the lips and drawing the tongue back works the other way by the same physics, but no trial has tested it for darkening a voice. This replaces the \"big dog / small dog\" drill, which comes from online voice-training communities and has no published support.",
                    listOf(Source.CAREW_2007, Source.DAVIES_2015)),
                showsResonance = true,
            ),
            Exercise(
                "vowels", "Vowel ladder (ee/ah)", "textformat.abc",
                "Sustained vowels on one note, keeping resonance steady.",
                guided(
                    "Sustain \"ee\" on a comfortable note near your goal. Hold it steady, without gliding." to 15,
                    "Same note, now \"ah\". Try to keep the resonance score close to your \"ee\"." to 15,
                    "Back to \"ee\"." to 15,
                    "\"ah\" again, keeping the same mouth space." to 15,
                    "Glide slowly \"ee-ah-ee-ah\" on one breath." to 20,
                ),
                setOf(3, 4, 6),
                Basis(Basis.Strength.RESEARCH,
                    "Using one vowel's mouth shape as a model for the others is a described clinical technique. On-screen formant feedback, like the resonance meter here, helped trans women brighten their resonance in a small study.",
                    listOf(Source.DAVIES_2015, Source.KAWITZKY_2020)),
                showsResonance = true,
            ),
            Exercise(
                "phrases", "Words and phrases", "text.bubble",
                "Keep pitch and resonance together from single words to short phrases.",
                guided(
                    "Find your practice note with a short hum. Then say everyday words on it: \"hi\", \"bye\", \"yes\", \"no\", \"thanks\"." to 30,
                    "Same words, now spoken naturally, letting the pitch move a little. Keep the resonance you're working on." to 30,
                    "Two- and three-word phrases: \"good morning\", \"see you soon\", \"how are you?\"" to 40,
                    "Longer phrases you actually say, like your name, ordering a coffee, or answering the phone." to 40,
                ),
                setOf(4, 5),
                Basis(Basis.Strength.CLINICAL,
                    "Clinical guidance for trans and gender-diverse people builds from sounds to words to longer phrases while keeping the voice easy. Practising everyday words and real-life phrases is a recommended way to carry the voice into daily life.",
                    listOf(Source.DAVIES_2015)),
                showsResonance = true,
            ),
            Exercise(
                READING_ID, "Reading: Rainbow Passage", "book",
                "Read aloud and carry your practice into speech.",
                Kind.Reading(45.0),
                setOf(5, 6),
                Basis(Basis.Strength.CLINICAL,
                    "The Rainbow Passage is a standard text for voice assessment. Reading aloud is the step between phrases and free conversation in clinical guidance for trans and gender-diverse voice training.",
                    listOf(Source.FAIRBANKS_1960, Source.DAVIES_2015)),
            ),
            Exercise(
                INTONATION_ID, "Intonation", "point.topleft.down.to.point.bottomright.curvepath",
                "Explore how your pitch rises and falls across a sentence.",
                guided(
                    "Say \"Really\" three ways: as a question (rising), as a statement (falling), and surprised (up and down)." to 30,
                    "\"It's a lovely day.\" Say it flat, then let the pitch move more. Notice how each version feels." to 30,
                    "\"Are you coming tonight?\" Ask with a rise at the end, then say \"I'm coming tonight\" with a fall." to 30,
                    "Tell a short story about your day, using the amount of melody that feels like you." to 40,
                ),
                setOf(5),
                Basis(Basis.Strength.RESEARCH,
                    "Intonation affects how voices are perceived. In a study of cis and trans speakers, listeners heard speakers with more upward glides and a wider semitone range as female. Other studies found the opposite, so there's no single right amount. The app shows your range but doesn't set a target.",
                    listOf(Source.HANCOCK_2014, Source.LEUNG_2018)),
            ),
            Exercise(
                "weight", "Lighter ↔ heavier", "scalemass",
                "Contrast a lighter and a heavier voice quality on the same note.",
                guided(
                    "On a comfortable note, say \"hey\" with a light, gentle start, like calling softly across a room." to 20,
                    "Same note and volume, now a fuller, heavier \"hey\". Don't push or get louder." to 20,
                    "Go slowly from light to full and back on \"mmm-ah\". Notice what changes in your throat." to 30,
                    "Pick the weight you're aiming for and keep it on a few words: \"hello\", \"lovely\", \"any time\"." to 30,
                ),
                setOf(6),
                Basis(Basis.Strength.LIMITED,
                    "Vocal weight is mostly a voice-training idea. A computer-model study suggests that voice quality (spectral shape) affects gender perception alongside pitch, and a review found mixed results for breathiness. No study has tested a weight drill directly. Never press or squeeze to get a heavier sound.",
                    listOf(Source.ZHANG_2022, Source.LEUNG_2018, Source.DAVIES_2015)),
            ),
            Exercise(
                COOL_DOWN_ID, "Cool-down", "moon.stars",
                "Gentle downward hums and trills to finish.",
                guided(
                    "Hum gently on a low, comfortable note, sliding downward." to 30,
                    "Lip trills sliding from a middle note down to a low one." to 30,
                    "Yawn-sigh downward a few times." to 30,
                    "Say a few words in your relaxed, natural voice. Then rest and sip some water." to 20,
                ),
                everyStage,
                Basis(Basis.Strength.LIMITED,
                    "Singers report that cool-downs help, but objective measures haven't shown a clear effect yet. The hums and trills used here are the same semi-occluded exercises as the warm-up.",
                    listOf(Source.RAGAN_2016, Source.TITZE_2006)),
            ),
        )
    }
}

object RainbowPassage {
    const val TEXT = "When the sunlight strikes raindrops in the air, they act as a prism and form a rainbow. The rainbow is a division of white light into many beautiful colours. These take the shape of a long round arch, with its path high above, and its two ends apparently beyond the horizon. There is, according to legend, a boiling pot of gold at one end. People look, but no one ever finds it. When a man looks for something beyond his reach, his friends say he is looking for the pot of gold at the end of the rainbow."
    const val CITATION = "The Rainbow Passage (first paragraph), from Fairbanks, G. (1960), Voice and Articulation Drillbook, 2nd ed., Harper & Row."
}
