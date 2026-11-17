#!/bin/bash
# Builds and runs the mapping-layer tests on macOS. See main.swift.
# Exits non-zero if any check fails.
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${TMPDIR:-/tmp}/mappingtests"
swiftc -O -swift-version 6 -parse-as-library -module-name VoiceMapper -o "$OUT" \
  "$ROOT/Tools/MappingTests/main.swift" \
  "$ROOT"/VoiceMapper/Analysis/*.swift \
  "$ROOT/VoiceMapper/Store/Models.swift" \
  "$ROOT/VoiceMapper/DSP/FormantAnalyzer.swift" \
  "$ROOT/VoiceMapper/DSP/VoiceQualityAnalyzer.swift" \
  "$ROOT/VoiceMapper/DSP/VoiceQualityTracker.swift"
"$OUT" "$@"
