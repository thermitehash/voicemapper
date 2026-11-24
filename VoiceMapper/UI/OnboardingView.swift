import SwiftUI
import SwiftData

/// First-launch setup (PLAN §6): welcome, disclaimer, mic, goal, optional
/// baseline, headphones tip. Shown again after Delete all data.
struct OnboardingView: View {
    static let doneKey = "onboarding.done"

    @Environment(AudioEngine.self) private var audio
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize
    @AppStorage(Self.doneKey) private var done = false
    @Query private var baselines: [Baseline]
    @AppStorage(GoalRange.lowKey) private var goalLow = 0
    @AppStorage(GoalRange.highKey) private var goalHigh = 0
    @State private var step = Self.initialStep

    /// Debug builds accept `-debugOnboardingStep 3` to open on a step (for screenshots).
    private static var initialStep: Step {
        #if DEBUG
        return Step(rawValue: UserDefaults.standard.integer(forKey: "debugOnboardingStep")) ?? .welcome
        #else
        return .welcome
        #endif
    }
    @State private var showingBaseline = false

    enum Step: Int, CaseIterable {
        case welcome, disclaimer, microphone, goal, baseline, headphones
    }

    var body: some View {
        VStack(spacing: 0) {
            ProgressView(value: Double(step.rawValue + 1), total: Double(Step.allCases.count))
                .padding(.horizontal)
                .padding(.top, 8)
                .accessibilityLabel("Step \(step.rawValue + 1) of \(Step.allCases.count)")
            Group {
                switch step {
                case .welcome: welcome
                case .disclaimer: disclaimer
                case .microphone: microphone
                case .goal: goal
                case .baseline: baseline
                case .headphones: headphones
                }
            }
            .frame(maxHeight: .infinity)
            .transition(reduceMotion ? .opacity
                        : .asymmetric(insertion: .move(edge: .trailing), removal: .move(edge: .leading))
                            .combined(with: .opacity))
            .id(step)
            controls
        }
        .animation(.default, value: step)
        .sheet(isPresented: $showingBaseline) { BaselineView() }
    }

    // MARK: - Pages

    private var welcome: some View {
        page(symbol: "waveform", title: "Welcome to VoiceMapper") {
            Text("VoiceMapper helps you reach your own voice goal, safely.")
            Text("There's no \"correct\" voice here. You choose what you're aiming for, and the app shows your pitch and resonance so you can see where you are.")
            Text("Everything stays on this iPhone. Nothing is uploaded, and no audio is ever saved.")
        }
    }

    private var disclaimer: some View {
        page(symbol: "stethoscope", title: "Before you start") {
            Text(regional: "VoiceMapper is a practice tool, not medical advice, and not a substitute for a speech-language pathologist.")
            Text("Its strain alerts are estimates, not a diagnosis. Stop if anything hurts or feels scratchy, and see a professional if you have pain or hoarseness that lasts.")
            Text("Working with a voice therapist who specialises in gender-affirming voice care is the safest way to train. Settings has a link to help you find one.")
        }
    }

    private var microphone: some View {
        page(symbol: "mic", title: "Microphone") {
            Text("VoiceMapper listens to your voice to measure pitch and resonance. Audio is analysed as you speak and never stored.")
            switch audio.micPermission {
            case .granted:
                Label("Microphone access is on", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
            case .denied:
                Text("Microphone access is off. You can turn it on in iOS Settings > VoiceMapper.")
                    .foregroundStyle(.secondary)
            case .undetermined:
                Button("Allow microphone", systemImage: "mic") {
                    Task { await audio.requestMicAccess() }
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private var goal: some View {
        VStack(alignment: .leading, spacing: 0) {
            header(symbol: "scope", title: "Your goal")
                .padding(.horizontal)
            List { GoalPickerView() }
        }
    }

    private var baseline: some View {
        page(symbol: "record.circle", title: "Baseline (optional)") {
            Text("Read a short passage for 30 seconds in your natural voice. It's your reference point, not a target: strain alerts compare against it, and you'll see how you change over time.")
            if let b = baselines.first {
                Label("Saved: \(Note(frequency: b.medianHz).name) (\(Int(b.medianHz.rounded())) Hz)",
                      systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
            }
            Button(baselines.isEmpty ? "Record baseline" : "Record again", systemImage: "record.circle") {
                showingBaseline = true
            }
            .buttonStyle(.bordered)
            .disabled(audio.micPermission == .denied)
            Text("You can skip this and record it later in Settings.")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }

    private var headphones: some View {
        page(symbol: "headphones", title: "Hearing yourself") {
            Text(regional: "You can hear your own voice through headphones while you practise. Wired headphones work best: Bluetooth adds a delay of about 150–250 ms, which makes it harder to follow your voice.")
            Text(regional: "The mic picks up your voice fine without headphones. Practise somewhere quiet, at a comfortable volume, and keep sessions short.")
        }
    }

    // MARK: - Pieces

    /// Side by side normally; stacked full-width at accessibility text sizes,
    /// capped so the buttons leave room for the page text.
    private var controls: some View {
        let label = Text(regional: nextTitle).frame(maxWidth: typeSize.isAccessibilitySize ? .infinity : nil)
        // Goal and baseline are optional: the button stays tappable but is
        // grey until the step is done, which nudges without forcing.
        let next = Group {
            if nextIsMuted {
                Button { self.next() } label: { label }
                    .buttonStyle(.bordered)
                    .tint(.secondary)
                    .accessibilityHint(step == .goal ? "Optional. Picking a goal first is recommended."
                                                     : "Optional. You can record a baseline later in Settings.")
            } else {
                Button { self.next() } label: { label }
                    .buttonStyle(.borderedProminent)
            }
        }
        .controlSize(.large)
        return Group {
            if typeSize.isAccessibilitySize {
                VStack(spacing: 8) {
                    next
                    if step != .welcome { Button("Back") { move(-1) } }
                }
            } else {
                HStack {
                    if step != .welcome { Button("Back") { move(-1) } }
                    Spacer()
                    next
                }
            }
        }
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        .padding()
    }

    /// Optional step not done yet: goal not picked, or no baseline recorded.
    private var nextIsMuted: Bool {
        switch step {
        case .goal: GoalRange(low: goalLow, high: goalHigh) == nil
        case .baseline: baselines.isEmpty
        default: false
        }
    }

    private var nextTitle: String {
        switch step {
        case .welcome: "Get started"
        case .disclaimer: "I understand"
        case .microphone: audio.micPermission == .undetermined ? "Not now" : "Next"
        case .baseline: baselines.isEmpty ? "Skip" : "Next"
        case .headphones: "Start practising" // shown via Text(regional:)
        default: "Next"
        }
    }

    private func next() {
        if step == .headphones { done = true } else { move(1) }
    }

    private func move(_ delta: Int) {
        if let s = Step(rawValue: step.rawValue + delta) { step = s }
    }

    private func header(symbol: String, title: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 40))
                .foregroundStyle(.tint)
                .accessibilityHidden(true)
            Text(title)
                .font(.largeTitle.weight(.bold))
                .accessibilityAddTraits(.isHeader)
        }
        .padding(.top, 32)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func page<Content: View>(symbol: String, title: String,
                                     @ViewBuilder content: () -> Content) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header(symbol: symbol, title: title)
                content()
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
