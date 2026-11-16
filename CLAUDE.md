# VoiceMapper — project guide for Claude

iOS voice-training app (SwiftUI, iOS 17+, Swift 6). Fully local: no network code, analytics, or third-party SDKs.
Guiding principle: help the user reach *their* voice goal safely — no "correct" voice, strain prevention built in.

Full approved plan: `docs/PLAN.md`. Approved screen wireframes: `docs/WIREFRAMES.md`.

## Working rules (Claude Pro budget, spread-out pace)
- One session = one row of the build table below. Stop at the session boundary.
- Read only the files the current session needs; don't re-scan the whole repo.
- At session start, run `git fetch && git branch -r` and check for an existing `claude/sN-*` branch for this session. Branches have been shipped to TestFlight before being merged, so the status table on `main` can be out of date.
- To screenshot the simulator, install from `TARGET_BUILD_DIR` (from `xcodebuild ... -showBuildSettings`), not from `find DerivedData`. There's more than one DerivedData folder, and the wrong one installs a stale build.
- Development happens on the user's Mac. Claude builds locally for the simulator and fixes errors before handing off:
  `xcodebuild -project VoiceMapper.xcodeproj -scheme VoiceMapper -destination 'platform=iOS Simulator,name=iPhone 18 Pro' build`
  It can also launch the app and screenshot it with `xcrun simctl`. Device tests (mic, headphones, Bluetooth, Face ID) still need the user's iPhone.
- The iPhone gets builds through TestFlight (internal testing), not by cable — the user doesn't want Developer Mode on. To ship a build: bump `CURRENT_PROJECT_VERSION`, then `xcodebuild ... -configuration Release -destination 'generic/platform=iOS' -archivePath <path> -allowProvisioningUpdates archive`, then `xcodebuild -exportArchive` with an ExportOptions.plist (`method` app-store-connect, `destination` upload, `teamID` R6PF4UF778).
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
- `Analysis/` — NoteMapper, RangeTable, GoalRange, ResonanceGoal, ResonanceScorer, VoicePerception, StrainGuard, HeatmapBinner
- `UI/` — LiveView, GridView, RangeTableView, HistoryView, HistoryLayers, SafetyViews, BaselineView, HearYourselfSheet, SettingsView, ExercisesView (placeholder); OnboardingView arrives in S12
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
| S11 | Exercises | Exercise list, warm-up, cool-down, target-hold drill | Exercises completable | todo |
| S12 | Setup & settings | Onboarding, goal picker, settings, Face ID, delete all | Clean install end to end | todo |
| S13 | Polish | Praat accuracy check, accessibility, colour-blind palette, performance | Overall testing | todo |
| S14 | Buffer | Bug fixes | — | todo |

## Next session: S11 Exercises (Sonnet, build 40)
S1–S10 are merged into `main` (S10 was PR #11, builds 34–39). TestFlight's latest build is 39.

**Goal:** replace the `ExercisesView` placeholder with the list in WIREFRAMES §5 and the drill in §6.
- **Header:** "Today: X / cap min" progress, from `strain.todaySeconds` and `StrainGuard.capKey`.
- **Exercises, in order:**
  1. Warm-up (required): gentle hums or lip trills and easy slides. It covers the warm-up prompts PLAN §5 assigns to the strain guard.
  2. Pitch slides
  3. Hold target pitch
  4. Resonance "big dog → small dog"
  5. Vowel ladder (ee/ah)
  6. Reading: Rainbow Passage (reuse the text and attribution in `BaselineView`)
  7. Cool-down
- **Target-hold drill (§6):** the target note comes from `GoalRange` (`@AppStorage` low/high), falling back to the baseline median. Show a hold progress bar (e.g. 6s/10s) that counts only time within ±1 semitone of the target, using `audio.pitch`.
- **Completion:** a simple per-day "done" tick for each exercise is enough. Store it in `@AppStorage` or a small SwiftData model, whichever is simpler.

**Plug into what already exists (don't re-implement):**
- **Capture:** `audio.setListening(true/false)`. `SessionRecorder` already logs sessions, and `StrainGuard.observe` already runs on every capture, so strain alerts and the daily cap work in exercises automatically.
- **Alerts:** `SafetyAlertOverlay` already sits over every tab (`RootTabView`). Respect `strain.restUntil`: while resting, show `RestBanner` and don't auto-start capture.
- **Live readings:** `audio.pitch`, `audio.resonance` (score 0–100) and `audio.quality`.

**Read first:** `UI/ExercisesView.swift`, `UI/LiveView.swift` (capture buttons and rest banner pattern), `UI/SafetyViews.swift`, `Analysis/GoalRange.swift`, `Analysis/StrainGuard.swift` (public API only).

**Wrap-up:** build for the simulator, bump to build 40, archive and upload to TestFlight, open a PR from `claude/s11-exercises`, merge it, then mark S11 done.

