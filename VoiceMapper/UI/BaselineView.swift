import SwiftUI
import SwiftData

/// Records 30 seconds of the user's natural voice and stores the medians,
/// including the voice-quality reference StrainGuard compares against.
struct BaselineView: View {
    @Environment(AudioEngine.self) private var audio
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss

    private static let length = 30.0
    @State private var elapsed = 0.0
    @State private var isRecording = false
    @State private var hz: [Double] = []
    @State private var scores: [Double] = []
    @State private var qualities: [VoiceQuality] = []
    @State private var result: String?
    @State private var task: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("Read this passage aloud in your natural, comfortable voice. This is your reference point, not a target.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 6) {
                    Text(regional: RainbowPassage.text).font(.title3)
                    Text(RainbowPassage.citation)
                        .font(.caption).foregroundStyle(.secondary)
                }
                if isRecording {
                    ProgressView(value: elapsed, total: Self.length)
                        .accessibilityLabel("Recording")
                        .accessibilityValue("\(Int(Self.length - elapsed)) seconds left")
                    if audio.isNoisy { NoiseBanner() }
                    Text(audio.pitch.map { "\($0.note.name)" } ?? "–")
                        .font(.system(size: 56, weight: .semibold, design: .rounded))
                        .accessibilityLabel("Current note")
                        .accessibilityValue(audio.pitch?.note.name ?? "none")
                    Button("Cancel", role: .cancel) { cancel() }
                } else {
                    if let result { Text(result).font(.headline) }
                    Button(result == nil ? "Record" : "Record again", systemImage: "record.circle") { begin() }
                        .buttonStyle(.borderedProminent)
                        .disabled(audio.micPermission == .denied)
                    if audio.micPermission == .denied {
                        Text("Microphone access is off. Enable it in Settings > VoiceMapper.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Spacer()
            }
            .padding()
            .navigationTitle("Baseline")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Done") { cancel(); dismiss() } }
        }
        .onDisappear { cancel() }
    }

    private func begin() {
        result = nil; elapsed = 0; hz = []; scores = []; qualities = []
        task = Task {
            await audio.setListening(true)
            guard audio.isListening else { return }
            isRecording = true
            while elapsed < Self.length, !Task.isCancelled {
                try? await Task.sleep(for: .seconds(SessionRecorder.tick))
                elapsed += SessionRecorder.tick
                if let p = audio.pitch {
                    hz.append(p.frequency)
                    if let r = audio.resonance { scores.append(r.score) }
                    if let q = audio.quality, !audio.isNoisy { qualities.append(q) }
                }
            }
            guard !Task.isCancelled else { return }
            finish()
        }
    }

    private func finish() {
        isRecording = false
        stopListening()
        guard hz.count * 5 >= 50, let med = median(hz) else {  // need ≥ 10 s voiced
            result = "Not enough voice heard. Try again, a little closer to the mic."
            return
        }
        try? context.delete(model: Baseline.self)
        let baseline = Baseline(date: .now, medianHz: med, medianResonance: median(scores))
        baseline.quality = QualitySummary(qualities)
        context.insert(baseline)
        try? context.save()
        result = "Saved: median \(Note(frequency: med).name) (\(Int(med.rounded())) Hz)"
    }

    private func cancel() {
        task?.cancel(); task = nil
        if isRecording { isRecording = false; stopListening() }
    }

    private func stopListening() { Task { await audio.setListening(false) } }
}
