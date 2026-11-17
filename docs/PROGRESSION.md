# Progression: new learner → fine tuner (S14)

> **Status:** built in S14 (build 44), with fixes in S16 (build 46).
> - The code is in `Analysis/Progression.swift`, and the exercise evidence review is in `docs/EXERCISE_EVIDENCE.md`.
> - History stage markers were added in S16.
> - **Deviations from the plan below:**
>   - Stage 2 steps move after 2 separate passing days per step.
>   - Stage 5 intonation uses an exercise count, not a semitone target, because the evidence is mixed.
>   - "Big dog / small dog" became "Resonance: forward ↔ back".
>   - An exercise counts only with real voiced time.

Today the Exercises tab is a flat daily list. Real voice training moves through stages over weeks and months. This plan adds that long arc, built on data the app already records, without making any voice "correct" or pushing faster than is safe.

## Principles
- **Suggest, never lock.** The app proposes moving on; the user can jump to any stage or go back at any time.
- **Safety gates progress.** Frequent strain alerts or hitting the daily cap hold a stage in place, with a nudge to rest or see an SLP. Each stage has a minimum number of practice days, because the voice adapts over weeks, not sessions.
- **Goal-relative.** Every criterion is measured against the user's own goal and baseline, never against a gender norm.
- **Review is built in.** Each day mixes the current stage with one earlier exercise, so skills stay in use.

## Stages
| # | Stage | Focus | Example exercises | Ready to move on when… (suggested) |
|---|---|---|---|---|
| 1 | Foundations | Breath, easy onset, comfort, awareness | Warm-up, hums, slides, baseline | 5+ practice days, warm-up done most days, baseline recorded |
| 2 | Pitch | Find and hold notes moving from baseline towards goal | Hold target (target steps up/down 1 semitone at a time), slides | Hold drill passed on 3 separate days at the current step, with no strain alerts |
| 3 | Resonance | Brightness/darkness independent of pitch | Big → small dog, vowel ladder, resonance hold | Median resonance inside the goal band for 2+ minutes in a session, 3 days |
| 4 | Combining | Pitch and resonance together, on the grid | Grid target holds, sustained words, short phrases | 50%+ of voiced time inside the goal zone on the heat map over a week |
| 5 | Speech | Carry it into reading and intonation | Rainbow Passage, phrase lists, intonation contours, free speech | Reading session median in goal zone; pitch variation (semitones) near the target |
| 6 | Fine-tuning | Vocal weight, consistency, endurance, maintenance | Weight drills (lighter ↔ heavier), longer reading, a short maintenance routine | Ongoing: consistency over weeks; the plan shifts to keeping the voice steady |

## Representation in the app
- **Path header** at the top of Exercises: the six stages as steps, with the current one highlighted and a "Ready to move on" ring showing which criteria are met (each with a plain-language line, e.g. "Hold drill passed 2 of 3 days").
- **Today's plan** card under it: warm-up → 2–3 stage exercises → 1 review exercise → cool-down, sized to fit the daily cap. The full exercise library stays below.
- **Hold target steps.** In stage 2 the hold target starts at the baseline and moves 1 semitone towards the goal each time the step is passed, instead of jumping straight to the goal midpoint.
- **History** gains stage markers on the timeline, so progress shows as more than numbers.
- **Settings ▸ Goal** shows the current stage and has a "Choose stage" option.

## Data needed
- New SwiftData model `ExerciseResult` (date, exerciseID, stage, score/passed, held seconds, strain alerts during it). Today's `ExerciseLog` only keeps ticks for the current day.
- A `stage` value and the current hold step in `@AppStorage`, cleared by Delete all data.
- `Exercise` gets `stages: Set<Int>`; new exercises for stages 4–6 (phrases, intonation, weight).
- Criteria are computed from `PracticeSession`, `GridCellCount`, `ExerciseResult` and StrainGuard history. No new DSP, except an intonation measure (pitch variation across phrases, PLAN §5) for stage 5.

## Scheduling
**S14 Progression** (Opus for the criteria and plan logic, about one session), after S13 Polish; the buffer moves to S15.
