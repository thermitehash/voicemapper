# Google Play internal testing: setup and answers

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
| Default language | English (United Kingdom) or (United States) |
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
