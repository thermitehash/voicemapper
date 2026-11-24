# VoiceMapper — approved plan

Guiding principle: whatever helps the user accomplish their voice goal safely.

## 1. Hear yourself through headphones (toggle)
- AVAudioEngine mic → mixer → output; `.playAndRecord`, ~5 ms IO buffer.
- Only enabled when headphones are connected (prevents speaker feedback).
- 1a. When enabled and the route is Bluetooth: banner "Wired headphones recommended: Bluetooth adds ~150–250 ms delay." Re-shown on route change; dismissible, not permanently hidden.
- 1b. Volume slider with safe-listening cap + warning; delay slider for delayed-feedback drills.

## 2. Pitch → note
- YIN or McLeod pitch detection, voicing gate; `midi = 69 + 12·log2(f/440)`.
- 2a. Note label only (e.g. G4) — no needle/cents — inside a table of all vocal ranges:
  note, Hz, classical voice type, rows colour-coded by typical speaking-voice gender perception
  (usually read male / ambiguous-androgynous / usually read female), from speaking-F0 research.
  Current row highlighted and auto-scrolled. Legend: resonance matters too. Colour-blind-safe with text labels.

## 3. Pitch & resonance
- Pitch: median, 10–90th percentile range, variability in semitones.
- Resonance: LPC formants F1–F3(/F4) → vocal tract length estimate, formant spacing → brightness score. Vowel-guided exercises improve reliability.

## 4. Pitch × resonance grid
- X: dark → bright resonance; Y: log pitch with note labels; typical male / female / androgynous zones.
- Live smoothed dot + trail; draggable target zone.
- 4a. Toggleable history overlay: session averages joined in time order.
- 4b. Heat map of time-in-cell (voiced time only), with Today / Week / Month / All time selector.

## 5. Research-backed measures (safety-focused)
- Vocal weight: H1–H2, spectral tilt, CPP (shown neutrally as lighter ↔ heavier).
- Health: HNR, jitter, shimmer vs the user's own baseline.
- Strain guard: rest alerts on declining health measures, sustained top-of-range pitch or loudness; daily practice cap; warm-up/cool-down prompts.
- Intonation: pitch movement across phrases.
- Sources: Hillenbrand & Clark 2009; Gelfer & Mikos 2005; Leung, Oates & Chan 2018; Hillenbrand et al. 1995. Validate against Praat.

## 6. App structure
- Tabs: Live (grid) · Range · Exercises · History · Settings.
- Goal setup: feminine / masculine / androgynous / custom zone; baseline recording centres the grid.
- Privacy: optional on-device recordings, delete-all, optional Face ID lock.
- Disclaimer: not a substitute for a speech-language pathologist; link to finding gender-affirming voice therapy.

## 7. Launch and data rules (Nov 2026)
- **Markets:** US, Canada, UK, Australia and New Zealand, in English only. Regional spelling, professional names and therapist directories are in `docs/LOCALIZATION.md`.
- **Data rule:** measurement and corrections stay true to the data and neutral between goals. Anything directional points only towards the user's own goal.
- **Audience priority:** trans women first, because voice training is their main route and they have the most research. Every goal stays fully supported.
- **Accuracy:** S18 found and fixed higher voices reading too dark. The real-voice results are in `docs/ACCURACY.md`.

## Risks
- Formants noisy at high F0 → vowel-guided exercises, median smoothing, confidence gates.
- Norms vary by person and language → present as typical ranges, not rules.
- Strain detection is heuristic, not diagnostic → state clearly, point to an SLP.

## Maybe later: version for friends without iPhones
**Update (Nov 2026):** built as native Android (Kotlin/Compose) in `android/`, because two Android testers are lined up. The DSP and analysis are ported with the same test suites and a parity check against the Swift sources. The whole app is ported, including hear-yourself (version 0.2), and it's being distributed through Google Play internal testing. See `android/README.md`, and the Android status table in `CLAUDE.md`. The notes below are the original options.

Not scheduled. Revisit after S16, the real-voice check and the SLP review, so the port uses checked algorithms.
- **Preferred: web app (PWA), ~5–7 sessions.** Runs in any phone browser, add-to-home-screen, fully local and offline after first load (static hosting, e.g. GitHub Pages). Port DSP + Analysis to TypeScript and port both test suites with the same tolerances. Mic with echo cancellation, noise suppression and auto gain off. Scope: live pitch/resonance, grid, range table, exercises, progression, history, export/backup. Leave out hear-yourself (browser latency) and the Bluetooth banner (not detectable); Face ID lock optional later via passkeys.
- **Alternatives:** native Android in Kotlin/Compose (~12 sessions; needs an Android phone, $25 Play account, low-latency audio and mic-processing risks); Skip (SwiftUI → Android; audio/DSP/SwiftData still need Android code); lending a used iPhone (SE 2nd gen or later runs iOS 17).
