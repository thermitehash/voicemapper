import SwiftUI
import SwiftData

@main
struct VoiceMapperApp: App {
    @State private var audio = AudioEngine()
    private let container: ModelContainer
    @State private var recorder: SessionRecorder
    @State private var strain: StrainGuard
    @State private var lock = AppLock()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let container = try! ModelContainer(for: PracticeSession.self, GridCellCount.self, Baseline.self,
                                               ExerciseResult.self, StrainEvent.self)
        let audio = AudioEngine()
        self.container = container
        _audio = State(initialValue: audio)
        let strain = StrainGuard()
        _strain = State(initialValue: strain)
        _recorder = State(initialValue: SessionRecorder(audio: audio, context: container.mainContext, strain: strain))
    }

    var body: some Scene {
        WindowGroup {
            AppRoot()
                .environment(audio)
                .environment(strain)
                .environment(recorder)
                .environment(lock)
                .task { recorder.run() }
        }
        .modelContainer(container)
        .onChange(of: scenePhase) { _, phase in
            if phase == .background {
                recorder.finish()
                lock.lockIfEnabled()
            }
        }
    }
}

/// Onboarding until it's finished, then the tabs; the Face ID lock covers both.
private struct AppRoot: View {
    @AppStorage(OnboardingView.doneKey) private var onboarded = false
    @Environment(AppLock.self) private var lock
    @Environment(AudioEngine.self) private var audio
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            if onboarded {
                RootTabView()
            } else {
                OnboardingView()
            }
            if lock.isLocked {
                LockScreen().transition(.opacity)
            }
        }
        .animation(.default, value: onboarded)
        .animation(.default, value: lock.isLocked)
        .onChange(of: lock.isLocked) { _, locked in
            if locked {
                Task {
                    await audio.setHearYourself(false)
                    await audio.setListening(false)
                }
            }
        }
        .task(id: scenePhase) {
            if scenePhase == .active { await lock.unlock() }
        }
    }
}
