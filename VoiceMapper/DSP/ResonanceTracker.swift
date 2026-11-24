import Foundation

/// What the resonance tracker reports while the user holds a voiced sound.
struct ResonanceReading: Equatable, Sendable {
    /// 0 (darker) … 100 (brighter).
    let score: Double
    /// Smoothed formant spacing ΔF in Hz.
    let spacing: Double
    /// Latest formant frequencies (F1…F4) behind the reading.
    let formants: [Double]
}

/// Runs `FormantAnalyzer` on the live stream while the voice is pitched,
/// gates out unreliable frames and median-smooths the spacing. Called only from
/// the audio thread, so make a new one whenever the engine is rebuilt.
final class ResonanceTracker: @unchecked Sendable {
    /// Samples between analyses (~43 ms at 48 kHz).
    private static let hop = 2048
    /// ~0.65 s of accepted frames; the median throws out mis-tracked ones.
    private static let medianCount = 15
    /// Share of each new median taken by the displayed value (exponential
    /// follow), so the dot glides instead of jumping.
    private static let followRate = 0.2
    /// Accepted frames before the first reading is shown.
    private static let minimumFrames = 3
    /// Analyses in a row without an accepted frame before the reading clears
    /// (~0.35 s).
    private static let dropoutFrames = 8
    /// Above this F0 the harmonics are too sparse for LPC to find formants.
    /// The S13 check (Tools/DSPCheck) found most vowels collapse above ~350 Hz.
    private static let maxPitch = 350.0
    /// r-coloured vowels ("her", "car", "water") pull F3 far down, which reads
    /// 40-odd points darker, so rhotic accents (US, Canada, Scotland) would read
    /// darker in speech than non-rhotic ones (England, Australia, NZ) with the
    /// same vocal tract. A frame whose F3 is below this share of the speaker's
    /// running median F3 is skipped. In Hillenbrand et al.'s 1995 recordings, /ɝ/ F3 is
    /// ~0.66× the other vowels' and the lowest plain vowel (/u/) ~0.92×; at 0.8
    /// every /ɝ/ token and no plain-vowel token (0 of 1012) is caught.
    /// See docs/LOCALIZATION.md.
    private static let rhoticF3Ratio = 0.8
    /// Recent F3 values (~2.6 s of frames) behind the running median; r-coloured
    /// stretches are a minority of speech, so the median stays on plain vowels.
    private static let f3WindowCount = 60
    /// F3 values needed before the rhotic gate starts.
    private static let f3MinimumFrames = 10
    /// Between these pitches the standard and high-pitch LPC orders are
    /// blended linearly (see `FormantAnalyzer.Poles`), so the score doesn't
    /// jump ~5 points as a voice crosses ~160 Hz.
    private static let blendLow = 150.0
    private static let blendHigh = 175.0

    /// One analysed frame: formant spacing and the formants behind it.
    private typealias Frame = (spacing: Double, formants: [FormantAnalyzer.Formant])

    private var standard: FormantAnalyzer
    private var highPitch: FormantAnalyzer
    private let onReading: @Sendable (ResonanceReading?) -> Void
    private var samples: [Float] = []
    private var recentF3: [Double] = []
    private var recent: [Double] = []
    private var missedRun = 0
    private var hasReading = false
    private var smoothed: Double?

    init(sampleRate: Double, onReading: @escaping @Sendable (ResonanceReading?) -> Void) {
        standard = FormantAnalyzer(sampleRate: sampleRate)
        highPitch = FormantAnalyzer(sampleRate: sampleRate, poles: .highPitch)
        self.onReading = onReading
    }

    /// `pitch` is the current smoothed F0, nil when unvoiced.
    func process(_ incoming: UnsafeBufferPointer<Float>, pitch: Double?) {
        samples.append(contentsOf: incoming)
        let needed = max(standard.frameLength, Self.hop)
        while samples.count >= needed {
            var frame: Frame?
            if let pitch, pitch <= Self.maxPitch {
                frame = samples.withUnsafeBufferPointer { analyze($0, pitch: pitch) }
            }
            samples.removeFirst(Self.hop)
            update(with: frame)
        }
    }

    /// Spacing from the LPC order that suits this pitch, blended between the
    /// two orders from `blendLow` to `blendHigh`.
    private func analyze(_ x: UnsafeBufferPointer<Float>, pitch: Double) -> Frame? {
        let w = Self.highPitchShare(pitch)
        var low: Frame?, high: Frame?
        if w < 1, let f = standard.analyze(x), let s = ResonanceScorer.spacing(of: f) { low = (s, f) }
        if w > 0, let f = highPitch.analyze(x), let s = ResonanceScorer.spacing(of: f) { high = (s, f) }
        switch (low, high) {
        case let (l?, h?): return (l.spacing + w * (h.spacing - l.spacing), w < 0.5 ? l.formants : h.formants)
        case let (l?, nil): return l
        case let (nil, h?): return h
        default: return nil
        }
    }

    /// 0 → standard order only, 1 → high-pitch order only.
    static func highPitchShare(_ pitch: Double) -> Double {
        min(1, max(0, (pitch - blendLow) / (blendHigh - blendLow)))
    }

    private func update(with frame: Frame?) {
        guard let (spacing, formants) = frame else {
            missedRun += 1
            if missedRun >= Self.dropoutFrames {
                recent.removeAll()
                smoothed = nil
                if hasReading {
                    hasReading = false
                    onReading(nil)
                }
            }
            return
        }
        if isRhotic(formants) {
            // Not a miss: an "r" stretch shouldn't blank the reading.
            return
        }
        missedRun = 0
        recent.append(spacing)
        if recent.count > Self.medianCount { recent.removeFirst() }
        guard recent.count >= Self.minimumFrames else { return }
        let median = recent.sorted()[recent.count / 2]
        let value = smoothed.map { $0 + Self.followRate * (median - $0) } ?? median
        smoothed = value
        hasReading = true
        onReading(ResonanceReading(
            score: ResonanceScorer.score(spacing: value),
            spacing: value,
            formants: formants.prefix(4).map(\.frequency)
        ))
    }

    /// Records this frame's F3 and reports whether it is r-coloured: F3 well
    /// below the running median. Every frame feeds the median, including
    /// skipped ones, so a long sustained "rrr" eventually reads normally.
    private func isRhotic(_ formants: [FormantAnalyzer.Formant]) -> Bool {
        let assigned = ResonanceScorer.assigned(formants)
        guard assigned.count >= 3 else { return false }
        let f3 = assigned[2]
        let reference: Double? = recentF3.count >= Self.f3MinimumFrames
            ? recentF3.sorted()[recentF3.count / 2] : nil
        recentF3.append(f3)
        if recentF3.count > Self.f3WindowCount { recentF3.removeFirst() }
        guard let reference else { return false }
        return f3 < Self.rhoticF3Ratio * reference
    }
}
