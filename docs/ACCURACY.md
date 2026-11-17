# DSP accuracy check (S13)

How closely VoiceMapper's pitch, formant and resonance measurements match known values, and what changed as a result.

## Method
`Tools/DSPCheck/` compiles the app's own `PitchDetector`, `FormantAnalyzer` and `ResonanceScorer` for macOS and runs them outside the app.

- `Tools/DSPCheck/run.sh` runs synthetic vowels with **known** F0 and formants: a Klatt-style source–filter model with Rosenberg glottal pulses, 0.5% jitter, 3% shimmer, a cascade of formant resonators, lip radiation and light noise. Vowel targets are approximate men's and women's averages from Hillenbrand et al. (1995). Known ground truth makes this a stricter test than comparing against Praat, which also estimates.
- `run.sh --probe` adds finer sweeps: pitch every 5 Hz from 90 to 600 Hz on several vowels, alternating-amplitude pulses (diplophonia), breathy noise, and resonance against F0.
- `run.sh a.wav b.wav …` prints median F0, F1–F4, ΔF and score per file, for comparing real recordings with Praat (see below).

Praat isn't installed on the dev Mac. The real-voice comparison is left as an optional step for the user.

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
- **High open vowels in singing range** (/ɑ/ at 455–475 Hz) can read an octave high. That's above speaking range.
- **Heavy breath noise** at 200 Hz (noise at a third of peak level) puts 41% of frames off. That's the same as before the fix.

## Comparing real recordings with Praat (optional)
1. Record a few 2–3 second sustained vowels ("ee", "ah", "oo") and a sentence as WAV files, for example with Voice Memos exported as WAV, or QuickTime.
2. In Praat, open each file and choose View & Edit:
   - Pitch ▸ Get pitch (median from the Pitch listing; pitch range 60–600 Hz).
   - Formant ▸ Formant listing, with maximum formant 5000 Hz for low voices or 5500 Hz for higher ones and 5 formants.
3. Run `Tools/DSPCheck/run.sh file1.wav file2.wav …` and compare F0 and F1–F3.
4. Expect F0 within a few cents and F1–F3 within about 10%. Larger gaps on "oo", or above 300 Hz, match the limits above.

## Sources
- de Cheveigné, A., & Kawahara, H. (2002). YIN, a fundamental frequency estimator for speech and music. *JASA*, 111(4), 1917–1930.
- Hillenbrand, J., Getty, L. A., Clark, M. J., & Wheeler, K. (1995). Acoustic characteristics of American English vowels. *JASA*, 97(5), 3099–3111.
- Klatt, D. H. (1980). Software for a cascade/parallel formant synthesizer. *JASA*, 67(3), 971–995.
