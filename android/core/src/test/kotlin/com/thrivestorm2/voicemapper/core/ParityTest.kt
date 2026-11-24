package com.thrivestorm2.voicemapper.core

import com.thrivestorm2.voicemapper.core.analysis.Exercise
import com.thrivestorm2.voicemapper.core.analysis.RainbowPassage
import com.thrivestorm2.voicemapper.core.analysis.Stage
import org.junit.Test
import java.io.File

/**
 * The iOS app is the source of truth. These checks read the Swift sources and
 * fail when they change in a way the Kotlin port hasn't followed: exercise
 * wording, and the tuned DSP/analysis constants. When one fails, port the iOS
 * change, then update the expected text here.
 */
class ParityTest {
    private val ios = File(System.getProperty("voicemapper.iosRoot") ?: "../VoiceMapper")
    private fun swift(path: String) = File(ios, path).readText()
    /** Swift string literal form of a Kotlin string. */
    private fun literal(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    @Test
    fun exerciseWordingMatchesIos() {
        val c = Checks("Parity: exercises")
        val src = swift("Analysis/Exercises.swift")
        c.group("exercise strings appear verbatim in Exercises.swift")
        for (e in Exercise.all) {
            for (s in listOf(e.title, e.summary, e.basis.summary) + e.basis.citations) {
                c.expect(literal(s) in src) { "${e.id}: not in Swift: \"${s.take(60)}…\"" }
            }
            val kind = e.kind
            if (kind is Exercise.Kind.Guided) for (step in kind.steps) {
                c.expect("Step(text: \"${literal(step.text)}\", seconds: ${step.seconds.toInt()})" in src) {
                    "${e.id}: step not in Swift (text or seconds differ): \"${step.text.take(60)}…\""
                }
            }
            c.expect("stages: [${e.stages.sorted().joinToString(", ")}]" in src || e.stages == (1..6).toSet()) { "${e.id}: stages differ" }
        }
        val swiftSteps = Regex("Step\\(text: ").findAll(src).count()
        val kotlinSteps = Exercise.all.sumOf { (it.kind as? Exercise.Kind.Guided)?.steps?.size ?: 0 }
        c.expect(swiftSteps == kotlinSteps) { "iOS has $swiftSteps guided steps, Android $kotlinSteps (exercise added or removed?)" }
        val swiftExercises = Regex("\\n\\s+Exercise\\(\\n").findAll(src).count()
        c.expect(swiftExercises == Exercise.all.size) { "iOS has $swiftExercises exercises, Android ${Exercise.all.size}" }
        c.expect(literal(RainbowPassage.TEXT) in src, "Rainbow Passage text")
        c.finish()
    }

    @Test
    fun tunedConstantsMatchIos() {
        val c = Checks("Parity: constants")
        val expected = mapOf(
            "DSP/PitchDetector.swift" to listOf(
                "minFrequency = 60.0", "maxFrequency = 1000.0", "windowLength = 1024",
                "dipThreshold: Float = 0.15", "voicedLimit: Float = 0.4", "octaveDipLimit: Float = 0.25",
                "harmonicRatio: Float = 0.5", "harmonicGap: Float = 0.07", "harmonicStrongRatio: Float = 0.1",
                "harmonicFloor: Float = 0.03", "harmonicStrongMinHz = 300.0", "silenceRMS: Float = 0.003", "for k in 2...6",
            ),
            "DSP/PitchTracker.swift" to listOf("hop = 1024", "medianCount = 5", "dropoutFrames = 6", "suspectDrop = 4.0", "jumpFrames = 10"),
            "DSP/FormantAnalyzer.swift" to listOf(
                "targetRate = 11_025.0", "minFormant = 180.0", "maxBandwidth = 600.0", "16 * factor + 1",
                "0.9 * 0.5 / Double(factor)", "analysisRate * 0.040", "4 + Int(analysisRate / 1000)", "standardOrder - 2", "2 * .pi * 50 / analysisRate",
                "for _ in 0..<80",
            ),
            "DSP/ResonanceTracker.swift" to listOf("hop = 2048", "medianCount = 15", "followRate = 0.2", "minimumFrames = 3",
                "dropoutFrames = 8", "maxPitch = 350.0", "rhoticF3Ratio = 0.8", "f3WindowCount = 60", "f3MinimumFrames = 10",
                "blendLow = 150.0", "blendHigh = 175.0"),
            "DSP/VoiceQualityAnalyzer.swift" to listOf("silenceLevel = -55.0", "0.08 * sampleRate", "(12 - h1h2) / 16", "(alphaRatio + 28) / 20"),
            "DSP/VoiceQualityTracker.swift" to listOf("windowCount = 24", "minimumFrames = 6", "dropoutFrames = 8", "70.0...800.0"),
            "Analysis/ResonanceScorer.swift" to listOf("darkSpacing = 900.0", "brightSpacing = 1300.0", "200...1200", "800...3300",
                "1400...4300", "2600...5300", "maxLowBandwidth = 400.0", "f - last < 150"),
            "Analysis/RangeTable.swift" to listOf("case ..<145: self = .male", "case ..<175: self = .ambiguous", "case .male: 120",
                "case .ambiguous: 160", "case .female: 210", "case .male: 25", "case .ambiguous: 50", "case .female: 72"),
            "Analysis/VoicePerception.swift" to listOf("centerFrequency = 165.0", "pitchSlope = 0.8", "resonanceSlope = 0.06", "ambiguousHalfWidth = 1.1"),
            "Analysis/StrainGuard.swift" to listOf("defaultCapMinutes = 30", "healthWindow = 30.0", "healthMinimum = 10.0", "rangeWindow = 60.0",
                "rangeMinimum = 25.0", "rangeShare = 0.75", "loudWindow = 30.0", "loudMinimum = 12.0", "ref.hnr - 4", "ref.cpp - 4",
                "max(1.6 * r, r + 0.5)", "max(1.5 * r, r + 2)", "$0.level + 10", "?? -14"),
            "Analysis/Progression.swift" to listOf("safetyWindowDays = 3", "passesPerStep = 2", "passesToMoveOn = 3",
                "resonanceSecondsPerDay = 120.0", "zoneShare = 0.5", "zoneMinimumSeconds = 300.0", "daysNeeded = 3",
                "case .foundations: 5", "case .fineTuning: 0", "month >= 12"),
        )
        c.group("tuned constants still present in the Swift sources")
        for ((file, snippets) in expected) {
            val src = swift(file)
            for (s in snippets) c.expect(s in src) { "$file no longer contains `$s`: iOS changed, port it to Kotlin" }
        }
        val progression = swift("Analysis/Progression.swift")
        for (stage in Stage.entries) c.expect("\"${literal(stage.focus)}\"" in progression) { "stage ${stage.number} focus text differs" }
        c.finish()
    }
}
