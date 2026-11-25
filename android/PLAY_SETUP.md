# Google Play: setup, answers and release steps

**Package name:** `com.thermitehash.voicemapper` (permanent; changed from `com.thrivestorm2` before the first upload so the public Play link carries the developer name; the Kotlin source packages are still `com.thrivestorm2.voicemapper`). **Price:** $4.99. **Current versionCode:** 4 (0.4).

The upload file is `android/dist/VoiceMapper-android-<version>.aab` (git-ignored). Build it with:
```
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd android && ./gradlew :app:bundleRelease   # → app/build.nosync/outputs/bundle/release/app-release.aab
```
Every upload needs a higher `versionCode` in `app/build.gradle.kts`.

Builds go to `app/build.nosync` so iCloud Drive doesn't duplicate build files (see README). If R8 still fails with "defined multiple times", delete `app/build.nosync` and `core/build.nosync` and build again.

## Create app
| Field | Answer |
|---|---|
| App name | VoiceMapper |
| Default language | English (United States) (as used) |
| App or game | App |
| Free or paid | Paid ($4.99). Pick Paid here: Play never lets a free app become paid later |

## App content answers (Dashboard ▸ "Set up your app")
| Section | Answer |
|---|---|
| Privacy policy | https://github.com/thermitehash/voicemapper/blob/main/PRIVACY.md |
| App access | All functionality is available without special access |
| Ads | No, my app does not contain ads |
| Content rating | Category: All other app types. Answer **No** to every question (no violence, sexual content, profanity, drugs, gambling, user interaction or sharing, location sharing, purchases) |
| Target audience | 18 and over |
| Data safety | Does your app collect or share any of the required user data types? **No.** (Audio is processed only on the device and never stored or sent, which Google does not count as collection.) |
| Government app | No |
| Financial features | None |
| Health apps | Pick the closest non-medical option (e.g. fitness/wellness or "none"). It is **not** a medical device and makes no diagnosis |
| News app | No |

## Store listing (needed before going public; internal testing may ask for some of it)
- **Short description (80 chars):** Map your voice's pitch and resonance, and train towards your own goal, safely.
- **Full description:**
  VoiceMapper helps you train your voice towards the goal you choose. There's no "correct" voice.
  • Live pitch and resonance on a simple grid, with your goal range marked
  • Guided exercises with a daily plan that adapts as you progress
  • Strain alerts and a daily practice cap to protect your voice
  • History of your practice, with heat maps and trends
  • Fully private: no accounts, no internet, no tracking. Nothing leaves your phone.
  VoiceMapper gives estimates to support practice; it is not a medical device.
- **App icon:** 512×512 PNG (`android/dist/play-icon-512.png`, made from the iOS AppIcon).
- **Feature graphic:** `android/dist/play-feature-1024x500.png`.
- **Phone screenshots:** `android/dist/play-screenshots/` (five, 1080×2160; Play rejects anything taller than 2:1). Regenerate on the emulator with the debug extras `--ez debugSeed true --es debugGoal 52,59 --ez debugReading true` (the last shows a sample G3 reading, since the emulator has no voice input), then fit each onto a 1080×2160 canvas with `sips -Z 2160` and `sips -p 2160 1080 --padColor FEF7FF`.

## Store settings and listing extras
| Field | Answer |
|---|---|
| Category | Health & Fitness (not Medical) |
| Tags | Up to 5 from Google's list: voice/speech, wellness, self-improvement, training, progress tracking. Nothing medical |
| Health features, "other" (250 chars max) | Voice training: live pitch and resonance estimates, guided vocal exercises, practice history, and voice-care safety (strain alerts, daily time limit). Audio stays on device. Not a medical device; does not diagnose or treat any condition. |
| Age restriction | Restrict to 18 and over (matches target audience; revisit only with an SLP review) |
| AI asset declaration | Don't label assets, unless the icon or feature graphic came from an AI image generator. It covers listing images only, not AI help with the code or text |

## Payments profile (needed for a paid app)
Setup ▸ Payments profile. Business name and customer-facing name: `thermitehash`. What you sell: apps / software / digital goods. Website: the GitHub repo. Use a support email you're happy for buyers to see. Legal name and address are for Google's verification and tax; check before saving whether anything is marked as shown publicly.

App pricing (Monetise ▸ App pricing) was blocked with "finish setting up your app" while the app was still a draft; retry after the first full review. Set it per country for AU, CA, IS, NZ, UK, US.

## Release steps
**Internal testing** (no review, listed testers only)
1. Testing ▸ Internal testing ▸ Testers: email list with each tester's Play Store Google account (including your own phone's), ticked.
2. Releases ▸ Create new release ▸ accept Play App Signing (Google-generated key; the local keystore is the upload key, keep it safe) ▸ upload the `.aab` ▸ name + notes ▸ Save and publish.
3. Testers ▸ Copy link. Testers open it on the phone signed into the listed account ▸ Accept ▸ install from Play.
4. A brand-new app's first release can show **"item not found"** for a few hours. Leave it and retry; it isn't a setup error.

**Closed testing (Alpha)** (full review; needed before production)
1. Closed testing ▸ Alpha: countries AU, CA, IS, NZ, UK, US; testers (email list or Google Group).
2. Create new release ▸ **Add from library** to reuse an uploaded build, or Internal testing ▸ Promote release. Uploading the same `.aab` again is refused ("version code already used").
3. Publishing overview ▸ Send changes for review.
4. Production access (personal account): 12+ testers opted in for 14 consecutive days, then Apply for production.

Release notes (default language en-US):
```
<en-US>
First test build. Please check: the microphone works, the pitch and resonance readings move with your voice, hear-yourself with wired headphones, and the exercises and plan.
</en-US>
```

The "contains native code, no debug symbols" warning is harmless: the only native code is a pre-stripped androidx library (`libandroidx.graphics.path.so`), so `debugSymbolLevel` adds nothing.

## Status (Nov 25 2026)
- Internal testing: 0.3 (versionCode 3), installed and working on the Moto G Play.
- Closed testing Alpha: 0.4 (versionCode 4, same code as 0.3), sent for review Nov 25 2026; approval still pending.
- Alpha opt-in link: https://play.google.com/apps/testing/com.thermitehash.voicemapper (works only for accounts on the Alpha tester list or Google Group, and only once the review passes).
- Still to check: whether testers are charged for the paid app; app pricing once the review passes.
