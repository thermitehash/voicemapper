# Google Play internal testing: setup and answers

The upload file is `android/dist/VoiceMapper-android-<version>.aab` (git-ignored). Build it with:
```
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd android && ./gradlew :app:bundleRelease   # → app/build/outputs/bundle/release/app-release.aab
```
Every upload needs a higher `versionCode` in `app/build.gradle.kts`.

If R8 fails with "Type … is defined multiple times" on a file named `… 2.class`, something (usually iCloud Drive syncing Documents) duplicated build files. Delete `app/build` and `core/build`, then build again.

## Create app
| Field | Answer |
|---|---|
| App name | VoiceMapper |
| Default language | English (United Kingdom) or (United States) |
| App or game | App |
| Free or paid | Free |

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
- **Feature graphic:** 1024×500 PNG.
- **Phone screenshots:** at least 2.
