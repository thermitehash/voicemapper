import SwiftUI
import SwiftData

/// Runs one exercise: guided steps, the target-hold drill (WIREFRAMES §6) or
/// the reading. Time only counts while the mic is on, so a strain alert's
/// Rest (which stops capture) pauses the exercise.
struct ExerciseRunView: View {
    let exercise: Exercise

    @Environment(AudioEngine.self) private var audio
    @Environment(StrainGuard.self) private var strain
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @AppStorage(ExerciseLog.key) private var log = ""
    @AppStorage(GoalRange.lowKey) private var goalLow = 0
    @AppStorage(GoalRange.highKey) private var goalHigh = 0
    @AppStorage(Progression.stageKey) private var stage = 1
    @AppStorage(Progression.holdStepKey) private var holdStep = 0
    @Query private var baselines: [Baseline]

    @State private var startedCapture = false
    @State private var finished = false
    // Guided
    @State private var step = 0
    @State private var stepElapsed = 0.0
    // Hold
    @State private var targetMidi: Int?
    @State private var rep = 0
    @State private var held = 0.0
    @State private var offTarget = 0.0
    @State private var pauseLeft = 0.0
    // Reading
    @State private var readSeconds = 0.0
    // Result measures (S14)
    @State private var pitchSamples: [Double] = []
    @State private var resonanceSamples: [Double] = []
    @State private var strainAlerts = 0
    @State private var nextStepMessage: String?
    @State private var tooLittleVoice = false
    @State private var semitoneRange: Double?

    private static let tick = 0.1
    /// Off-target time (breath, wobble) before a hold restarts.
    private static let holdGrace = 0.6
    /// Rest between hold reps.
    private static let repPause = 5.0
    /// ±semitones counted as on target.
    private static let holdTolerance = 1.0

    private var done: Set<String> { ExerciseLog.done(in: log) }
    private var running: Bool { audio.isListening && !finished }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                banners
                if finished {
                    finishedView
                } else {
                    readout
                    content
                    BasisView(basis: exercise.basis)
                }
            }
            .padding()
            .frame(maxWidth: .infinity)
        }
        .safeAreaInset(edge: .bottom) {
            if !finished { controls.padding().background(.bar) }
        }
        .navigationTitle(exercise.title)
        .navigationBarTitleDisplayMode(.inline)
        .animation(.default, value: finished)
        .animation(.default, value: strain.restUntil)
        .task {
            if targetMidi == nil { targetMidi = defaultTarget }
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Self.tick))
                if running { advance(Self.tick) }
            }
        }
        .onChange(of: strain.alert) { old, new in
            if running, old == nil, case .strain = new { strainAlerts += 1 }
        }
        .onDisappear {
            if startedCapture { Task { await audio.setListening(false) } }
        }
    }

    // MARK: - Pieces

    @ViewBuilder private var banners: some View {
        if let until = strain.restUntil {
            RestBanner(until: until) { strain.endRest() }
        }
        if audio.isNoisy && running {
            NoiseBanner()
        }
        if !finished, !exercise.isWarmUp, !exercise.isCoolDown, !done.contains(Exercise.warmUpID) {
            Label("You haven't warmed up today. A few minutes of warm-up first protects your voice.",
                  systemImage: "sun.horizon")
                .font(.subheadline)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.yellow.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
        }
    }

    /// Current note, and resonance for the resonance drills.
    private var readout: some View {
        VStack(spacing: 6) {
            Text(audio.pitch?.note.name ?? "–")
                .font(.system(size: 56, weight: .semibold, design: .rounded))
                .foregroundStyle(audio.pitch == nil ? .tertiary : .primary)
                .contentTransition(.numericText())
            Text(audio.pitch.map { "\(Int($0.frequency.rounded())) Hz" }
                 ?? (audio.isListening ? "Listening…" : "Tap Start when ready"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
            if exercise.showsResonance {
                ResonanceMeter(score: audio.resonance?.score)
                    .padding(.top, 8)
            }
        }
        .monospacedDigit()
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var content: some View {
        switch exercise.kind {
        case .guided(let steps): guided(steps)
        case .hold(let seconds, let reps): hold(seconds: seconds, reps: reps)
        case .reading(let seconds): reading(seconds: seconds)
        }
    }

    private func guided(_ steps: [Exercise.Step]) -> some View {
        let current = steps[min(step, steps.count - 1)]
        return VStack(alignment: .leading, spacing: 12) {
            Text("Step \(min(step, steps.count - 1) + 1) of \(steps.count)")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text(regional: current.text)
                .font(.title3)
                .fixedSize(horizontal: false, vertical: true)
            ProgressView(value: min(stepElapsed / current.seconds, 1))
            HStack {
                Text("\(Int(max(0, current.seconds - stepElapsed).rounded(.up))) s left")
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
                Spacer()
                Button(step + 1 < steps.count ? "Next step" : "Finish") { nextStep(steps) }
                    .buttonStyle(.bordered)
            }
            .font(.subheadline)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func hold(seconds: Double, reps: Int) -> some View {
        let target = targetMidi ?? defaultTarget
        let note = Note(midi: target)
        return VStack(spacing: 14) {
            Text("Hold target: \(note.name) (\(Int(note.frequency.rounded())) Hz)")
                .font(.title3.weight(.semibold))
            Stepper("Target note", value: Binding(get: { target }, set: { targetMidi = $0; held = 0 }),
                    in: 36...84)
                .labelsHidden()
                .accessibilityLabel("Target note")
                .accessibilityValue(note.name)
            if pauseLeft > 0 {
                Text("Relax and breathe… \(Int(pauseLeft.rounded(.up))) s")
                    .foregroundStyle(.secondary)
            } else {
                Text(holdHint(target: target))
                    .foregroundStyle(holdHintColor(target: target))
            }
            ProgressView(value: min(held / seconds, 1))
            Text("\(Int(held)) s / \(Int(seconds)) s · hold \(min(rep + 1, reps)) of \(reps)")
                .font(.subheadline)
                .monospacedDigit()
                .foregroundStyle(.secondary)
            if isSteppedHold, GoalRange(low: goalLow, high: goalHigh) != nil {
                Text("Stage 2 steps: this target moves 1 semitone towards your goal after you pass it on \(Progression.passesPerStep) separate days.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            Text("Counts while you stay within a semitone. Comfortable volume. If it feels tight, pick an easier target.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    private func reading(seconds: Double) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(regional: "Read aloud slowly, using the voice you're practising. Stop and breathe whenever you need to.")
                .foregroundStyle(.secondary)
            Text(regional: RainbowPassage.text).font(.title3)
            Text(RainbowPassage.citation).font(.caption).foregroundStyle(.secondary)
            ProgressView(value: min(readSeconds / seconds, 1))
            Text("\(Int(readSeconds)) s of \(Int(seconds)) s voiced")
                .font(.subheadline)
                .monospacedDigit()
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var controls: some View {
        Button {
            Task { await toggleCapture() }
        } label: {
            Label(audio.isListening ? "Pause" : (strain.restUntil != nil ? "Start anyway" : "Start"),
                  systemImage: audio.isListening ? "pause.fill" : "mic")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(audio.micPermission == .denied)
    }

    private var finishedView: some View {
        VStack(spacing: 16) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(.green)
            Text("Done").font(.title2.weight(.semibold))
            if let nextStepMessage {
                Text(nextStepMessage).multilineTextAlignment(.center)
            }
            if exercise.id == Exercise.intonationID, let semitoneRange, !tooLittleVoice {
                Text("Your pitch moved across about \(Int(semitoneRange.rounded())) semitones. There's no right amount: use the melody that feels like you.")
                    .multilineTextAlignment(.center)
            }
            if tooLittleVoice {
                Text(regional: "The mic heard very little voice, so this won't count towards your stage. Next time, tap Start and practise aloud.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
            } else if strainAlerts > 0 {
                Text("There was a strain alert during this exercise, so it won't count towards moving on. Rest your voice before trying again.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
            }
            if exercise.isCoolDown {
                Text("Nice work today. Rest your voice and drink some water.")
                    .multilineTextAlignment(.center)
            } else if !done.contains(Exercise.coolDownID) {
                Text(regional: "When you've finished practising for today, end with the cool-down.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
            }
            Button("Back to exercises") { dismiss() }
                .buttonStyle(.borderedProminent)
        }
        .padding(.top, 40)
    }

    // MARK: - Logic

    /// Voiced seconds an exercise needs to count towards the stage: a quarter
    /// of a guided exercise's length (speech is ~50% voiced, with pauses),
    /// capped at 20 s. Guided steps can be tapped through with the mic off.
    private var requiredVoice: Double {
        switch exercise.kind {
        case .guided(let steps): min(20, 0.25 * steps.reduce(0) { $0 + $1.seconds })
        case .hold, .reading: 0  // can't finish without voice anyway
        }
    }

    private var isSteppedHold: Bool {
        if case .hold = exercise.kind { return Progression.stage(stored: stage) == .pitch }
        return false
    }

    /// Stage 2: the current hold step. Otherwise the middle of the goal range,
    /// else the baseline median, else A3.
    private var defaultTarget: Int {
        if isSteppedHold {
            return Progression.currentHoldStep(stored: holdStep, goal: GoalRange(low: goalLow, high: goalHigh),
                                               baselineHz: baselines.first?.medianHz)
        }
        if let goal = GoalRange(low: goalLow, high: goalHigh) {
            return (goal.low + goal.high) / 2
        }
        if let b = baselines.first { return Note(frequency: b.medianHz).midi }
        return 57
    }

    private func toggleCapture() async {
        if audio.isListening {
            await audio.setListening(false)
        } else {
            strain.endRest()
            await audio.setListening(true)
            if audio.isListening { startedCapture = true }
        }
    }

    private func advance(_ dt: Double) {
        if let pitch = audio.pitch {
            pitchSamples.append(pitch.frequency)
            if let r = audio.resonance { resonanceSamples.append(r.score) }
        }
        switch exercise.kind {
        case .guided(let steps):
            stepElapsed += dt
            if stepElapsed >= steps[min(step, steps.count - 1)].seconds { nextStep(steps) }
        case .hold(let seconds, let reps):
            advanceHold(dt, seconds: seconds, reps: reps)
        case .reading(let seconds):
            if audio.pitch != nil { readSeconds += dt }
            if readSeconds >= seconds { finish() }
        }
    }

    private func nextStep(_ steps: [Exercise.Step]) {
        stepElapsed = 0
        if step + 1 < steps.count { step += 1 } else { finish() }
    }

    private func advanceHold(_ dt: Double, seconds: Double, reps: Int) {
        if pauseLeft > 0 {
            pauseLeft = max(0, pauseLeft - dt)
            return
        }
        if let distance = semitonesFromTarget, abs(distance) <= Self.holdTolerance {
            held += dt
            offTarget = 0
        } else {
            offTarget += dt
            if offTarget > Self.holdGrace { held = 0 }
        }
        guard held >= seconds else { return }
        rep += 1
        held = 0
        offTarget = 0
        if rep >= reps { finish() } else { pauseLeft = Self.repPause }
    }

    private var semitonesFromTarget: Double? {
        guard let pitch = audio.pitch, let target = targetMidi else { return nil }
        return Note.midiValue(frequency: pitch.frequency) - Double(target)
    }

    private func holdHint(target: Int) -> String {
        guard let d = semitonesFromTarget else { return audio.isListening ? "Sing or hum the target note" : " " }
        if abs(d) <= Self.holdTolerance { return "On target, hold it" }
        return d < 0 ? "A little higher" : "A little lower"
    }

    private func holdHintColor(target: Int) -> Color {
        guard let d = semitonesFromTarget else { return .secondary }
        return abs(d) <= Self.holdTolerance ? .green : .orange
    }

    private func finish() {
        finished = true
        log = ExerciseLog.marking(exercise.id, in: log)
        record()
        if startedCapture {
            startedCapture = false
            Task { await audio.setListening(false) }
        }
    }
    /// Saves an `ExerciseResult` and, for a passed stage 2 hold, moves the step on.
    private func record() {
        var isHold = false
        if case .hold = exercise.kind { isHold = true }
        let voiced = Double(pitchSamples.count) * Self.tick
        let result = ExerciseResult(
            date: .now, exerciseID: exercise.id, stage: stage,
            passed: strainAlerts == 0 && voiced >= requiredVoice,
            voicedSeconds: voiced, strainAlerts: strainAlerts)
        tooLittleVoice = voiced < requiredVoice
        result.targetMidi = isHold ? targetMidi : nil
        result.medianHz = median(pitchSamples)
        if let lo = percentile(pitchSamples, 0.1), let hi = percentile(pitchSamples, 0.9) {
            result.semitoneRange = Note.midiValue(frequency: hi) - Note.midiValue(frequency: lo)
        }
        result.medianResonance = median(resonanceSamples)
        semitoneRange = result.semitoneRange
        context.insert(result)
        try? context.save()

        guard isSteppedHold, result.passed else { return }
        let results = (try? context.fetch(FetchDescriptor<ExerciseResult>(
            predicate: #Predicate { $0.exerciseID == "hold" }))) ?? []
        let startStamp = UserDefaults.standard.double(forKey: Progression.stageStartKey)
        let input = Progression.Input(
            stage: .pitch, stageStart: startStamp > 0 ? Date(timeIntervalSince1970: startStamp) : nil,
            sessions: [], results: results, strainDates: [], baselineHz: baselines.first?.medianHz,
            goal: GoalRange(low: goalLow, high: goalHigh), resonanceGoal: nil,
            holdStep: holdStep, capMinutes: strain.capMinutes)
        let current = Progression.currentHoldStep(stored: holdStep, goal: input.goal, baselineHz: input.baselineHz)
        let next = Progression.nextHoldStep(input)
        holdStep = next
        nextStepMessage = next != current ? "Step passed. Your next hold target is \(Note(midi: next).name)." : nil
    }
}

/// "Why this exercise": the evidence behind it (S14 evidence review).
private struct BasisView: View {
    let basis: Exercise.Basis

    var body: some View {
        DisclosureGroup {
            VStack(alignment: .leading, spacing: 8) {
                Text(regional: basis.summary)
                    .font(.subheadline)
                ForEach(basis.citations, id: \.self) { citation in
                    Text(citation)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Text("Keep to a comfortable volume, and stop if anything hurts or feels scratchy.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 6)
        } label: {
            HStack {
                Text("Why this exercise")
                Spacer()
                Text(regional: basis.strength.label)
                    .font(.caption)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(strengthColor.opacity(0.15), in: Capsule())
            }
        }
        .padding(12)
        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 12))
    }

    private var strengthColor: Color {
        switch basis.strength {
        case .research: .green
        case .clinical: .blue
        case .limited: .orange
        }
    }
}

/// Darker ↔ brighter resonance score bar for the resonance drills.
private struct ResonanceMeter: View {
    let score: Double?

    var body: some View {
        VStack(spacing: 4) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(LinearGradient(colors: [.blue.opacity(0.5), .purple.opacity(0.5), .pink.opacity(0.5)],
                                             startPoint: .leading, endPoint: .trailing))
                        .frame(height: 8)
                    if let score {
                        Circle()
                            .fill(.white)
                            .stroke(.primary, lineWidth: 2)
                            .frame(width: 18, height: 18)
                            .offset(x: (geo.size.width - 18) * score / 100)
                            .animation(.easeOut(duration: 0.2), value: score)
                    }
                }
                .frame(maxHeight: .infinity)
            }
            .frame(height: 18)
            HStack {
                Text("Darker")
                Spacer()
                Text(score.map { "Resonance \(Int($0.rounded()))" } ?? "Resonance –").monospacedDigit()
                Spacer()
                Text("Brighter")
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Resonance")
        .accessibilityValue(score.map { "\(Int($0.rounded())) of 100, darker to brighter" } ?? "No reading")
        .accessibilityAddTraits(.updatesFrequently)
    }
}
