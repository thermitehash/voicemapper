import Accelerate

/// YIN pitch detector (de Cheveigné & Kawahara 2002). Works on one frame of
/// mono samples; see `frameLength` for how many it needs. Not thread-safe:
/// keep one instance per audio stream.
struct PitchDetector {
    struct Result: Sendable {
        let frequency: Double
        /// 0–1; how clean the periodicity is.
        let confidence: Double
    }

    static let minFrequency = 60.0
    static let maxFrequency = 1000.0
    /// Samples compared at each lag.
    static let windowLength = 1024
    /// A dip in the normalised difference below this is taken as the period.
    private static let dipThreshold: Float = 0.15
    /// Frames whose best dip is above this are treated as unvoiced.
    private static let voicedLimit: Float = 0.4
    /// A dip at half the chosen lag below this means the chosen one was a
    /// subharmonic (octave error).
    private static let octaveDipLimit: Float = 0.25
    /// Frames quieter than this (RMS, full scale = 1) are treated as silence.
    /// Harmonic guard: a multiple-lag dip wins if it is under this share of the
    /// chosen dip and lower by at least `harmonicGap`. The gap keeps jitter on
    /// already-clean dips from triggering it; the ratio keeps noise from it.
    private static let harmonicRatio: Float = 0.5
    private static let harmonicGap: Float = 0.07
    /// Second harmonic-guard rule for shallower false dips (≈0.03–0.07): a
    /// multiple-lag dip under this share of the chosen one also wins. A real
    /// period's multiples are about as deep as it is, never 10× deeper (S15).
    /// Only when the corrected pitch is at least `harmonicStrongMinHz`: below
    /// that, mild diplophonia (cycles alternating ~20% in amplitude) looks the
    /// same and would read an octave low in the core speaking range.
    private static let harmonicStrongRatio: Float = 0.1
    private static let harmonicFloor: Float = 0.03
    private static let harmonicStrongMinHz = 300.0
    private static let silenceRMS: Float = 0.003

    let sampleRate: Double
    private let tauMin: Int
    private let tauMax: Int
    private var diff: [Float]
    private var cmnd: [Float]
    private var cumulative: [Double]

    /// Samples `detect` needs: the window plus the longest lag (and one more
    /// for the dip search).
    var frameLength: Int { Self.windowLength + tauMax + 2 }

    init(sampleRate: Double) {
        self.sampleRate = sampleRate
        tauMin = max(2, Int(sampleRate / Self.maxFrequency))
        tauMax = Int(sampleRate / Self.minFrequency)
        diff = [Float](repeating: 0, count: tauMax + 2)
        cmnd = [Float](repeating: 0, count: tauMax + 2)
        cumulative = [Double](repeating: 0, count: Self.windowLength + tauMax + 3)
    }

    /// Returns nil for silence or unvoiced sound.
    mutating func detect(_ x: UnsafeBufferPointer<Float>) -> Result? {
        let window = Self.windowLength
        guard x.count >= frameLength, let base = x.baseAddress else { return nil }

        var meanSquare: Float = 0
        vDSP_measqv(base, 1, &meanSquare, vDSP_Length(window))
        guard meanSquare.squareRoot() >= Self.silenceRMS else { return nil }

        // Prefix sums of x², so the energy of any shifted window is O(1).
        cumulative[0] = 0
        for i in 0..<frameLength {
            cumulative[i + 1] = cumulative[i] + Double(x[i]) * Double(x[i])
        }
        let energy0 = Float(cumulative[window])

        // d(τ) = Σ (x[j] − x[j+τ])² = e(0) + e(τ) − 2·r(τ)
        for tau in 1...(tauMax + 1) {
            var r: Float = 0
            vDSP_dotpr(base, 1, base + tau, 1, &r, vDSP_Length(window))
            let shifted = Float(cumulative[tau + window] - cumulative[tau])
            diff[tau] = max(0, energy0 + shifted - 2 * r)
        }

        // Cumulative mean normalised difference.
        cmnd[0] = 1
        var running: Float = 0
        for tau in 1...(tauMax + 1) {
            running += diff[tau]
            cmnd[tau] = running > 0 ? diff[tau] * Float(tau) / running : 1
        }

        var best = -1
        var clear = true
        var tau = tauMin
        while tau <= tauMax {
            if cmnd[tau] < Self.dipThreshold {
                while tau + 1 <= tauMax, cmnd[tau + 1] < cmnd[tau] { tau += 1 }
                best = tau
                break
            }
            tau += 1
        }
        if best < 0 {
            // No clear dip: fall back to the deepest one, if voiced at all.
            best = (tauMin...tauMax).min { cmnd[$0] < cmnd[$1] } ?? tauMin
            guard cmnd[best] < Self.voicedLimit else { return nil }
            clear = false
        }

        // Harmonic guard: when a harmonic (2·F0, 4·F0…) sits on F1 it dominates
        // and a fractional-period dip can slip under the threshold (≈0.1–0.15)
        // while the true period's dip is far deeper (≈0.001). A truly periodic
        // voice has multiple-lag dips about as deep as the first, so the first
        // multiple that is much deeper is the real period (S13 check).
        if clear, cmnd[best] > Self.harmonicFloor {
            let first = best
            for k in 2...6 {
                let lag = first * k
                guard lag + 2 <= tauMax else { break }
                guard let alt = ((lag - 2)...(lag + 2)).min(by: { cmnd[$0] < cmnd[$1] }) else { continue }
                let gapRule = cmnd[alt] < cmnd[first] * Self.harmonicRatio
                    && cmnd[first] - cmnd[alt] > Self.harmonicGap
                let strongRule = cmnd[alt] < cmnd[first] * Self.harmonicStrongRatio
                    && sampleRate / Double(alt) >= Self.harmonicStrongMinHz
                if gapRule || strongRule {
                    best = alt
                    break
                }
            }
        }

        // Octave-down guard: on weak, breathy or creaky frames the deepest dip
        // is often the subharmonic. If half that lag is also a fair dip, the
        // true period is the shorter one. Only for frames without a clear dip:
        // the scan above already takes the shortest clear one, so applying it
        // there doubled the pitch whenever 2·F0 sat near F1 (S13 check).
        let half = best / 2
        if !clear, half >= tauMin {
            let lo = max(tauMin, half - 2), hi = min(tauMax, half + 2)
            if let alt = (lo...hi).min(by: { cmnd[$0] < cmnd[$1] }), cmnd[alt] < Self.octaveDipLimit {
                best = alt
            }
        }

        // Parabolic interpolation around the dip for sub-sample accuracy.
        var lag = Double(best)
        if best > tauMin, best < tauMax {
            let a = Double(cmnd[best - 1]), b = Double(cmnd[best]), c = Double(cmnd[best + 1])
            let denominator = a - 2 * b + c
            if denominator > 0 { lag += (a - c) / (2 * denominator) }
        }
        return Result(frequency: sampleRate / lag, confidence: Double(max(0, 1 - cmnd[best])))
    }
}
