# VoiceMapper for Android

A native Kotlin/Compose port of the iOS app. **The iPhone app is the source of truth.** This version follows it, and is never ahead of it. Same principle: help the user reach *their* voice goal safely. Fully local: no network permission, analytics or third-party SDKs.

## Layout
- `core/`: a pure Kotlin (JVM) port of iOS `DSP/` and `Analysis/`, mirrored line for line. It has no Android dependencies, so the accuracy suites run on the Mac.
- `app/`: the Android app (Jetpack Compose, Material 3). It has the same five tabs as iOS: Live, Range, Exercises, History and Settings, plus onboarding, baseline, strain alerts and the app lock.

## Keeping in step with iOS
`./gradlew :core:test` runs three suites:

| Suite | What it checks | Result |
|---|---|---|
| `DspCheckTest` | Port of `Tools/DSPCheck/run.sh --test`, same tolerances, same synthetic vowels (bit-identical noise). Also runs the real-voice reference set when the WAVs exist | 1273 / 1273 (same as iOS) |
| `MappingTest` | Port of `Tools/MappingTests`: notes, zones, presets, goals, bins, heat map, strain guard, progression, exercise library | 1525 / 1525 |
| `ParityTest` | Reads the **Swift sources** and fails if exercise wording or any tuned DSP/analysis constant has changed on iOS without being ported | 199 / 199 |

After any iOS change to `DSP/`, `Analysis/` or exercise wording, run `./gradlew :core:test`. If `ParityTest` fails, port the change, then update the expected text in the test.

## Build
Requirements (already set up on the dev Mac):
- JDK 17 in `~/Library/Java/JavaVirtualMachines`
- Android SDK at `/usr/local/share/android-commandlinetools`
- `local.properties` containing `sdk.dir=…` (git-ignored)

```
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :core:test              # accuracy + parity suites
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # signed release APK → app/build.nosync/outputs/apk/release/
```

**Build folders end in `.nosync`** (`app/build.nosync`, `core/build.nosync`). Documents is synced by iCloud Drive, which copies files in busy build folders ("Foo 2.class") and breaks the build; iCloud skips `.nosync` names. If a build fails with duplicate classes or a missing class, delete those two folders and build again.

**Toolchain:** AGP 9.5, Gradle 9.9, Kotlin 2.4. Compile SDK 37.1, target SDK 36, minimum SDK 26 (Android 8).

**Release signing** uses `~/.android/voicemapper-release.jks`, with its passwords in `android/keystore.properties`. Both are git-ignored. **Back them up.** Without them, an update can't install over the old version, and testers would have to uninstall and lose their data.

**Debug launch options** (debug builds only, for emulator screenshots):
```
adb shell am start -n com.thrivestorm2.voicemapper/.MainActivity \
  --es debugTab history --ez onboardingDone true --ez debugSeed true
```
`debugTab`: live | range | exercises | history | settings. `debugSeed` fills two weeks of made-up sessions. `debugExercise <id>` opens an exercise. `debugGoal 52,59` sets the pitch goal (MIDI low,high). `debugReading true` shows a sample reading on Live and Range, for store screenshots.

## Installing on a tester's phone (sideloading)
No Play Store account is needed for a couple of testers.
1. Send them `app-release.apk`, by email, Google Drive, or whatever they use.
2. On the phone, open the file. Android asks to allow installs from that app (Files, Gmail, Chrome…): tap **Settings ▸ Allow from this source**, go back, then **Install**.
3. Samsung phones may show "Auto Blocker" (One UI 6+). Turn it off for the install under **Settings ▸ Security and privacy ▸ Auto Blocker**, then turn it back on.
4. Google Play Protect may warn about an unknown developer. Tap **More details ▸ Install anyway**.
5. For updates, send the new APK and install it over the old one. Data is kept as long as it's signed with the same key.

Google is bringing in developer verification for apps installed outside the Play Store, rolling out by country. If a tester's phone refuses the install, the fallback is a Play Console account ($25 one-time) and its **internal testing** track, which needs no review and takes up to 100 testers by email.

## Device test checklist (needs real phones)
The emulator can't test real voice input or headphones. Testers: Galaxy A52 (Android 14), Galaxy S22 (Android 16), plus the developer's Moto G Play (budget phone, has a headphone jack).
- [ ] Settings ▸ About ▸ **Microphone** line: "Unprocessed mic" or "Voice-recognition mic", and whether any effects were switched off. Send a screenshot.
- [ ] Hum a steady note: the note label matches a tuner app, and the dot moves on the grid.
- [ ] "ee" reads brighter than "oo" on the resonance meter (Vowel ladder exercise).
- [ ] High "ah" sirens (370–480 Hz) stay in the right octave, and creaky speech doesn't read an octave low.
- [ ] Noise banner: appears with a TV or tap running, and clears in a quiet room.
- [ ] Strain alert fires in the iOS strain test (pressed, loud voice for ~30 s). Rest and Dismiss work.
- [ ] A 3+ minute session appears in History after switching away from the app and back.
- [ ] Baseline records, and the hold drill target starts at the baseline note in stage 2.
- [ ] App lock works with fingerprint, face or PIN, and locks again after leaving the app.
- [ ] Delete all data returns to onboarding.
- [ ] **Hear-yourself** (Live ▸ Listen) with wired headphones: you hear your voice, Device delay shows a number (note it per phone), Voice level and Delay sliders work, and there are no crackles.
- [ ] Hear-yourself refuses to turn on with no headphones, and turns itself off (no speaker squeal) when the headphones are unplugged.
- [ ] With Bluetooth earbuds: the Route says Bluetooth and the orange banner shows on Live; closing it works.
- [ ] Headphone volume slider moves with the phone's volume buttons, and turns orange above 75%.

## Hear-yourself (ported, untested on a real phone)
`AudioCapture` reads the mic in 4 ms chunks and plays them through a low-latency `AudioTrack` (pure delay line × voice level), and regroups the chunks into 1024-sample blocks for analysis, so readings match iOS. The output buffer grows a burst at a time if it underruns. If the output is ever routed to the phone speaker, it's muted at once to prevent feedback. Device delay is measured from the mic and output timestamps (median of recent readings), using the same Low / Noticeable / High thresholds as iOS.

## Not yet ported
- Microphone picker: iOS lets you choose a mic; Android always prefers the phone's own mic (the same as iOS "Automatic"). Voice level is therefore one setting, not per mic.
- The real-voice reference comparison runs here too once the WAVs exist (see `Tools/DSPCheck/reference/README.md`).
