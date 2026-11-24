# VoiceMapper — project guide for Claude

iOS voice-training app (SwiftUI, iOS 17+, Swift 6). Fully local: no network code, analytics, or third-party SDKs.
Guiding principle: help the user reach *their* voice goal safely — no "correct" voice, strain prevention built in.
Data rule (Nov 2026): measurement, corrections and localisation stay true to the data and neutral between goals. A correction may move readings only because the measurement was wrong, never because of who the user is. Anything directional (hold steps, hints, plan wording) points only towards the user's own goal. Note which group (trans fem, trans masc, nonbinary, cis) the evidence comes from; see `docs/LOCALIZATION.md` §3.
Priority (Nov 2026): trans women are the main audience. Testosterone does much of the work for many trans men, and trans women have the most research. So feminising accuracy, exercises and device testing come first. Masculine, androgynous and custom goals stay fully supported, and readings are never skewed towards any goal.

Full approved plan: `docs/PLAN.md`. Approved screen wireframes: `docs/WIREFRAMES.md`. Progression system (S14): `docs/PROGRESSION.md`. DSP accuracy results: `docs/ACCURACY.md`. Launch markets, regional English and accent effects (S17): `docs/LOCALIZATION.md`. After any DSP or mapping change, run `Tools/DSPCheck/run.sh --test` and `Tools/MappingTests/run.sh`; both must exit 0.

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

## Android port (`android/`)
Native Kotlin/Compose port; see `android/README.md`. **iOS stays the source of truth**: never change the iOS app to suit Android. `android/core` mirrors `DSP/` and `Analysis/` line for line; after any iOS change there, run `cd android && ./gradlew :core:test --rerun-tasks` (`--rerun-tasks` matters: Gradle doesn't track the Swift files ParityTest reads, so a cached run can hide a parity failure; DSP + mapping suites, plus `ParityTest`, which reads the Swift sources and fails on unported wording or constant changes). Build output goes to `*/build.nosync` because Documents is iCloud-synced (iCloud duplicated build files and broke R8). Branch: `claude/android-N`.

| # | Session | Scope | Status |
|---|---|---|---|
| A1 | Port | `core` (DSP + analysis + test suites + ParityTest), full app: five tabs, onboarding, baseline, strain alerts, daily cap, progression, History, app lock | done (emulator only) |
| A2 | Play + hear-yourself | Privacy policy (`PRIVACY.md`), Play setup notes and store assets (`android/PLAY_SETUP.md`, `android/dist/`), hear-yourself (low-latency monitor, delay, voice level, Bluetooth banner), version 0.2 | done (hear-yourself untested on a phone) |
| A2b | Regional English | Ported from iOS S17 in S19: `RegionalSpelling.kt`, `rs()` for display strings, therapist directory per region; MappingTest lints `app/.../ui` for British forms outside `rs()` | done |
| A2c | iOS parity (S17–S19) | Everything iOS gained through build 49: S18 rhotic gate and high-pitch LPC blend, S19 evidence wording, full Rainbow Passage (baseline scrolls), onboarding nudges, Range dark-mode contrast, TalkBack grid state and goal actions (iOS VoiceOver parity); fixed dark mode showing a white background behind onboarding; version 0.3 (`versionCode` 3), `dist/VoiceMapper-android-0.3.aab` | done (emulator only) |
| A3 | Device testing | First Play internal-testing release, fix what the device checklist in `android/README.md` turns up | next |

**Next Android session (A3):** the user is setting up a Google Play developer account (developer name **thermitehash**, personal, not a trader). Play Console needs device verification, so they ordered a Moto G Play (budget phone with a headphone jack). Once it's verified they upload `android/dist/VoiceMapper-android-0.3.aab` to internal testing and send testers the opt-in link (steps in `android/PLAY_SETUP.md`). Testers: Galaxy A52 / Android 14 and Galaxy S22 / Android 16. Start by asking for the Settings ▸ Microphone line from each phone, the hear-yourself "Device delay" figure, and results from the README device checklist. Every new upload needs a higher `versionCode` (now 3).

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
| S14 | Progression | Stages new learner → fine tuner, Today's plan, stepped hold target, ExerciseResult (see `docs/PROGRESSION.md`) | Plan adapts over several days | done |
| S15 | Verification | Automated accuracy tests: DSP pass/fail suite with tolerances, mapping-layer unit tests, real-voice reference set measured in Praat | Tests pass; real recordings within tolerance | done (real recordings pending) |
| S16 | Buffer | Bug fixes | — | in progress (build 46 uploaded for internal testers; more fixes from device feedback) |
| S17 | Localisation | Launch in US, Canada, UK, Australia, NZ (English only): regional spelling, professional names, therapist directories; accent analysis (`docs/LOCALIZATION.md`) | US/UK spelling and the right directory per region | done (build 47) |
| S18 | Accent calibration | Rhotic F3 gate; found and fixed higher voices reading up to 49 points too dark (pitch-blended LPC order); real-voice tests on Hillenbrand recordings; pitch-independence test; Android port. Accent offsets blocked on data (`docs/LOCALIZATION.md`, `docs/ACCURACY.md`) | Resonance reads alike across accents and pitches | done (build 48) |
| S19 | Evidence and Android spelling | Evidence notes name who each study covered (trans women for the resonance work); verified citations in `docs/LOCALIZATION.md`; "oo" and "ee" step wording; Android regional spelling, professional names and therapist directory (`core/analysis/RegionalSpelling.kt`, `rs()` in the app); full Rainbow Passage (346 words, scrolls on the baseline screen); onboarding goal and baseline buttons grey until done (still tappable); Range goal band and live-note highlight readable in dark mode; VoiceOver: grid reads position vs goal, swipe up/down sets the resonance goal (drag alternative), live readouts update while focused (Android TalkBack equivalent still to port) | None needed beyond the suites | done (build 49) |

## Launch markets (decided Nov 2026)
US, Canada, UK, Australia, New Zealand; English only. Copy is written in British spelling; anything with a regional word (see `Analysis/RegionalSpelling.swift`) is shown with `Text(regional:)`. `Tools/MappingTests` fails if a UI literal skips it. Build 47 (S17) carries this; Android isn't ported yet.

## Next session: S16 Buffer, part 2 (Sonnet; Opus if a DSP fix is needed; build 50)
S18 is merged and in build 48. It changes resonance readings for higher voices a lot (darker → accurate), so tell testers what to look for in the next build's "What to Test": resonance on "ah"/"eh" at higher pitch, and whether the dot stays put while you only change pitch. Real-voice checks: unpack the Hillenbrand recordings (see `docs/ACCURACY.md` S18) and run `DSPCHECK_H95=<folder> Tools/DSPCheck/run.sh --test`.
S1–S15 are merged into `main`. TestFlight's latest build is 49 (S19 on top of S18, S17 and S16 part 1, internal only; uploaded Nov 23 2026). External testers are still on build 45, pending Beta App Review.

**Start here:** ask the user for feedback from device testing of builds 45/46 and fix what they report. The checks to ask about are in the "What to Test" notes for 45: the plan changes over several days, the hold step moves after 2 passing days, high "ah" stays in the right octave, rough or creaky speech doesn't read an octave low, and "Why this exercise" is clear. If they've recorded the reference WAVs, run the real-voice comparison first.

**Done so far in S16 (build 46, internal testers only):**
- Exercises need real voiced time to count towards a stage. Before, guided steps could be tapped through silently and still counted as practice.
- The Intonation finish screen shows the semitone range, as its basis text promises.
- The stage 2 stepping note is hidden when no goal is set.
- History stage markers (dashed lines) on the pitch and resonance charts, read from `ExerciseResult.stage`. Not yet checked visually with real data.

**Open items:**
- **Real-voice reference set:** Praat is installed (`/Applications/Praat.app`), and the pipeline was tested end to end on text-to-speech clips. On a TTS "ee", F2 differed a lot (app 2841 vs Praat 2042 Hz). That's probably the TTS glide, but check F2 on the real "ee" recordings. If it fails, look at the formant numbering in `ResonanceScorer.assigned` and the Praat ceiling. The user records the WAVs listed in `Tools/DSPCheck/reference/README.md`, measures them with `measure.praat`, and runs `Tools/DSPCheck/run.sh --test`. Record the result in `docs/ACCURACY.md`. The WAVs are git-ignored; only `reference.json` is committed.
- **Device test of the S15 pitch fix:** check high "ah" sirens (370–480 Hz) and normal speech. Rough or creaky voice must not read an octave low.
- **SLP review** of `docs/EXERCISE_EVIDENCE.md`. Don't call the app "SLP-approved" until one has.
- **Before the larger external group:** use build 48 or later, not 45. Gate it on device testing of the progression for about a week, the real-voice comparison passing, and ideally the SLP review. The first external build needs Beta App Review (a beta description and contact email in App Store Connect).
- **Performance:** profile with Instruments on device if anything feels slow.
- **XCTest target:** optional, if the user wants tests in Xcode. The command-line suites already cover the scope.
- **Maybe later: version for non-iPhone friends.** Not scheduled; see the last section of `docs/PLAN.md` (preferred: web app, ~5–7 sessions). Revisit after S16.

**Notes:**
- The progression logic lives in `Analysis/Progression.swift`.
- New models: `ExerciseResult` and `StrainEvent`.
- Stage keys: `progression.stage`, `progression.stageStart`, `progression.holdStep`.
- Keep gender-related lists alphabetical.

**Debug helpers:** `-debugTab <live|range|exercises|history|settings>`, `-debugExercise <id>` (warmup, hums, slides, hold, forwardback, vowels, phrases, reading, intonation, weight, cooldown), `-debugOnboardingStep <0-5>`, `-onboarding.done YES`, `-display.colourBlindPalette YES`, `-progression.stage <1-6>`, `-debugRangeMidi <midi>` (highlights a Range row without singing), `-debugBaseline YES` (opens the baseline sheet from Settings), `-debugBaselineRecording YES` (its recording layout without a mic), `-goalLowMidi`/`-goalHighMidi <midi>`.

**Wrap-up:** build for the simulator, run both test suites, bump to build 50, archive and upload, open a PR from `claude/s16-buffer-2`, merge it, then mark S16 done.
