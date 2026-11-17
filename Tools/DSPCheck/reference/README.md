# Real-voice reference set (S15)

`Tools/DSPCheck/run.sh --test` compares the app's pitch and formant readings on real recordings against Praat. Until `reference.json` exists, this part is skipped and the synthetic checks still run.

## 1. Record (about 10 files, 2–3 seconds each)
Record at a comfortable volume in a quiet room. Use Voice Memos (Share ▸ Save to Files, then convert to WAV) or QuickTime, and save the files in this folder:

| File | What to say |
|---|---|
| `ee_low.wav` | sustained "ee", lower comfortable voice |
| `ah_low.wav` | sustained "ah", lower voice |
| `oo_low.wav` | sustained "oo", lower voice |
| `ee_high.wav` | sustained "ee", higher voice |
| `ah_high.wav` | sustained "ah", higher voice |
| `oo_high.wav` | sustained "oo", higher voice |
| `ee_mid.wav` | sustained "ee", everyday speaking voice |
| `ah_mid.wav` | sustained "ah", everyday speaking voice |
| `sentence_low.wav` | "When the sunlight strikes raindrops in the air, they act as a prism.", lower voice |
| `sentence_high.wav` | the same sentence, higher voice |

The WAVs are git-ignored, so your voice stays on this Mac.

## 2. Measure in Praat
Install Praat from praat.org (or `brew install --cask praat`), then:

```
/Applications/Praat.app/Contents/MacOS/Praat --run Tools/DSPCheck/reference/measure.praat \
  "$PWD/Tools/DSPCheck/reference" > Tools/DSPCheck/reference/reference.json
```

## 3. Test
```
Tools/DSPCheck/run.sh --test
```

**Tolerances:**
- F0 within 20 cents of Praat. Both estimate from a voice that wavers, so this is looser than the 5-cent synthetic check.
- F1–F3 within 10%.
- "oo" and pitches above 300 Hz can be out on F2/F3. See "Known limits" in `docs/ACCURACY.md`.
