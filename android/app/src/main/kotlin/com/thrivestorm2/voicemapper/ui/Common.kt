package com.thrivestorm2.voicemapper.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.thrivestorm2.voicemapper.core.analysis.PerceivedGender
import com.thrivestorm2.voicemapper.core.analysis.RegionalSpelling
import com.thrivestorm2.voicemapper.core.analysis.Rgb
import java.util.Locale

/** iOS system blue, so the accent matches the iPhone app. */
val Accent = Color(0xFF0A84FF)
val Teal = Color(0xFF30B0C7)

/** Settings ▸ Colour-blind palette, available to every drawing composable. */
val LocalColourBlind = staticCompositionLocalOf { false }

fun Rgb.toColor(alpha: Float = 1f) = Color(r.toFloat(), g.toFloat(), b.toFloat(), alpha)

@Composable
fun PerceivedGender.color(): Color = rgb(LocalColourBlind.current).toColor()

/** Heat-map fill: orange normally; with the colour-blind palette orange is a zone colour, so it switches. */
@Composable
fun heatColor(): Color = if (LocalColourBlind.current) MaterialTheme.colorScheme.onSurface else Color(0xFFFF9500)

@Composable
fun VoiceMapperTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme())
        darkColorScheme(primary = Accent, secondary = Teal)
    else
        lightColorScheme(primary = Accent, secondary = Teal)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** [text] respelled for the user's English (US, Canadian, or British/Australian/NZ); see core `RegionalSpelling`. */
fun rs(text: String): String {
    val locale = Locale.getDefault()
    return RegionalSpelling.apply(text, RegionalSpelling.variant(listOf(locale.toLanguageTag()), locale.country))
}

/** Settings ▸ Find a voice therapist: the directory for the device's region. */
fun therapistDirectory(): RegionalSpelling.Directory = RegionalSpelling.directory(Locale.getDefault().country)
