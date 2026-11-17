# VoiceMapper — project guide for Claude

iOS voice-training app (SwiftUI, iOS 17+, Swift 6). Fully local: no network code, analytics, or third-party SDKs.
Guiding principle: help the user reach *their* voice goal safely — no "correct" voice, strain prevention built in.

Full approved plan: `docs/PLAN.md`. Approved screen wireframes: `docs/WIREFRAMES.md`. Progression system (S14): `docs/PROGRESSION.md`.

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
| S13 | Polish | Praat accuracy check, accessibility, colour-blind palette, performance | Overall testing | todo |
| S14 | Progression | Stages new learner → fine tuner, Today's plan, stepped hold target, ExerciseResult (see `docs/PROGRESSION.md`) | Plan adapts over several days | todo |
| S15 | Buffer | Bug fixes | — | todo |

## Next session: S13 Polish (Opus for the Praat check, build 43)
S1–S12 are merged into `main`. TestFlight's latest build is 42 (S12).

**Goal:** accuracy, accessibility, colour-blind palette, performance.
- **Praat accuracy check:** compare `PitchDetector` (median F0) and `FormantAnalyzer` (F1–F3) against Praat on a few recorded vowels/sentences (the user records on a Mac and runs Praat; Claude writes a small offline harness or unit test that feeds WAV files through the DSP). Note the differences in docs/ and tune only if clearly off.
- **Colour-blind palette:** Settings toggle (WIREFRAMES §8) that swaps `PerceivedGender.rgb` (range table, grid zones, heat map, goal dots) for a colour-blind-safe set (e.g. blue / grey-yellow / orange). Text labels (M/A/F) already exist.
- **Accessibility:** VoiceOver pass over every tab (labels, values, grouping), Dynamic Type at the largest sizes (onboarding, exercises, settings), Reduce Motion for the live dot/trail.
- **Performance:** Instruments Time Profiler on device while Live runs; keep the audio thread allocation-free; check SwiftUI redraw rate on the grid.

**Read first:** `Analysis/RangeTable.swift`, `Analysis/VoicePerception.swift`, `UI/GridView.swift`, `UI/SettingsView.swift`, the DSP files only for the Praat part.

**Debug helpers:** `-debugTab <live|range|exercises|history|settings>`, `-debugExercise <id>`, `-debugOnboardingStep <0-5>`; pass `-onboarding.done YES` to skip onboarding on a fresh simulator install.

**Wrap-up:** build for the simulator, bump to build 43, archive and upload, open a PR from `claude/s13-polish`, merge it, then mark S13 done.
