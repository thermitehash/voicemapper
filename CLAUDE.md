# VoiceMapper — project guide for Claude

iOS voice-training app (SwiftUI, iOS 17+, Swift 6). Fully local: no network code, analytics, or third-party SDKs.
Guiding principle: help the user reach *their* voice goal safely — no "correct" voice, strain prevention built in.

Full approved plan: `docs/PLAN.md`. Approved screen wireframes: `docs/WIREFRAMES.md`. Progression system (S14): `docs/PROGRESSION.md`. DSP accuracy results: `docs/ACCURACY.md` (re-run with `Tools/DSPCheck/run.sh` after any DSP change).

## Working rules (Claude Pro budget, spread-out pace)
- One session = one row of the build table below. Stop at the session boundary.
- Read only the files the current session needs; don't re-scan the whole repo.
- At session start, run `git fetch && git branch -r` and check for an existing `claude/sN-*` branch for this session. Branches have been shipped to TestFlight before being merged, so the status table on `main` can be out of date.
- To screenshot the simulator, install from `TARGET_BUILD_DIR` (from `xcodebuild ... -showBuildSettings`), not from `find DerivedData`. There's more than one DerivedData folder, and the wrong one installs a stale build.
- Development happens on the user's Mac. Claude builds locally for the simulator and fixes errors before handing off:
  `xcodebuild -project VoiceMapper.xcodeproj -scheme VoiceMapper -destination 'platform=iOS Simulator,name=iPhone 18 Pro' build`
  It can also launch the app and screenshot it with `xcrun simctl`. Device tests (mic, headphones, Bluetooth, Face ID) still need the user's iPhone.
- The iPhone gets builds through TestFlight (internal testing), not by cable — the user doesn't want Developer Mode on. To ship a build: bump `CURRENT_PROJECT_VERSION`, then `xcodebuild ... -configuration Release -destination 'generic/platform=iOS' -archivePath <path> -allowProvisioningUpdates archive`, then `xcodebuild -exportArchive` with an ExportOptions.plist (`method` app-store-connect, `destination` upload, `teamID` R6PF4UF778, `manageAppVersionAndBuildNumber` false — otherwise Xcode silently bumps the build number on upload).
- Commit + push at the end of every session, then update the status column below.
- Use Sonnet for routine UI sessions; Opus for DSP sessions (S3, S5, S6, S10).

## Stack
SwiftUI · AVAudioEngine · Accelerate/vDSP · Swift Charts · SwiftData

## Xcode project notes
- Requires Xcode 16+. `VoiceMapper/` is a *synchronized folder*: any file added under it is compiled automatically. Do not edit `project.pbxproj` to add files.
- Info.plist is generated from build settings (`INFOPLIST_KEY_*`); microphone and Face ID usage strings are already set.
- Bundle ID `com.thrivestorm2.VoiceMapper`; signing is Automatic — the user picks their Team in Xcode.
- iPhone only, portrait only, iOS 17.0 minimum, Swift 6 language mode.

## Module layout
- `AudioEngine` — capture, hear-yourself output, route monitoring + Bluetooth banner
- `DSP/` — PitchDetector/PitchTracker (YIN), FormantAnalyzer/ResonanceTracker (LPC), VoiceQualityAnalyzer/VoiceQualityTracker (H1–H2, alpha ratio, CPP, HNR, jitter, shimmer, level), NoiseMeter
- `Analysis/` — NoteMapper, RangeTable, GoalRange, ResonanceGoal, GoalPreset, ResonanceScorer, VoicePerception, StrainGuard, HeatmapBinner
- `UI/` — LiveView, GridView, RangeTableView, HistoryView, HistoryLayers, SafetyViews, BaselineView, HearYourselfSheet, SettingsView (+ ResearchSourcesView), ExercisesView, ExerciseRunView, OnboardingView, GoalPickerView
- `App/` — VoiceMapperApp (AppRoot: onboarding vs tabs, lock overlay), RootTabView, AppLock (Face ID / passcode)
- `Store/` — SwiftData models (sessions, grid-cell counts, baseline, goals)

## Build sessions
| # | Session | Scope | Device test | Status |
|---|---|---|---|---|
| S1 | Setup | Xcode project, app structure, tab bar with empty screens | App opens, tabs switch | done |
| S2 | Audio | Engine, mic permission, hear-yourself toggle, volume cap, delay slider | Hear yourself in headphones | done |
| S3 | Bluetooth | Route monitoring, "wired recommended" banner, headphones-only lock | Banner appears with AirPods | done |
| S4 | Pitch | Pitch detection + note label | Correct note when humming | done |
| S5 | Range table | Colour-coded range table, current note highlighted | Highlight follows voice | done |
| S6 | Resonance | Formant analysis + resonance score | "ee" vs "oo" changes score | done |
| S7 | Grid | Grid view, gender zones, live dot + trail | Dot moves as expected | done |
| S8 | Storage | SwiftData sessions + baseline recording | Data survives restart | done |
| S9 | History | History overlay, heat map, Today/Week/Month/All | Overlay + heat map correct | done |
| S10 | Safety | Weight/health measures, strain alerts, daily cap | Alert fires in strain test | done |
| S11 | Exercises | Exercise list, warm-up, cool-down, target-hold drill | Exercises completable | done |
| S12 | Setup & settings | Onboarding, goal picker, settings, Face ID, delete all | Clean install end to end | done |
| S13 | Polish | Praat accuracy check, accessibility, colour-blind palette, performance | Overall testing | done |
| S14 | Progression | Stages new learner → fine tuner, Today's plan, stepped hold target, ExerciseResult (see `docs/PROGRESSION.md`) | Plan adapts over several days | todo |
| S15 | Verification | Automated accuracy tests: DSP pass/fail suite with tolerances, mapping-layer unit tests, real-voice reference set measured in Praat | Tests pass; real recordings within tolerance | todo |
| S16 | Buffer | Bug fixes | — | todo |

## Next session: S14 Progression (Opus, build 44)
S1–S13 are merged into `main`. TestFlight's latest build is 43 (S13).

**Goal:** the long arc from new learner to fine tuner. The full design is in `docs/PROGRESSION.md`; build what it describes:
- `ExerciseResult` SwiftData model (add to the `ModelContainer` and to `SessionRecorder.deleteAll()`); record one result when each exercise finishes (`ExerciseRunView.finish()`).
- Stages 1–6 with suggested (never forced) criteria computed from `PracticeSession`, `GridCellCount`, `ExerciseResult` and strain history, with minimum days per stage. Strain alerts hold the stage.
- Exercises tab: a path header (stages, current one, "ready to move on" checks in plain words) and a **Today's plan** card (warm-up → 2–3 stage exercises → 1 review → cool-down, sized to the daily cap), with the full library below.
- Stage 2 stepped hold target: start at the baseline and move 1 semitone towards the goal per passed step.
- `Exercise.stages`, plus a few new exercises for stages 4–6 (phrases, intonation, lighter ↔ heavier weight). Keep the instructions goal-neutral.
- Settings ▸ Goal shows the current stage and has "Choose stage". Stage keys are cleared by Delete all data (it already wipes the whole UserDefaults domain).

**First: evidence review of every exercise** (user request). Each exercise must rest on published voice research or recognised speech-language pathology practice, not internet folklore:
- Add a `basis` (citation + one line) to `Exercise` and show it in the run view ("Why this exercise").
- Starting points to check against:
  - Titze 2006 (semi-occluded vocal tract exercises: lip/tongue trills, straw, humming; JSLHR 49:448–459)
  - Stemple et al. 1994 (Vocal Function Exercises: sustained vowels, glides; J Voice)
  - Verdolini's resonant voice therapy
  - Davies, Papp & Antoni 2015 (gender-affirming voice and communication guidelines; Int J Transgenderism 16:117–159)
  - Hancock & Garabedian 2013 (transgender voice therapy outcomes)
- Read the sources for real; don't cite from memory without checking the claim.
- Flag anything without published support. "Big dog / small dog" in particular comes from community voice training: either reframe it as forward/back resonance work that Davies et al. 2015 describes, or label it as a community technique.
- Same standard for the new stage 4–6 exercises.
- Keep the exact wording safe: comfortable volume, stop if it hurts.
- The app can't claim to be "SLP-approved" unless an SLP actually reviews it. Suggest to the user that they ask one to look at the final list.

**Read first:** `docs/PROGRESSION.md`, `Analysis/Exercises.swift`, `UI/ExercisesView.swift`, `UI/ExerciseRunView.swift`, `Store/Models.swift`, `Store/SessionRecorder.swift`.

**Notes from S13:**
- Goal presets are listed alphabetically on purpose, so the order doesn't read as a ranking. Keep any new gender-related lists the same way.
- The colour-blind palette is `Palette.colourBlind` (cached static, synced in RootTabView).
- Performance profiling on device (Instruments) is still open; do it in S15 if anything feels slow.

**Debug helpers:** `-debugTab <live|range|exercises|history|settings>`, `-debugExercise <id>`, `-debugOnboardingStep <0-5>`, `-onboarding.done YES`, `-display.colourBlindPalette YES`.

**Queued for S15 Verification (user request):** tests that measure mapping accuracy, not just run the code.
- **Test target:** add an XCTest unit-test target (in Xcode: File ▸ New ▸ Target ▸ Unit Testing Bundle; the user may need to click through it), or failing that, give `Tools/DSPCheck` a `--test` mode that exits non-zero.
- **DSP tolerances,** turning the `docs/ACCURACY.md` tables into assertions:
  - F0 within 5 cents and no octave errors from 65 to 400 Hz across vowels;
  - F1–F3 within 10%;
  - ΔF within 3%;
  - resonance score within 5 points for front and open vowels;
  - no resonance reading above 350 Hz.
- **Mapping layer:**
  - `Note` Hz↔MIDI↔name round trips (A4 = 440, C4 = 261.63);
  - `PerceivedGender` zone boundaries (145 / 175 Hz);
  - `GoalPreset` ranges stay inside their zones;
  - `ResonanceScorer.score` endpoints;
  - `GridCellCount.bin` edges;
  - `HeatmapBinner` totals;
  - `GoalRange.closeness` and `direction`;
  - `StrainGuard` rules on scripted inputs;
  - `ExerciseLog` day rollover.
- **Real-voice reference set:**
  1. The user records about 10 short WAVs (sustained ee/ah/oo plus a sentence, low and high voice).
  2. They're measured in Praat (steps in `docs/ACCURACY.md`).
  3. The Praat values go in a fixture file.
  4. The tests assert the app is within tolerance.
- **Wrap-up:** record the results in `docs/ACCURACY.md`.

**Wrap-up:** build for the simulator, bump to build 44, archive and upload, open a PR from `claude/s14-progression`, merge it, then mark S14 done.
