#!/bin/bash
# Builds and runs the DSP accuracy check on macOS. See main.swift.
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${TMPDIR:-/tmp}/dspcheck"
swiftc -O -o "$OUT" "$ROOT/Tools/DSPCheck/main.swift" \
  "$ROOT/VoiceMapper/DSP/PitchDetector.swift" \
  "$ROOT/VoiceMapper/DSP/FormantAnalyzer.swift" \
  "$ROOT/VoiceMapper/Analysis/ResonanceScorer.swift"
"$OUT" "$@"
