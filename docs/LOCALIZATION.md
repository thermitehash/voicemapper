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

### A. Rhotic /r/ reads darker (US, Canada, Scotland, parts of SW England). **Done in S18**
- **What happens:** r-coloured vowels ("her", "car", "for", "water") pull F3 down to roughly 1600–2000 Hz, against about 2500–3000 Hz in plain vowels. A 700 Hz drop moves those frames about 21 points darker, or about 50 points if LPC also misses F4.
- **Who it affects:** rhotic speakers spend much of connected speech in r-coloured stretches. The Rainbow Passage is full of them: rainbow, raindrops, air, prism, form, colours, there, horizon, according, for. So in free speech and the reading exercise, an American or Scottish speaker reads darker than an English, Australian or NZ speaker with the same vocal tract.
- **Proposed fix:** in `ResonanceTracker`, drop frames whose F3 is more than ~20% below the running median F3 of recent accepted frames. That excludes r-colouring for every accent, which keeps the accents comparable.
  - Don't use an F3−F2 gap test: women's "ee" has F2 and F3 only ~500 Hz apart and would be dropped.
- **Test:** add synthetic /ɝ/ (F3 lowered) and an "r-coloured reading" sequence to `DSPCheck`. Check that the score of a rhotic and a non-rhotic sequence differs by ≤ 3 points, and that the existing 1273 checks don't regress.
- **S18 result:** done as proposed, with a 0.8 × running-median-F3 gate.
  - On Hillenbrand's real recordings, real /ɝ/ reads about 40 points darker than the other vowels. The gate catches all 80 /ɝ/ tokens and none of 1012 plain ones.
  - With r-coloured vowels mixed in, speech now reads within 1 point of the same speech without them, on synthetic and real voices.
  - Results are in `docs/ACCURACY.md`. Skipped frames aren't counted as misses, so an "r" doesn't blank the dot.
- **Found along the way:** the app read higher-pitched voices up to 49 points too dark. LPC was locking onto harmonics, which wasn't an accent effect. It's fixed with a pitch-blended LPC order; see `docs/ACCURACY.md` S18. That bias hit everyone with a higher voice, whatever their goal, and it was much larger than any accent effect here.

### B. Fronted vowels read brighter (Australia, NZ, southern England, younger North Americans). **Blocked on data (S18)**
- **What happens:** GOOSE ("oo") is fronted to [ʉː] in all these accents: F2 is roughly 1600–2200 Hz, against ~1000 Hz for American /u/ in Hillenbrand. GOAT is fronted too.
  - Australian and NZ START ("ah") is central [ɐː], with higher F2 than American /ɑ/.
  - NZ raises TRAP and DRESS ("yes" sounds close to "yis"), which raises F2. NZ KIT is centralised, which lowers it.
- **Size:** an 800 Hz F2 rise moves that vowel about 14 points brighter. Averaged over connected speech, this probably shifts an Australian or NZ speaker a few points brighter than an American with the same tract. The size isn't known yet; it needs measuring.
- **Proposed fix:**
  1. Compute each accent's average ΔF from published formant tables, run through `DSPCheck` synthetic vowels so it uses the app's own scorer. Sources: Cox 2006 (Australian), Watson, Maclagan & Harrington 2000 (NZ), Deterding 1997 or Hawkins & Midgley 2005 (southern British), Clarke, Elms & Youssef 1995 or Boberg 2008 (Canadian).
  2. If an accent's average differs from Hillenbrand by more than 3 points, add a per-accent offset to the typical zone positions and preset goal zones. Never apply it to the user's own score.
  3. Add an **Accent** setting (US, Canada, UK and Ireland, Australia, New Zealand, Other or not sure), defaulting from the device region. Region isn't accent, so the user can change it. "Other or not sure" uses no offset.
  4. Many of these tables don't report F4, so synthesise a standard F4 and note that limit.

- **S18 status:** no offset and no Accent setting added, because the published numbers couldn't be obtained in usable form:
  - Watson, Harrington & Evans 1998 (NZ vs Australian) gives monophthongs only as F1/F2 plots.
  - Deterding 1997's table was unreadable from the sources reachable here.
  - Cox 2006 is paywalled.
  - The South Australian table (SST 2006) extracted garbled, and covers women only.
- Being true to the data means not guessing an offset.
- **To unblock, either:**
  1. drop the PDFs of Deterding 1997, Cox 2006 and a Canadian table into `Tools/DSPCheck/reference/`; or
  2. better, record a few non-American speakers (reference README) and tag their accents.
- Most of these studies report only F1/F2, so the offset would be modelled with American F3/F4.
- The rhotic gate already removes the largest accent difference.

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
  - Pemberton et al. (1998) reportedly found Australian women's speaking pitch falling over several decades. Only secondary sources were found, and they conflict, so don't cite it in the app.

### F. Uptalk (high rising terminal; Australia, NZ, and increasingly UK and US). Priority: none
- **What happens:** rises at the end of statements widen the 10–90th percentile pitch range and nudge the median up slightly.
- **Why no fix:** `StrainGuard`'s "sustained top of range" uses a 60 s window, so brief rises don't trigger it. The Intonation exercise only describes the user's range and never marks rises as wrong. Keep it that way.

### G. Real-voice check across accents
When the real-voice reference set is recorded (S16 open item), try to include at least one non-rhotic speaker and one Australian or NZ speaker, and tag each file's accent in `reference.json`. That's the only way to confirm A and B on real voices.

## 3. Who the data describes: trans fem, trans masc and nonbinary voices
The norms behind the scale and zones come from Hillenbrand et al. (1995; cis adult men and women from Michigan) and Gelfer & Mikos (2005). Neither describes trans or nonbinary speakers. What the research says for each group, and what it means for the app:

- **Trans fem.**
  - This group has the most evidence. Leung, Oates & Chan (2018, JSLHR 61(2):266–297; meta-analysis) found speaking F0 accounted for 41.6% of the variance in perceived gender. Resonance, loudness, articulation and intonation were also associated with it, and findings on breathiness were mixed.
  - Formant biofeedback studies (Carew et al. 2007; Kawitzky & McAllister 2020; both in the exercise evidence) were done with trans women.
  - **Impact:** the S18 high-pitch fix matters most here. Before it, a voice raised into the 180–230 Hz range read up to 49 points darker than it was, which would have told a person their resonance was moving the wrong way.
- **Trans masc.**
  - A meta-analysis of 19 studies found testosterone lowers F0, but voice–gender congruence isn't always reached.
  - Formant changes are small: one study found transmasculine speakers' formants only about 1.05× lower than cis women's.
  - Results vary a lot between people ("make no assumptions": a first-year testosterone study, PubMed 28892815). So resonance can lag behind pitch.
  - **Impact:** as F0 drops through 175 → 150 Hz the analyser blends LPC orders. The new pitch-independence test shows a fixed mouth shape scores within ±3 across that band, so a falling pitch doesn't fake a darker resonance. The resonance evidence notes cite trans fem studies; trans masc resonance-training evidence is thinner. Say so in the notes rather than implying the same strength of evidence.
- **Nonbinary.**
  - Data is thin. One study of AFAB nonbinary speakers found most combined an intermediate F0 with a brighter voice quality (less negative spectral slope, higher CPP), a cue mix not seen in the cis groups.
  - Gender-expansive listeners judge voices on more than one dimension (Hope & Lilley). A 2025 review notes wide variability in transmasculine and nonbinary acoustic profiles.
  - **Impact:** the male/ambiguous/female zones are a description of cis listener norms, not targets. Custom zones and the androgynous preset already exist. Keep goal zones free to sit anywhere on the grid, including combinations the norms call uncommon.

**Priority (user decision, Nov 2026):** trans women are the main audience. They have the most research, and they get no help from hormones on voice, while testosterone lowers pitch for many trans men. So effort goes first to the feminising side: accuracy at higher pitches, the feminising-goal exercise paths, and device testing with feminising goals. This sets where effort goes. It doesn't change what the app measures, and other goals keep full support.

**Rules this repo follows (user decision, Nov 2026):**
- Measurement, corrections and localisation must be true to the data and neutral between goals.
- A correction may only move readings because the measurement was wrong, as with the harmonic lock-in, rhotic frames and pitch independence. Never because of who the user is or which goal they picked.
- Anything that pushes in one direction (stepped hold targets, "brighter" or "darker" hints, plan wording) may only push in the direction of the user's own goal.
- Exercises stay two-way (forward ↔ back, lighter ↔ heavier, slides both ways around the user's goal), and `MappingTests` already fails on goal-specific step wording.
- When evidence for a technique comes from only one group, the evidence note says which.

Sources for this section: [Leung et al. 2018 (ERIC)](https://eric.ed.gov/?id=EJ1171925), [transmasculine F0 meta-analysis and resynthesis study (PMC9584127)](https://pmc.ncbi.nlm.nih.gov/articles/PMC9584127/), [Trans male voice in the first year of testosterone (PubMed 28892815)](https://pubmed.ncbi.nlm.nih.gov/28892815/), [Bright voice quality and F0 variation in non-binary speakers (J Voice 2022)](https://www.sciencedirect.com/science/article/abs/pii/S089219972200234X), [Gender-expansive listeners (ResearchGate)](https://www.researchgate.net/publication/356503371_Gender_expansive_listeners_utilize_a_non-binary_multidimensional_conception_of_gender_to_inform_voice_gender_perception), [Voice gender diversity review 2025 (PMC12539971)](https://pmc.ncbi.nlm.nih.gov/articles/PMC12539971/). These were read at abstract level; check full papers before quoting numbers in the app.

## 4. Next steps
- **S18 (done, build 48):** rhotic gate, high-pitch LPC fix, real-voice tests, Android port.
- **S19 (done, build 49 / Android 0.3):** citations above verified; exercise wording tweaks C done (a deep "oo" further back, hold "ee" steady); evidence notes name who each study covered; Android regional spelling.
- **Still open:** accent offsets (B, blocked on data: non-American recordings or the PDFs would unblock it); the scale's typical positions on connected speech (`docs/ACCURACY.md`).

## Original proposal (S17)
**S18 Accent calibration (Opus, DSP):** do A first (the F3 gate, with DSPCheck tests), then measure B. Add the Accent setting only if B's offsets exceed 3 points. Port to `android/core` and run `./gradlew :core:test`.

## Sources
- Boberg, C. (2008). Regional phonetic differentiation in Standard Canadian English. *Journal of English Linguistics*, 36(2), 129–154.
- Clarke, S., Elms, F., & Youssef, A. (1995). The third dialect of English: Some Canadian evidence. *Language Variation and Change*, 7(2), 209–228.
- Cox, F. (2006). The acoustic characteristics of /hVd/ vowels in the speech of some Australian teenagers. *Australian Journal of Linguistics*, 26(2), 147–179.
- Deterding, D. (1997). The formants of monophthong vowels in Standard Southern British English pronunciation. *Journal of the International Phonetic Association*, 27(1–2), 47–55.
- Gelfer, M. P., & Mikos, V. A. (2005). The relative contributions of speaking fundamental frequency and formant frequencies to gender identification based on isolated vowels. *Journal of Voice*, 19(4), 544–554.
- Hawkins, S., & Midgley, J. (2005). Formant frequencies of RP monophthongs in four age groups of speakers. *JIPA*, 35(2), 183–199.
- Hillenbrand, J., Getty, L. A., Clark, M. J., & Wheeler, K. (1995). Acoustic characteristics of American English vowels. *JASA*, 97(5), 3099–3111.
- Watson, C. I., Maclagan, M., & Harrington, J. (2000). Acoustic evidence for vowel change in New Zealand English. *Language Variation and Change*, 12(1), 51–68.

**Checked (S19, Nov 2026):** volume and pages were confirmed against publisher or index records for all entries above. Gelfer & Mikos's title was corrected to the full form.
- Also confirmed: Watson, Harrington & Evans (1998), An acoustic comparison between New Zealand and Australian English vowels, *Australian Journal of Linguistics*, 18(2), 185–207. Read in full: F1/F2 is given in plots only, with no tables of means.
- **Not verified:** Pemberton et al. (1998, *Journal of Voice*) on Australian women's speaking pitch falling between the 1940s and 1990s. The study exists according to secondary sources, but they disagree on the authors, affiliation and figures. Don't cite it in the app until the original is read.
