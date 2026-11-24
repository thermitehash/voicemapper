# Localisation and accents (S17)

**Decision (Nov 2026):** the app launches in the **US, Canada, UK, Australia and New Zealand**, in English only. French, Spanish and te reo Māori are out of scope for now. Each would need a full translation, a reading passage in that language, and voice norms for that language.

## 1. What changed in the app (done, build 47)

The copy is written once, in British spelling (the existing text). At display time, `Analysis/RegionalSpelling.swift` adapts it to the user's English:

| Variant | Picked when | Spelling | Professional | "Find a voice therapist" opens |
|---|---|---|---|---|
| American | language region US (or device region US) | color, center, gray, practice (verb), recognize, mom | speech-language pathologist | ASHA ProFind |
| Canadian | CA | colour, centre, grey, practise, **recognize**, **mom** | speech-language pathologist | Speech-Language & Audiology Canada |
| British | GB, and the fallback for anywhere else | source text | speech and language therapist | ASLTIP |
| Australian | AU | source text | speech pathologist | Speech Pathology Australia |
| New Zealand | NZ | source text | speech-language therapist | NZ Speech-language Therapists' Association |

- The spelling variant follows the first preferred language (en-US, en-GB…), then falls back to the device region. The directory link follows the device region, because it depends on where the user lives, not on which spelling they read.
- **How it's wired:** UI copy that contains a regional word goes through `Text(regional:)` (`UI/RegionalText.swift`). That covers exercise steps, evidence notes, the strength label, the Rainbow Passage, stage requirements, safety messages, and the literals that contain a regional word.
- **Why not a String Catalog:** much of the copy lives in `Analysis/`, is built with interpolation, and has to stay verbatim for the Android `ParityTest`. Five English variants only differ by a word list, so a word list is simpler and testable.
- **Guard rails:** `Tools/MappingTests` now checks variant detection, the respellings, the professional terms, and that no exercise text keeps a British form for US users. It also lints `UI/*.swift`: a literal with a British form or "speech-language pathologist" must be on a `Text(regional:)` line, or carry a `// regional` comment when it reaches the screen that way indirectly.
- Dates and times already use `formatted()`, so they follow the region. History weeks are rolling 7 days, so week-start differences don't matter. Hz, semitones and cm are scientific units and stay the same everywhere.
- The Rainbow Passage source now reads "colours". US users see Fairbanks' original "colors".
- **Not yet checked on device:** the Canadian SAC link lands on SAC's home page when fetched by a script. Check it in Safari on a phone.

**Adding copy:** write it in British spelling. If it contains a word from the lists in `RegionalSpelling`, show it with `Text(regional:)`. The mapping test fails if you forget.

**Android:** not ported yet. Android needs the same word list and directory table. `ParityTest` doesn't enforce this; it's listed as an Android follow-up.

**App Store Connect (user):**
1. Under Pricing and Availability, limit the app to the five countries.
2. Add English (U.K.), English (Australia) and English (Canada) listings next to English (U.S.), with matching spelling.
3. App Store Connect has no separate New Zealand English listing. Check which listing the NZ store shows.
4. Use "speech pathologist" in the AU description and "speech and language therapist" in the UK one.
5. One thing to confirm before launch, not legal advice: the app shows strain and health measures, and the UK (MHRA), Australia (TGA) and Canada (Health Canada) each regulate software as a medical device. The app is a wellness and training tool with a clear "not medical advice" disclaimer, which usually falls outside that. It's worth checking the wording of the store descriptions against each regulator's guidance.

## 2. Where accents can make the voice mapping wrong

The resonance score is a least-squares fit of formant spacing ΔF over F1–F4 (`ResonanceScorer`). Its 900–1300 Hz scale and the "typical male/female" positions (25/72) come from **American English** vowels: Hillenbrand et al. 1995, Michigan speakers. The pitch zones (145/175 Hz, centre 165 Hz) come from American speakers and listeners (Hillenbrand 1995; Gelfer & Mikos 2005).

How much each formant moves the score, from the fit's weights (k = 0.5, 1.5, 2.5, 3.5) and 4 Hz of ΔF per score point:

| Formant | 4-formant fit | 3-formant fit (F4 missed) |
|---|---|---|
| F1 | 0.006 pt/Hz | 0.014 pt/Hz |
| F2 | 0.018 pt/Hz | 0.043 pt/Hz |
| F3 | 0.030 pt/Hz | 0.071 pt/Hz |
| F4 | 0.042 pt/Hz | — |

So a vowel that moves F2 or F3 by several hundred Hz moves that frame by 10–50 points. The 15-frame median only absorbs moves shorter than about 0.3 s.

**Relative progress is not affected.** The baseline, hold targets, history trends and strain alerts all compare the user with themselves. What accents can bias is **absolute placement**: where the dot falls against the shaded male/ambiguous/female zones, the session colour stripe, and preset goal zones.

### A. Rhotic /r/ reads darker (US, Canada, Scotland, parts of SW England). Priority: high
- **What happens:** r-coloured vowels ("her", "car", "for", "water") pull F3 down to roughly 1600–2000 Hz, against about 2500–3000 Hz in plain vowels. A 700 Hz drop moves those frames about 21 points darker, or about 50 points if LPC also misses F4.
- **Who it affects:** rhotic speakers spend much of connected speech in r-coloured stretches. The Rainbow Passage is full of them: rainbow, raindrops, air, prism, form, colours, there, horizon, according, for. So in free speech and the reading exercise, an American or Scottish speaker reads darker than an English, Australian or NZ speaker with the same vocal tract.
- **Proposed fix:** in `ResonanceTracker`, drop frames whose F3 is more than ~20% below the running median F3 of recent accepted frames. That excludes r-colouring for every accent, which keeps the accents comparable.
  - Don't use an F3−F2 gap test: women's "ee" has F2 and F3 only ~500 Hz apart and would be dropped.
- **Test:** add synthetic /ɝ/ (F3 lowered) and an "r-coloured reading" sequence to `DSPCheck`. Check that the score of a rhotic and a non-rhotic sequence differs by ≤ 3 points, and that the existing 1273 checks don't regress.

### B. Fronted vowels read brighter (Australia, NZ, southern England, younger North Americans). Priority: medium
- **What happens:** GOOSE ("oo") is fronted to [ʉː] in all these accents: F2 is roughly 1600–2200 Hz, against ~1000 Hz for American /u/ in Hillenbrand. GOAT is fronted too.
  - Australian and NZ START ("ah") is central [ɐː], with higher F2 than American /ɑ/.
  - NZ raises TRAP and DRESS ("yes" sounds close to "yis"), which raises F2. NZ KIT is centralised, which lowers it.
- **Size:** an 800 Hz F2 rise moves that vowel about 14 points brighter. Averaged over connected speech, this probably shifts an Australian or NZ speaker a few points brighter than an American with the same tract. The size isn't known yet; it needs measuring.
- **Proposed fix:**
  1. Compute each accent's average ΔF from published formant tables, run through `DSPCheck` synthetic vowels so it uses the app's own scorer. Sources: Cox 2006 (Australian), Watson, Maclagan & Harrington 2000 (NZ), Deterding 1997 or Hawkins & Midgley 2005 (southern British), Clarke, Elms & Youssef 1995 or Boberg 2008 (Canadian).
  2. If an accent's average differs from Hillenbrand by more than 3 points, add a per-accent offset to the typical zone positions and preset goal zones. Never apply it to the user's own score.
  3. Add an **Accent** setting (US, Canada, UK and Ireland, Australia, New Zealand, Other or not sure), defaulting from the device region. Region isn't accent, so the user can change it. "Other or not sure" uses no offset.
  4. Many of these tables don't report F4, so synthesise a standard F4 and note that limit.

### C. "oo" prompts and the forward/back drill. Priority: low
- **What happens:** the "oo" step asks for a darker sound. A speaker with fronted GOOSE who just says "oo" will barely move the score. The step already says "tongue pulled back and lips rounded", which describes the articulation, not the letter, so it works for every accent.
- **Proposed fix:** keep the wording. Optionally add "like a deep 'oo', further back than your everyday 'oo'".
- "ee" in Australian and NZ English starts with a short glide ([ɪi]). Adding "hold it steady rather than gliding" to the vowel ladder steps would help everyone.

### D. "ah" against "ee" in the vowel ladder. Priority: low
- **What happens:** the drill compares the user's "ah" with their own "ee", so accent only changes how far apart the two start. Australian and NZ speakers will find it easier than Americans, since their "ah" is fronter.
- **Proposed fix:** none needed beyond C.

### E. Pitch zones and perception. Priority: low, no change proposed
- **What's known:** the 145/175 Hz boundaries come from North American speakers and listeners. Published differences in average speaking pitch between these five Englishes are small compared with differences between individuals, and there is little listener data from the UK, Australia or NZ.
- **Proposed fix:** don't add per-accent pitch offsets without evidence. Optionally make the grid's info text say "typical ranges from mostly North American research".
  - Pemberton, McCormack & Russell (1998) reported Australian women's speaking pitch falling over several decades. Confirm their figures before citing them anywhere in the app.

### F. Uptalk (high rising terminal; Australia, NZ, and increasingly UK and US). Priority: none
- **What happens:** rises at the end of statements widen the 10–90th percentile pitch range and nudge the median up slightly.
- **Why no fix:** `StrainGuard`'s "sustained top of range" uses a 60 s window, so brief rises don't trigger it. The Intonation exercise only describes the user's range and never marks rises as wrong. Keep it that way.

### G. Real-voice check across accents
When the real-voice reference set is recorded (S16 open item), try to include at least one non-rhotic speaker and one Australian or NZ speaker, and tag each file's accent in `reference.json`. That's the only way to confirm A and B on real voices.

## 3. Proposed next session
**S18 Accent calibration (Opus, DSP):** do A first (the F3 gate, with DSPCheck tests), then measure B. Add the Accent setting only if B's offsets exceed 3 points. Port to `android/core` and run `./gradlew :core:test`.

## Sources
- Boberg, C. (2008). Regional phonetic differentiation in Standard Canadian English. *Journal of English Linguistics*, 36(2), 129–154.
- Clarke, S., Elms, F., & Youssef, A. (1995). The third dialect of English: Some Canadian evidence. *Language Variation and Change*, 7(2), 209–228.
- Cox, F. (2006). The acoustic characteristics of /hVd/ vowels in the speech of some Australian teenagers. *Australian Journal of Linguistics*, 26(2), 147–179.
- Deterding, D. (1997). The formants of monophthong vowels in Standard Southern British English pronunciation. *Journal of the International Phonetic Association*, 27(1–2), 47–55.
- Gelfer, M. P., & Mikos, V. A. (2005). The relative contributions of speaking fundamental frequency and formant frequencies to gender identification. *Journal of Voice*, 19(4), 544–554.
- Hawkins, S., & Midgley, J. (2005). Formant frequencies of RP monophthongs in four age groups of speakers. *JIPA*, 35(2), 183–199.
- Hillenbrand, J., Getty, L. A., Clark, M. J., & Wheeler, K. (1995). Acoustic characteristics of American English vowels. *JASA*, 97(5), 3099–3111.
- Watson, C. I., Maclagan, M., & Harrington, J. (2000). Acoustic evidence for vowel change in New Zealand English. *Language Variation and Change*, 12(1), 51–68.

Citations are from memory. Check them before they go into the in-app Research sources.
