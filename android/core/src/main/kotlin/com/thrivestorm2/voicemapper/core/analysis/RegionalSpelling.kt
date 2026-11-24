package com.thrivestorm2.voicemapper.core.analysis

import java.util.concurrent.ConcurrentHashMap

/**
 * Regional English for the five launch markets (US, Canada, UK, Australia,
 * New Zealand). Port of iOS `Analysis/RegionalSpelling.swift`; keep the two in
 * step. Copy is written once in British spelling; US and Canadian users get a
 * word-for-word respelling at display time, and every market gets its own name
 * for the voice professional and its own directory link.
 */
object RegionalSpelling {
    enum class Variant { BRITISH, AUSTRALIAN, NEW_ZEALAND, CANADIAN, AMERICAN }

    /** From the first preferred language tag (e.g. "en-US"), falling back to the device region. */
    fun variant(preferredLanguages: List<String>, region: String?): Variant {
        val tagRegion = preferredLanguages.firstOrNull()
            ?.split('-', '_')?.drop(1)?.firstOrNull { it.length == 2 && it.all(Char::isLetter) }
            ?.uppercase()
        return when (tagRegion ?: region?.uppercase()) {
            "US", "PR", "UM", "VI", "AS", "GU", "MP" -> Variant.AMERICAN
            "CA" -> Variant.CANADIAN
            "AU" -> Variant.AUSTRALIAN
            "NZ" -> Variant.NEW_ZEALAND
            else -> Variant.BRITISH
        }
    }

    /** What each market calls the professional who does voice therapy. */
    fun professional(variant: Variant): String = when (variant) {
        Variant.AMERICAN, Variant.CANADIAN -> "speech-language pathologist"
        Variant.BRITISH -> "speech and language therapist"
        Variant.AUSTRALIAN -> "speech pathologist"
        Variant.NEW_ZEALAND -> "speech-language therapist"
    }

    data class Directory(val name: String, val url: String)

    /** The national professional body's public directory, by the device's region. */
    fun directory(region: String?): Directory = when (region?.uppercase()) {
        "CA" -> Directory("Speech-Language & Audiology Canada", "https://www.sac-oac.ca/find-speech-language-pathologist-or-audiologist-service")
        "GB", "IM", "JE", "GG" -> Directory("ASLTIP (independent speech and language therapists)", "https://asltip.com/find-a-speech-therapist/")
        "AU" -> Directory("Speech Pathology Australia", "https://speechpathologyaustralia.org.au/find-a-speech-pathologist")
        "NZ" -> Directory("New Zealand Speech-language Therapists' Association", "https://speechtherapy.org.nz/find-a-therapist/")
        else -> Directory("ASHA ProFind", "https://find.asha.org/pro/")
    }

    private val canadianPairs = listOf(
        "analyse" to "analyze", "analysed" to "analyzed",
        "apologise" to "apologize",
        "emphasise" to "emphasize",
        "minimise" to "minimize",
        "mum" to "mom", "mums" to "moms",
        "organise" to "organize", "organised" to "organized",
        "personalise" to "personalize", "personalised" to "personalized",
        "realise" to "realize", "realised" to "realized",
        "recognise" to "recognize", "recognised" to "recognized",
        "summarise" to "summarize",
    )

    private val americanPairs = canadianPairs + listOf(
        "behaviour" to "behavior",
        "centre" to "center", "centred" to "centered", "centres" to "centers",
        "colour" to "color", "colours" to "colors", "coloured" to "colored",
        "favourite" to "favorite",
        "grey" to "gray",
        "metre" to "meter", "metres" to "meters",
        "practise" to "practice", "practised" to "practiced", "practising" to "practicing",
        "programme" to "program",
    )

    private fun expanded(pairs: List<Pair<String, String>>): List<Pair<Regex, String>> =
        pairs.flatMap { (from, to) -> listOf(from to to, from.replaceFirstChar(Char::uppercase) to to.replaceFirstChar(Char::uppercase)) }
            .map { (from, to) -> Regex("\\b$from\\b") to to }

    private val canadianRules = expanded(canadianPairs)
    private val americanRules = expanded(americanPairs)
    private val cache = ConcurrentHashMap<String, String>()

    /** [text] respelled for [variant]. */
    fun apply(text: String, variant: Variant): String {
        val rules = when (variant) {
            Variant.BRITISH, Variant.AUSTRALIAN, Variant.NEW_ZEALAND -> emptyList()
            Variant.CANADIAN -> canadianRules
            Variant.AMERICAN -> americanRules
        }
        return cache.getOrPut("${variant.name}|$text") {
            var out = text
            val pro = professional(variant)
            if (pro != "speech-language pathologist") {
                out = out.replace("speech-language pathologist", pro)
                    .replace("Speech-language pathologist", pro.replaceFirstChar(Char::uppercase))
            }
            for ((regex, to) in rules) out = regex.replace(out, to)
            out
        }
    }
}
