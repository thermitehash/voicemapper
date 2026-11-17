import SwiftUI
import SwiftData

/// docs/WIREFRAMES.md §8. The colour-blind palette arrives in S13.
struct SettingsView: View {
    @Environment(AudioEngine.self) private var audio
    @Environment(SessionRecorder.self) private var recorder
    @Environment(AppLock.self) private var lock
    @Query private var baselines: [Baseline]
    @State private var showingBaseline = false
    @State private var confirmingDelete = false
    @AppStorage(StrainGuard.alertsKey) private var strainAlerts = true
    @AppStorage(StrainGuard.capKey) private var capMinutes = StrainGuard.defaultCapMinutes
    @AppStorage(AppLock.enabledKey) private var lockEnabled = false
    @AppStorage(GoalRange.lowKey) private var goalLow = 0
    @AppStorage(GoalRange.highKey) private var goalHigh = 0
    @AppStorage(ResonanceGoal.lowKey) private var resLow = 0.0
    @AppStorage(ResonanceGoal.highKey) private var resHigh = 0.0

    /// ASHA ProFind; opens in Safari. The app itself makes no network calls.
    private static let therapistURL = URL(string: "https://find.asha.org/pro/")!

    var body: some View {
        NavigationStack {
            List {
                Section("Voice") {
                    NavigationLink {
                        GoalSettingsView()
                    } label: {
                        LabeledContent("Goal", value: GoalPreset.label(
                            low: goalLow, high: goalHigh, resonanceLow: resLow, resonanceHigh: resHigh))
                    }
                    Button {
                        showingBaseline = true
                    } label: {
                        VStack(alignment: .leading) {
                            Text(baselines.isEmpty ? "Record baseline" : "Re-record baseline")
                            if let b = baselines.first {
                                Text("\(Note(frequency: b.medianHz).name), \(b.date.formatted(date: .abbreviated, time: .omitted))")
                                    .font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                Section {
                    Stepper("Daily practice cap: \(capMinutes) min", value: $capMinutes, in: 10...120, step: 5)
                    Toggle("Strain alerts", isOn: $strainAlerts)
                } header: {
                    Text("Safety")
                } footer: {
                    Text("Strain alerts compare clarity, steadiness, pitch and loudness against your baseline (or the first minute of a session) and suggest a rest. They're an estimate, not a diagnosis: if you have pain or lasting hoarseness, see a speech-language pathologist.")
                }
                Section {
                    Toggle("\(AppLock.biometryName) lock", isOn: lockBinding)
                        .disabled(!AppLock.isAvailable && !lockEnabled)
                    Button("Delete all data", role: .destructive) { confirmingDelete = true }
                } header: {
                    Text("Privacy")
                } footer: {
                    Text("VoiceMapper never saves audio. It keeps only summaries of your sessions (pitch, resonance and voice-quality medians), all on this iPhone. Nothing is uploaded.")
                }
                Section("About") {
                    NavigationLink("Research sources") { ResearchSourcesView() }
                    Link(destination: Self.therapistURL) {
                        Label("Find a voice therapist", systemImage: "arrow.up.right.square")
                    }
                    .accessibilityHint("Opens the ASHA ProFind directory in Safari")
                }
            }
            .navigationTitle("Settings")
            .sheet(isPresented: $showingBaseline) { BaselineView() }
            .confirmationDialog("Delete all data?", isPresented: $confirmingDelete, titleVisibility: .visible) {
                Button("Delete everything", role: .destructive) { Task { await deleteAll() } }
            } message: {
                Text("This removes every session, your baseline, your goal and all settings, then starts setup again. It can't be undone.")
            }
        }
    }

    /// Turning the lock on asks for Face ID first, so it can't lock you out.
    private var lockBinding: Binding<Bool> {
        Binding(get: { lockEnabled }, set: { on in
            if on {
                Task { if await lock.confirmEnable() { lockEnabled = true } }
            } else {
                lockEnabled = false
            }
        })
    }

    private func deleteAll() async {
        await audio.setHearYourself(false)
        await audio.setListening(false)
        recorder.deleteAll()
        audio.resetPreferences()
        if let domain = Bundle.main.bundleIdentifier {
            UserDefaults.standard.removePersistentDomain(forName: domain)
        }
        // Explicit, so @AppStorage observers flip back to onboarding at once.
        UserDefaults.standard.set(false, forKey: OnboardingView.doneKey)
        lock.reset()
    }
}

/// PLAN §5 sources, plus the methods the app's measurements are built on.
struct ResearchSourcesView: View {
    private struct Source: Identifiable {
        let citation: String
        let use: String
        var id: String { citation }
    }

    private static let sources: [Source] = [
        Source(citation: "Gelfer, M. P., & Mikos, V. A. (2005). The relative contributions of speaking fundamental frequency and formant frequencies to gender identification based on isolated vowels. Journal of Voice, 19(4), 544–554.",
               use: "Speaking-pitch zones in the range table and grid."),
        Source(citation: "Hillenbrand, J. M., & Clark, M. J. (2009). The role of f0 and formant frequencies in distinguishing the voices of men and women. Attention, Perception, & Psychophysics, 71(5), 1150–1166.",
               use: "How pitch and resonance together shape how a voice is read."),
        Source(citation: "Hillenbrand, J., Getty, L. A., Clark, M. J., & Wheeler, K. (1995). Acoustic characteristics of American English vowels. Journal of the Acoustical Society of America, 97(5), 3099–3111.",
               use: "Formant values behind the resonance score."),
        Source(citation: "Leung, Y., Oates, J., & Chan, S. P. (2018). Voice, articulation, and prosody contribute to listener perceptions of speaker gender: A systematic review and meta-analysis. Journal of Speech, Language, and Hearing Research, 61(2), 266–297.",
               use: "Why pitch is only part of it: resonance and intonation matter too."),
        Source(citation: "de Cheveigné, A., & Kawahara, H. (2002). YIN, a fundamental frequency estimator for speech and music. Journal of the Acoustical Society of America, 111(4), 1917–1930.",
               use: "Pitch detection."),
        Source(citation: "Fairbanks, G. (1960). Voice and Articulation Drillbook (2nd ed.). Harper & Row.",
               use: "The Rainbow Passage used for the baseline and reading exercise."),
    ]

    var body: some View {
        List {
            Section {
                ForEach(Self.sources) { s in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(s.citation).font(.subheadline)
                        Text(s.use).font(.caption).foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 2)
                }
            } footer: {
                Text("Norms vary by person, language and culture. The app shows typical ranges, not rules. Voice-health measures (HNR, jitter, shimmer, CPP) are compared only against your own baseline.")
            }
        }
        .navigationTitle("Research sources")
        .navigationBarTitleDisplayMode(.inline)
    }
}
