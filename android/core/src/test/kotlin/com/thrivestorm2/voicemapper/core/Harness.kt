package com.thrivestorm2.voicemapper.core

import org.junit.Assert.fail
import kotlin.math.abs

/**
 * Check counter shaped like the iOS command-line suites, so results read the
 * same ("N of M checks passed" plus one FAIL line per miss).
 */
class Checks(private val suite: String) {
    var count = 0; private set
    val failures = mutableListOf<String>()
    private var group = ""

    fun group(name: String) { group = name; println("· $name") }

    fun expect(ok: Boolean, message: () -> String) {
        count++
        if (!ok) failures += "[$group] ${message()}"
    }

    fun expect(ok: Boolean, message: String) = expect(ok) { message }

    fun near(a: Double?, b: Double, tolerance: Double, label: String) {
        if (a == null) { expect(false, "$label: null, expected $b"); return }
        expect(abs(a - b) <= tolerance) { "$label: $a, expected $b ± $tolerance" }
    }

    fun finish() {
        println("\n$suite: ${count - failures.size} of $count checks passed")
        failures.forEach { println("FAIL $it") }
        if (failures.isNotEmpty()) fail("$suite: ${failures.size} of $count checks failed:\n" + failures.joinToString("\n"))
    }
}

fun fmt(v: Double?, digits: Int = 0): String = v?.let { "%.${digits}f".format(it) } ?: "–"
