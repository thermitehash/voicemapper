// DSP accuracy check (S13). Runs the app's own PitchDetector, FormantAnalyzer
// and ResonanceScorer on macOS, outside the app.
//
//   Tools/DSPCheck/run.sh                 synthetic check against known values
//   Tools/DSPCheck/run.sh voice.wav ...   per-file medians, to compare with Praat
//   Tools/DSPCheck/run.sh --test          pass/fail suite with tolerances (S15);
//                                         exits 1 on any failure
//
// Synthetic vowels use a Klatt-style source–filter model (Rosenberg glottal
// pulses with slight jitter and shimmer, a cascade of formant resonators, lip
// radiation), so the true F0 and formants are known exactly.

import Accelerate
import AVFoundation
import Foundation

let rate = 48_000.0

// MARK: - Synthesis

struct VowelSpec {
    let label: String
    let f0: Double
    let formants: [Double]   // F1–F5
    let bandwidths: [Double]
}

/// Approximate vowel averages for adult men and women (Hillenbrand et al. 1995);
/// F4/F5 are typical values. They're the ground truth for the synthesis, so
/// exact agreement with the paper doesn't matter.
let vowels: [VowelSpec] = {
    let bw = [80.0, 100, 150, 200, 250]
    return [
        VowelSpec(label: "men  /i/ heed ", f0: 138, formants: [342, 2322, 3000, 3657, 4500], bandwidths: bw),
        VowelSpec(label: "men  /æ/ had  ", f0: 127, formants: [588, 1952, 2601, 3500, 4500], bandwidths: bw),
        VowelSpec(label: "men  /ɑ/ hod  ", f0: 123, formants: [768, 1333, 2522, 3400, 4500], bandwidths: bw),
        VowelSpec(label: "men  /u/ who'd", f0: 143, formants: [378, 997, 2343, 3357, 4500], bandwidths: bw),
        VowelSpec(label: "women /i/ heed ", f0: 227, formants: [437, 2761, 3372, 4352, 5000], bandwidths: bw),
        VowelSpec(label: "women /æ/ had  ", f0: 220, formants: [669, 2349, 2972, 4200, 5000], bandwidths: bw),
        VowelSpec(label: "women /ɑ/ hod  ", f0: 212, formants: [936, 1551, 2815, 4000, 5000], bandwidths: bw),
        VowelSpec(label: "women /u/ who'd", f0: 235, formants: [459, 1105, 2735, 4115, 5000], bandwidths: bw),
    ]
}()

/// Deterministic noise so runs are repeatable.
struct LCG {
    var state: UInt64 = 0x9E3779B97F4A7C15
    mutating func next() -> Double {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return Double(state >> 11) / Double(1 << 53) * 2 - 1
    }
}

func synthesize(f0: Double, formants: [Double], bandwidths: [Double], seconds: Double = 1.0,
                jitter: Double = 0.005, shimmer: Double = 0.03, noise: Double = 0.002,
                alternate: Double = 0) -> [Float] {
    var rng = LCG()
    let count = Int(rate * seconds)
    var source = [Double](repeating: 0, count: count)
    // Rosenberg pulses: 40% opening, 16% closing, then closed.
    // Pulse start times are fractional so the mean period is exact.
    var start = 0.0, pulse = 0
    while Int(start) < count {
        let period = rate / f0 * (1 + jitter * rng.next())
        var amp = 1 + shimmer * rng.next()
        if alternate > 0, pulse % 2 == 1 { amp *= 1 - alternate }
        let open = 0.4 * period, close = 0.16 * period
        var i = Int(start.rounded(.up))
        while Double(i) < start + period, i < count {
            let p = Double(i) - start
            let v: Double
            if p < open { v = 0.5 * (1 - cos(.pi * p / open)) }
            else if p < open + close { v = cos(.pi / 2 * (p - open) / close) }
            else { v = 0 }
            source[i] = amp * v
            i += 1
        }
        start += period; pulse += 1
    }
    // Cascade of 2-pole resonators (Klatt 1980), unity gain at DC.
    var y = source
    for (f, b) in zip(formants, bandwidths) {
        let c = -exp(-2 * .pi * b / rate)
        let bb = 2 * exp(-.pi * b / rate) * cos(2 * .pi * f / rate)
        let a = 1 - bb - c
        var y1 = 0.0, y2 = 0.0
        for i in 0..<count {
            let out = a * y[i] + bb * y1 + c * y2
            y2 = y1; y1 = out; y[i] = out
        }
    }
    // Lip radiation: first difference. Then normalise and add a little noise.
    var prev = 0.0
    for i in 0..<count { let v = y[i]; y[i] = v - prev; prev = v }
    let peak = y.map(abs).max() ?? 1
    return y.map { Float(0.3 * $0 / peak + noise * rng.next()) }
}

// MARK: - Analysis

func median(_ v: [Double]) -> Double? {
    guard !v.isEmpty else { return nil }
    let s = v.sorted(); return s[s.count / 2]
}

func cents(_ measured: Double, _ truth: Double) -> Double { 1200 * log2(measured / truth) }

/// Median pitch over all frames, with the share of frames voiced and the share
/// more than 50 cents off (octave errors and the like).
func pitchStats(_ x: [Float], truth: Double?) -> (median: Double?, voiced: Double, gross: Double) {
    var detector = PitchDetector(sampleRate: rate)
    let hop = 512
    var values: [Double] = [], frames = 0, gross = 0
    var start = 0
    while start + detector.frameLength <= x.count {
        frames += 1
        if let r = x[start..<(start + detector.frameLength)].withUnsafeBufferPointer({ detector.detect($0) }) {
            values.append(r.frequency)
            if let truth, abs(cents(r.frequency, truth)) > 50 { gross += 1 }
        }
        start += hop
    }
    return (median(values), Double(values.count) / Double(max(frames, 1)), Double(gross) / Double(max(values.count, 1)))
}

/// Median F1–F4 and ΔF over frames, using the app's own formant numbering.
func formantStats(_ x: [Float]) -> (formants: [Double?], spacing: Double?, accepted: Double) {
    var analyzer = FormantAnalyzer(sampleRate: rate)
    let hop = 2048
    var perSlot: [[Double]] = [[], [], [], []], spacings: [Double] = [], frames = 0
    var start = 0
    while start + analyzer.frameLength <= x.count {
        frames += 1
        if let f = x[start..<(start + analyzer.frameLength)].withUnsafeBufferPointer({ analyzer.analyze($0) }),
           let s = ResonanceScorer.spacing(of: f) {
            spacings.append(s)
            let assigned = ResonanceScorer.assigned(f)
            for (i, v) in assigned.enumerated() where i < 4 { perSlot[i].append(v) }
        }
        start += hop
    }
    return (perSlot.map(median), median(spacings), Double(spacings.count) / Double(max(frames, 1)))
}

func trueSpacing(_ formants: [Double]) -> Double {
    var num = 0.0, den = 0.0
    for (i, f) in formants.prefix(4).enumerated() {
        let k = Double(2 * i + 1) / 2
        num += k * f; den += k * k
    }
    return num / den
}

func fmt(_ v: Double?, _ digits: Int = 0) -> String {
    guard let v else { return "–" }
    return String(format: "%.\(digits)f", v)
}

// MARK: - Runs

func syntheticCheck() {
    print("## Pitch: Rosenberg pulse train through a neutral vowel (F1–F5 500/1500/2500/3500/4500)")
    print("| true F0 (Hz) | median (Hz) | error (cents) | voiced | >50¢ off |")
    print("|---|---|---|---|---|")
    var worst = 0.0
    for f0 in [65.0, 80, 100, 120, 150, 180, 220, 260, 300, 350, 440, 520, 600] {
        let x = synthesize(f0: f0, formants: [500, 1500, 2500, 3500, 4500], bandwidths: [80, 100, 150, 200, 250])
        let s = pitchStats(x, truth: f0)
        let e = s.median.map { cents($0, f0) }
        worst = max(worst, abs(e ?? 1200))
        print("| \(fmt(f0)) | \(fmt(s.median, 1)) | \(fmt(e, 1)) | \(fmt(s.voiced * 100))% | \(fmt(s.gross * 100))% |")
    }
    print("\nWorst median pitch error: \(fmt(worst, 1)) cents\n")

    print("## Formants and resonance: synthetic vowels")
    print("| vowel | F0 | F1 true/meas | F2 true/meas | F3 true/meas | ΔF true/meas | score true/meas | frames used |")
    print("|---|---|---|---|---|---|---|---|")
    for v in vowels {
        let x = synthesize(f0: v.f0, formants: v.formants, bandwidths: v.bandwidths)
        let s = formantStats(x)
        let dfTrue = trueSpacing(v.formants)
        let pair = { (i: Int) in "\(fmt(v.formants[i]))/\(fmt(s.formants[i]))" }
        print("| \(v.label) | \(fmt(v.f0)) | \(pair(0)) | \(pair(1)) | \(pair(2)) | \(fmt(dfTrue))/\(fmt(s.spacing)) | \(fmt(ResonanceScorer.score(spacing: dfTrue)))/\(fmt(s.spacing.map(ResonanceScorer.score))) | \(fmt(s.accepted * 100))% |")
    }

    print("\n## Resonance at higher pitch: women /i/ formants, rising F0")
    print("| F0 | ΔF true/meas | score true/meas | frames used |")
    print("|---|---|---|---|")
    let iy = vowels[4]
    for f0 in [180.0, 220, 260, 300, 350, 400] {
        let x = synthesize(f0: f0, formants: iy.formants, bandwidths: iy.bandwidths)
        let s = formantStats(x)
        let dfTrue = trueSpacing(iy.formants)
        print("| \(fmt(f0)) | \(fmt(dfTrue))/\(fmt(s.spacing)) | \(fmt(ResonanceScorer.score(spacing: dfTrue)))/\(fmt(s.spacing.map(ResonanceScorer.score))) | \(fmt(s.accepted * 100))% |")
    }
}

/// Finer probes used while tuning (run with --probe).
func probe() {
    print("pitch sweep 90–600 Hz in 5 Hz steps (frames >50¢ off, shown when >10%):")
    let sets: [(String, [Double])] = [("neutral", [500, 1500, 2500, 3500, 4500])]
        + vowels[4...].map { ($0.label, $0.formants) }
    for (name, f) in sets {
        var bad: [String] = []
        for f0 in stride(from: 90.0, through: 600, by: 5) {
            let s = pitchStats(synthesize(f0: f0, formants: f, bandwidths: [80, 100, 150, 200, 250]), truth: f0)
            if s.gross > 0.1 { bad.append("\(Int(f0)):\(Int(s.gross * 100))%") }
        }
        print("  \(name): \(bad.isEmpty ? "no octave errors" : bad.joined(separator: " "))")
    }
    print("alternating pulses (diplophonia) and breathy noise, >50¢-off frames:")
    for f0 in [120.0, 200, 260] {
        var line = "  \(Int(f0)) Hz:"
        for alt in [0.1, 0.2, 0.4] {
            let s = pitchStats(synthesize(f0: f0, formants: [500, 1500, 2500, 3500, 4500],
                                          bandwidths: [80, 100, 150, 200, 250], alternate: alt), truth: f0)
            line += " alt \(Int(alt * 100))% → \(fmt(s.gross * 100))%"
        }
        for noise in [0.02, 0.05, 0.1] {
            let s = pitchStats(synthesize(f0: f0, formants: [500, 1500, 2500, 3500, 4500],
                                          bandwidths: [80, 100, 150, 200, 250], noise: noise), truth: f0)
            line += " · noise \(noise) → \(fmt(s.gross * 100))% (voiced \(fmt(s.voiced * 100))%)"
        }
        print(line)
    }
    print("resonance vs F0 (score true/meas):")
    for v in vowels {
        var line = "  \(v.label) true \(fmt(ResonanceScorer.score(spacing: trueSpacing(v.formants)))):"
        for f0 in [260.0, 280, 300, 310, 320, 330, 340, 350, 370, 400] {
            let s = formantStats(synthesize(f0: f0, formants: v.formants, bandwidths: v.bandwidths))
            line += " \(Int(f0))→\(fmt(s.spacing.map(ResonanceScorer.score)))"
        }
        print(line)
    }
}

func readWAV(_ path: String) -> [Float]? {
    guard let file = try? AVAudioFile(forReading: URL(fileURLWithPath: path)),
          let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: rate, channels: 1, interleaved: false),
          let converter = AVAudioConverter(from: file.processingFormat, to: format),
          let input = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length)),
          (try? file.read(into: input)) != nil else { return nil }
    let capacity = AVAudioFrameCount(Double(file.length) * rate / file.processingFormat.sampleRate) + 1024
    guard let output = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return nil }
    var fed = false
    var error: NSError?
    converter.convert(to: output, error: &error) { _, status in
        if fed { status.pointee = .endOfStream; return nil }
        fed = true; status.pointee = .haveData; return input
    }
    guard error == nil, let ch = output.floatChannelData else { return nil }
    return Array(UnsafeBufferPointer(start: ch[0], count: Int(output.frameLength)))
}

func fileCheck(_ paths: [String]) {
    print("| file | median F0 (Hz) | voiced | F1 | F2 | F3 | F4 | ΔF | score |")
    print("|---|---|---|---|---|---|---|---|---|")
    for path in paths {
        guard let x = readWAV(path) else { print("| \(path) | could not read |"); continue }
        let p = pitchStats(x, truth: nil)
        let f = formantStats(x)
        print("| \((path as NSString).lastPathComponent) | \(fmt(p.median, 1)) | \(fmt(p.voiced * 100))% | \(f.formants.map { fmt($0) }.joined(separator: " | ")) | \(fmt(f.spacing)) | \(fmt(f.spacing.map(ResonanceScorer.score))) |")
    }
}

// MARK: - Pass/fail suite (S15)

/// Tolerances from CLAUDE.md S15 / docs/ACCURACY.md.
enum Tolerance {
    static let pitchCents = 5.0
    /// A frame more than 50 cents off counts as gross (octave-type) error;
    /// more than this share of gross frames fails.
    static let grossShare = 0.05
    static let formantShare = 0.10
    static let spacingShare = 0.03
    static let scorePoints = 5.0
    /// Real recordings vs Praat: both are estimates on a wavering voice.
    static let realPitchCents = 20.0
}

var failures: [String] = []
var checks = 0

func check(_ ok: Bool, _ message: @autoclosure () -> String) {
    checks += 1
    if !ok { failures.append(message()) }
}

/// Back rounded vowels (/u/) lose F4 to LPC: ACCURACY.md "Known limits".
func isBackRounded(_ v: VowelSpec) -> Bool { v.label.contains("/u/") }

func runTests() {
    let bw = [80.0, 100, 150, 200, 250]
    let pitchSets: [(String, [Double])] = [("neutral", [500, 1500, 2500, 3500, 4500])]
        + vowels.map { ($0.label.trimmingCharacters(in: .whitespaces), $0.formants) }

    // F0 within 5 cents and no octave errors, 65–400 Hz, every vowel.
    print("pitch: 65–400 Hz in 5 Hz steps × \(pitchSets.count) vowels")
    for (name, formants) in pitchSets {
        for f0 in stride(from: 65.0, through: 400, by: 5) {
            let s = pitchStats(synthesize(f0: f0, formants: formants, bandwidths: bw, seconds: 0.5), truth: f0)
            guard let m = s.median else { check(false, "pitch \(name) \(Int(f0)) Hz: no reading"); continue }
            let e = cents(m, f0)
            check(abs(e) <= Tolerance.pitchCents, "pitch \(name) \(Int(f0)) Hz: median off by \(fmt(e, 1)) cents")
            check(s.gross <= Tolerance.grossShare,
                  "pitch \(name) \(Int(f0)) Hz: \(fmt(s.gross * 100))% of frames >50 cents off (octave errors)")
        }
    }

    // Mild diplophonia (≤20% alternating amplitude) must not read an octave
    // low: guards the harmonic-guard trade-off in PitchDetector (S15).
    print("pitch: mild diplophonia at speaking pitches")
    for f0 in [120.0, 160, 200, 260] {
        for alt in [0.1, 0.2] {
            let s = pitchStats(synthesize(f0: f0, formants: [500, 1500, 2500, 3500, 4500], bandwidths: bw,
                                          alternate: alt), truth: f0)
            check(s.gross <= 0.1, "diplophonia \(Int(alt * 100))% at \(Int(f0)) Hz: \(fmt(s.gross * 100))% of frames >50 cents off")
        }
    }

    // Formants, ΔF and score per vowel.
    print("formants: \(vowels.count) vowels")
    for v in vowels {
        let s = formantStats(synthesize(f0: v.f0, formants: v.formants, bandwidths: v.bandwidths))
        let name = v.label.trimmingCharacters(in: .whitespaces)
        for i in 0..<3 {
            guard let m = s.formants[i] else { check(false, "\(name): F\(i + 1) missing"); continue }
            let err = abs(m - v.formants[i]) / v.formants[i]
            check(err <= Tolerance.formantShare, "\(name): F\(i + 1) \(fmt(m)) vs \(fmt(v.formants[i])) (\(fmt(err * 100, 1))%)")
        }
        let dfTrue = trueSpacing(v.formants)
        if isBackRounded(v) {
            print("  \(name): ΔF and score skipped (known limit: weak F4 on back rounded vowels)")
            continue
        }
        guard let df = s.spacing else { check(false, "\(name): no ΔF"); continue }
        let dfErr = abs(df - dfTrue) / dfTrue
        check(dfErr <= Tolerance.spacingShare, "\(name): ΔF \(fmt(df)) vs \(fmt(dfTrue)) (\(fmt(dfErr * 100, 1))%)")
        let scoreErr = abs(ResonanceScorer.score(spacing: df) - ResonanceScorer.score(spacing: dfTrue))
        check(scoreErr <= Tolerance.scorePoints, "\(name): score off by \(fmt(scoreErr, 1)) points")
    }

    // No resonance reading above 350 Hz: run the app's ResonanceTracker.
    print("resonance gate: tracker above and below 350 Hz")
    for f0 in [300.0, 340, 360, 400, 450] {
        let x = synthesize(f0: f0, formants: vowels[4].formants, bandwidths: bw)
        final class Box: @unchecked Sendable { var readings = 0 }
        let box = Box()
        let tracker = ResonanceTracker(sampleRate: rate) { if $0 != nil { box.readings += 1 } }
        var i = 0
        while i + 512 <= x.count {
            x[i..<(i + 512)].withUnsafeBufferPointer { tracker.process($0, pitch: f0) }
            i += 512
        }
        if f0 > 350 {
            check(box.readings == 0, "resonance gate: \(box.readings) readings at \(Int(f0)) Hz (should be none above 350)")
        } else {
            check(box.readings > 0, "resonance gate: no readings at \(Int(f0)) Hz (should read below 350)")
        }
    }

    referenceTests()

    print("\n\(checks - failures.count) of \(checks) checks passed")
    for f in failures { print("FAIL \(f)") }
    exit(failures.isEmpty ? 0 : 1)
}

/// Real-voice reference set: `Tools/DSPCheck/reference/reference.json` lists
/// WAVs (in the same folder) with values measured in Praat. Missing files are
/// skipped, so the suite runs before the recordings exist.
func referenceTests() {
    struct Entry: Decodable {
        let file: String
        let f0: Double
        let f1: Double?
        let f2: Double?
        let f3: Double?
    }
    let dir = URL(fileURLWithPath: CommandLine.arguments[0]).deletingLastPathComponent()
    let folder = ProcessInfo.processInfo.environment["DSPCHECK_REFERENCE"].map { URL(fileURLWithPath: $0) }
        ?? dir.appendingPathComponent("reference")
    guard let data = try? Data(contentsOf: folder.appendingPathComponent("reference.json")),
          let entries = try? JSONDecoder().decode([Entry].self, from: data) else {
        print("reference set: no reference.json, skipped"); return
    }
    var ran = 0
    for e in entries {
        guard let x = readWAV(folder.appendingPathComponent(e.file).path) else { continue }
        ran += 1
        let p = pitchStats(x, truth: nil)
        if let m = p.median {
            check(abs(cents(m, e.f0)) <= Tolerance.realPitchCents,
                  "\(e.file): F0 \(fmt(m, 1)) vs Praat \(fmt(e.f0, 1)) (\(fmt(cents(m, e.f0), 1)) cents)")
        } else {
            check(false, "\(e.file): no pitch reading")
        }
        let f = formantStats(x)
        for (i, truth) in [e.f1, e.f2, e.f3].enumerated() {
            guard let truth else { continue }
            guard let m = f.formants[i] else { check(false, "\(e.file): F\(i + 1) missing"); continue }
            let err = abs(m - truth) / truth
            check(err <= Tolerance.formantShare,
                  "\(e.file): F\(i + 1) \(fmt(m)) vs Praat \(fmt(truth)) (\(fmt(err * 100, 1))%)")
        }
    }
    print("reference set: \(ran) of \(entries.count) recordings found")
}

let args = Array(CommandLine.arguments.dropFirst())
if args.isEmpty { syntheticCheck() }
else if args == ["--probe"] { probe() }
else if args == ["--test"] { runTests() }
else { fileCheck(args) }
