# DSP accuracy check (S13, S15)

How closely VoiceMapper's pitch, formant and resonance measurements match known values, and what changed as a result.

## Method
`Tools/DSPCheck/` compiles the app's own `PitchDetector`, `FormantAnalyzer` and `ResonanceScorer` for macOS and runs them outside the app.

- `Tools/DSPCheck/run.sh` runs synthetic vowels with **known** F0 and formants: a Klatt-style source–filter model with Rosenberg glottal pulses, 0.5% jitter, 3% shimmer, a cascade of formant resonators, lip radiation and light noise. Vowel targets are approximate men's and women's averages from Hillenbrand et al. (1995). Known ground truth makes this a stricter test than comparing against Praat, which also estimates.
- `run.sh --probe` adds finer sweeps: pitch every 5 Hz from 90 to 600 Hz on several vowels, alternating-amplitude pulses (diplophonia), breathy noise, and resonance against F0.
- `run.sh a.wav b.wav …` prints median F0, F1–F4, ΔF and score per file, for comparing real recordings with Praat (see below).

Praat isn't installed on the dev Mac. The real-voice reference set (S15) is ready to fill in: see `Tools/DSPCheck/reference/README.md`.

## Results after the S13 fixes

**Pitch.** On the neutral vowel from 65 to 600 Hz, the worst median error is 0.7 cents and no frames are off by an octave. The 5 Hz sweep over women's /i/ /æ/ /u/ and the neutral vowel shows no octave errors.

**Formants.** F1–F3 come within about 10–70 Hz of truth. ΔF is within 1–2% for every vowel except women's /u/ (see the limits below). The resonance score is within 0–3 points of truth on 7 of 8 vowels.

**Score scale.** Averaged across the four vowels, true ΔF is about 1020 Hz for men (score ≈ 30) and 1200 Hz for women (score ≈ 75). That agrees with `PerceivedGender.typicalResonance` (25 / 72), so the 900–1300 Hz mapping in `ResonanceScorer` stands.

## Problems found and fixed

1. **Pitch read an octave high (sometimes two octaves) whenever a harmonic sat on F1.** For example, every frame at F0 260 Hz on a neutral vowel, women's /i/ at 200–235 Hz, /æ/ at 295–370 Hz, and /ɑ/ at 230–245 Hz with F1 on the 4th harmonic. The strong harmonic makes a fractional-period dip in YIN's difference function slip just under the 0.15 threshold (≈0.1–0.15), while the true period's dip is far deeper (≈0.001).
   - **Fix.** A harmonic guard in `PitchDetector` checks multiples ×2…×6 of the chosen lag and takes the first whose dip is under half as deep *and* lower by at least 0.07.
   - The old octave-down guard now runs only on frames with no clear dip. It had been making the problem worse.
   - This matters most for feminine-goal voices, which sit right in the affected 180–300 Hz band.
2. **The resonance score collapsed above about 350 Hz.** For example, women's /i/ read 14 at 370 Hz and 0 at 400 Hz against a true 100, and the tracker accepted frames up to 450 Hz. The gate is now 350 Hz, so above that the app shows no resonance reading rather than a wrong one.

## Known limits
- **Resonance between 260 and 350 Hz is patchy** when harmonics fall badly against F1/F2: steady /ɑ/ at 260–280 Hz and /æ/ at 280 Hz can read 0. Real voices waver, and the tracker's 15-frame median absorbs one-off misreads, but sustained high, open vowels may still misread.
- **Weak F4 (back rounded vowels such as /u/).** When LPC misses F4, ΔF is fitted to F1–F3 only and reads darker. Women's /u/ reads 24 against a true 50. Front vowels ("ee") and open vowels ("ah") are reliable, which is why the vowel exercises use them.
- **Diplophonia.** With strongly alternating cycles (40% amplitude alternation), pitch can read an octave low. The waveform really does repeat every two cycles, so this is ambiguous by nature. Mild alternation (≤20%) is fine.
- **Heavy breath noise** at 200 Hz (noise at a third of peak level) puts 41% of frames off. That's the same as before the fix.

## Automated verification (S15)
Two command-line suites turn this page into pass/fail checks. Both exit non-zero on any failure, so they can gate a build.

**`Tools/DSPCheck/run.sh --test`** runs the app's own DSP code on synthetic vowels with known values, plus real recordings when they exist. Result: **1273 of 1273 checks pass.**

| Check | Tolerance | Scope |
|---|---|---|
| F0 median | within 5 cents | 65–400 Hz in 5 Hz steps, neutral + 8 vowels |
| Octave errors | ≤ 5% of frames > 50 cents off | same sweep |
| Mild diplophonia | ≤ 10% of frames off | 10% and 20% alternation at 120–260 Hz |
| F1–F3 | within 10% | all 8 vowels |
| ΔF | within 3% | front and open vowels (/u/ excluded: known F4 limit) |
| Resonance score | within 5 points | front and open vowels |
| Resonance gate | no reading above 350 Hz, readings below it | `ResonanceTracker` at 300–450 Hz |
| Real voice vs Praat | F0 within 20 cents, F1–F3 within 10% | `Tools/DSPCheck/reference/` (waiting on recordings) |

**`Tools/MappingTests/run.sh`** compiles `Analysis/` and `Store/Models.swift` for macOS and checks the mapping layer against known values. Result: **1520 of 1520 checks pass.**
- `Note` Hz↔MIDI↔name round trips (A4 = 440, C4 = 261.63, semitone rounding).
- `PerceivedGender` boundaries at 145 / 175 Hz.
- `GoalPreset` ranges stay inside their zones, listed alphabetically.
- `ResonanceScorer` endpoints and the uniform-tube ΔF (1000 Hz → 17.5 cm).
- `GridCellCount.bin` edges.
- `HeatmapBinner` totals and periods.
- `GoalRange.closeness` and `direction`, and `ResonanceGoal`.
- `StrainGuard` on scripted inputs: steady, one measure off, two off, sustained high, sustained loud, noisy, alerts off, cooldown, daily cap, "5 more minutes".
- `ExerciseLog` day rollover.
- Progression: plan sizing to the cap, hold steps, safety hold, zone share, resonance days.
- Exercise library: a basis on every exercise, goal-neutral wording, stage coverage.

A deliberate mutation (moving the 145 Hz boundary to 146) was caught, so the suite tests values, not just that the code runs.

**Fixed by S15.** The sweep found pitch reading an octave high at 370–395 Hz on an /ɑ/ with men's formants, where F1 sits on the 2nd harmonic. The false half-period dip there is only 0.05–0.07 deep, just under the S13 harmonic guard's 0.07 entry gate, while the true period's dip is about 0.001.
- **New rule:** `PitchDetector` adds a second harmonic-guard rule, so a multiple-lag dip at least 10× deeper also wins.
- **Limit on the rule:** it only applies when the corrected pitch is 300 Hz or higher. Below that, mild diplophonia (cycles alternating ~20%) has the same dip pattern. In testing it read an octave low on every frame at 200 Hz, the core speaking range for feminine goals.
- **Bonus:** the same fix cleared the S13 limit at /ɑ/ 455–475 Hz.
- **Regressions:** none on the S13 tables.

## Real voices and accents (S18)
Hillenbrand et al.'s (1995) original recordings are public (45 men, 48 women, 12 /hVd/ vowels, with hand-checked formants; [github.com/santiagobarreda/hillenbrand_et_al_1995](https://github.com/santiagobarreda/hillenbrand_et_al_1995)). With them unpacked, `DSPCHECK_H95=<folder> Tools/DSPCheck/run.sh --test` adds real-voice checks. Copy `vowdata.dat` into the folder too; the recordings themselves are not committed.

**Found: the app read higher voices too dark.**
- At 16 LPC poles, a voice's sparse harmonics at ~200 Hz and up let a spare pole lock onto a harmonic (3–4 × F0). That pushed every formant up a slot.
- Mean score error, app vs measured formants, women: "hod" −49, "had" −35, "head" −34, "hud" −19, "hid" −14. Men were within about ±9.
- The synthetic tests missed it because only four vowels were tested, and the synthetic harmonics were too clean.
- This biased every higher-pitched voice towards the dark end, whatever the speaker's goal.

**Fix.**
- `FormantAnalyzer.Poles.highPitch` (14 poles) is used above 175 Hz and the standard 16 below 150 Hz, blended linearly in between, so nothing jumps around 160 Hz.
- The choice follows the measured pitch, not any setting about the person.

| Error by F0 band (Hillenbrand tokens) | 16 poles | 14 poles |
|---|---|---|
| < 150 Hz (men) | bias −0.5 / +0.4, MAE 6–7 | +3 / +5, MAE 8 |
| 150–170 Hz | −2.0, MAE 7.3 | +3.2, MAE 6.0 |
| 170–190 Hz | −11.3, MAE 15.6 | +5.1, MAE 7.2 |
| 190–230 Hz (women) | −16 to −20, MAE 19–23 | +3, MAE 6–7 |
| > 230 Hz | −14.6, MAE 17.9 | +4.2, MAE 7.0 |

**After the fix**, the mean error per sex × vowel is within ±6.4 for every vowel except four:
- men's "heed" −8.5;
- women's "hawed" +9.1, "hud" +10.2 and "hood" +11.6 (back vowels, the known weak-F4 class).

The average size of error is 3.8 points. Women's mean error is 3.0 points brighter than men's, against 16.5 points darker before the fix.

**New checks:**
- **Real resonance:** each sex × vowel mean error ≤ 12, average ≤ 5, women–men gap ≤ 5. Forcing the old 16-pole order fails 10 checks.
- **Pitch independence:** the same formants at any pitch from 110 to 240 Hz must score within 6 points, so moving pitch towards a goal, in either direction, doesn't fake a resonance change. Currently ≤ 5.
- **Rhotic gate** (`ResonanceTracker`): frames with F3 below 0.8 × the running median F3 are skipped. On Hillenbrand's data that threshold catches all 80 /ɝ/ tokens and 0 of 1012 plain-vowel tokens.
  - Synthetic speech with r-coloured vowels a third of the time now reads within 0.8 points of the same speech without them, against 13–22 points darker ungated.
  - On real voices (each talker's 11 vowels, with "heard" added after every third), the mean shift is −0.4 for men and −0.9 for women, against −4.4 and −6.0 ungated.

**Open: the scale's typical positions.** On real recordings, the app's mean score over 11 plain vowels is about 31 for men and 73–76 for women. `PerceivedGender.typicalResonance` says 25 and 72 (from four vowels). Running speech is dominated by reduced vowels, which sit nearer the neutral tube (≈ 25 for men). So which is right depends on the vowel mix of real speech. Don't change it until real connected-speech recordings are measured.

## Comparing real recordings with Praat
1. Record a few 2–3 second sustained vowels ("ee", "ah", "oo") and a sentence as WAV files, for example with Voice Memos exported as WAV, or QuickTime.
2. In Praat, open each file and choose View & Edit:
   - Pitch ▸ Get pitch (median from the Pitch listing; pitch range 60–600 Hz).
   - Formant ▸ Formant listing, with maximum formant 5000 Hz for low voices or 5500 Hz for higher ones and 5 formants.
3. Run `Tools/DSPCheck/run.sh file1.wav file2.wav …` and compare F0 and F1–F3.
4. Expect F0 within a few cents and F1–F3 within about 10%. Larger gaps on "oo", or above 300 Hz, match the limits above.

## Sources
- de Cheveigné, A., & Kawahara, H. (2002). YIN, a fundamental frequency estimator for speech and music. *JASA*, 111(4), 1917–1930.
- Hillenbrand, J., Getty, L. A., Clark, M. J., & Wheeler, K. (1995). Acoustic characteristics of American English vowels. *JASA*, 97(5), 3099–3111.
- Hillenbrand et al. (1995) recordings and vowdata.dat, hosted with permission at github.com/santiagobarreda/hillenbrand_et_al_1995.
- Klatt, D. H. (1980). Software for a cascade/parallel formant synthesizer. *JASA*, 67(3), 971–995.
