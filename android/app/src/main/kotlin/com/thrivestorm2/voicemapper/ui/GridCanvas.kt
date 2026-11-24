package com.thrivestorm2.voicemapper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrivestorm2.voicemapper.core.analysis.GoalRange
import com.thrivestorm2.voicemapper.core.analysis.HeatCell
import com.thrivestorm2.voicemapper.core.analysis.HistoryPoint
import com.thrivestorm2.voicemapper.core.analysis.Note
import com.thrivestorm2.voicemapper.core.analysis.PerceivedGender
import com.thrivestorm2.voicemapper.core.analysis.ResonanceGoal
import com.thrivestorm2.voicemapper.core.analysis.VoicePerception
import com.thrivestorm2.voicemapper.core.analysis.swiftRound
import com.thrivestorm2.voicemapper.core.dsp.PitchReading
import com.thrivestorm2.voicemapper.core.dsp.ResonanceReading
import kotlin.math.abs
import kotlin.math.pow

/**
 * Pitch (up) × resonance (right) grid. Port of iOS `GridView`: zone shading,
 * goal bands, target box, heat map, history trace, live dot with a fading trail.
 */
object GridScale {
    const val CENTER_SCORE = 50.0
    const val CENTER_MIDI = 51.0
    const val HALF_SPAN = 16.0
    const val LOW_MIDI = CENTER_MIDI - HALF_SPAN
    const val HIGH_MIDI = CENTER_MIDI + HALF_SPAN
    const val TRAIL_LIFE_MS = 1500L
    val axisNotes = listOf(36, 43, 48, 55, 60, 64)

    fun y(midi: Double, h: Float): Float = h * (1 - ((midi - LOW_MIDI) / (HIGH_MIDI - LOW_MIDI)).coerceIn(0.0, 1.0)).toFloat()
    fun x(score: Double, w: Float): Float = w * (score / 100).coerceIn(0.0, 1.0).toFloat()
}

private data class TrailPoint(val ms: Long, val score: Double, val midi: Double)

@Composable
fun PitchResonanceGrid(
    pitch: PitchReading?,
    resonance: ResonanceReading?,
    goal: GoalRange?,
    resonanceGoal: ResonanceGoal?,
    onResonanceGoal: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
    heat: List<HeatCell> = emptyList(),
    trace: List<HistoryPoint> = emptyList(),
) {
    val colourBlind = LocalColourBlind.current
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val background = MaterialTheme.colorScheme.background
    val heatFill = heatColor()
    val measurer = rememberTextMeasurer()
    var lastScore by remember { mutableStateOf<Double?>(null) }
    val trail = remember { mutableStateListOf<TrailPoint>() }
    var frame by remember { mutableLongStateOf(0L) }
    var showingInfo by remember { mutableStateOf(false) }

    // Record a trail point whenever a reading changes, as GridView.record() does.
    LaunchedEffect(pitch, resonance) {
        resonance?.let { lastScore = it.score }
        val score = resonance?.score ?: lastScore
        if (pitch != null && score != null) {
            val now = System.currentTimeMillis()
            trail += TrailPoint(now, score, Note.midiValue(pitch.frequency))
            trail.removeAll { now - it.ms > GridScale.TRAIL_LIFE_MS }
        }
    }
    // Redraw while the trail fades.
    LaunchedEffect(trail.isNotEmpty() || pitch != null) {
        while (trail.isNotEmpty() || pitch != null) withFrameMillis { frame = it }
    }

    val zoneField = remember(colourBlind) { zoneColours(colourBlind) }
    val offScale = pitch?.let { val m = Note.midiValue(it.frequency); m > GridScale.HIGH_MIDI || m < GridScale.LOW_MIDI } ?: false

    Column(modifier) {
        run {
            val labelStyle = TextStyle(fontSize = 11.sp, color = onSurface.copy(alpha = 0.6f))
            Box(Modifier.fillMaxWidth()) {
                Canvas(
                    Modifier
                        .padding(start = 34.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, onSurface.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .pointerInput(Unit) {
                            var startX = 0f
                            detectDragGestures(
                                onDragStart = { startX = it.x },
                                onDrag = { change, _ ->
                                    val w = size.width.toFloat()
                                    if (abs(change.position.x - startX) >= 8) {
                                        val a = (startX / w * 100).toDouble()
                                        val b = (change.position.x / w * 100).toDouble()
                                        onResonanceGoal(minOf(a, b).coerceIn(0.0, 100.0), maxOf(a, b).coerceIn(0.0, 100.0))
                                    }
                                },
                            )
                        }
                        .pointerInput(Unit) { detectTapGestures { onResonanceGoal(0.0, 0.0) } }
                        // TalkBack: value as state (re-read while focused, like iOS
                        // .updatesFrequently) and actions in place of the drag.
                        .semantics {
                            contentDescription = "Pitch and resonance grid"
                            stateDescription = gridState(pitch, resonance, resonanceGoal)
                            customActions = listOf(
                                CustomAccessibilityAction("Move resonance goal brighter") { moveGoal(resonanceGoal, resonance, 5.0, onResonanceGoal); true },
                                CustomAccessibilityAction("Move resonance goal darker") { moveGoal(resonanceGoal, resonance, -5.0, onResonanceGoal); true },
                                CustomAccessibilityAction("Clear resonance goal") { onResonanceGoal(0.0, 0.0); true },
                            )
                        },
                ) {
                    @Suppress("UNUSED_EXPRESSION") frame
                    val w = size.width; val h = size.height
                    drawRect(surface)
                    drawZones(zoneField)
                    drawHeat(heat, heatFill)
                    for (z in PerceivedGender.entries) drawZoneLabel(z, colourBlind, background, measurer)
                    goal?.let { drawGoalBand(it, onSurface, measurer) }
                    resonanceGoal?.let { drawResonanceBand(it, onSurface, measurer) }
                    if (goal != null && resonanceGoal != null) drawTargetBox(goal, resonanceGoal, onSurface, background)
                    for (m in GridScale.axisNotes) {
                        val y = GridScale.y(m.toDouble(), h)
                        drawLine(onSurface.copy(alpha = 0.08f), Offset(0f, y), Offset(w, y), 1f)
                    }
                    drawLine(onSurface.copy(alpha = 0.35f), Offset(w / 2, 0f), Offset(w / 2, h), 3f)
                    drawLine(onSurface.copy(alpha = 0.35f), Offset(0f, h / 2), Offset(w, h / 2), 3f)
                    drawTrace(trace, onSurface, background)
                    drawTrailAndDot(trail, pitch, resonance, lastScore, offScale, background)
                    if (pitch != null && offScale) drawOffScale(pitch, resonance?.score ?: lastScore ?: GridScale.CENTER_SCORE, measurer, background)
                }
                // Axis labels.
                Canvas(Modifier.matchParentSize()) {
                    for (m in GridScale.axisNotes) {
                        val y = GridScale.y(m.toDouble(), size.height)
                        val t = measurer.measure(Note(m).name, labelStyle)
                        drawText(t, topLeft = Offset(0f, y - t.size.height / 2))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 34.dp), verticalAlignment = Alignment.CenterVertically) {
            val caption = MaterialTheme.typography.labelSmall
            Text("Darker", style = caption, color = onSurface.copy(alpha = 0.6f))
            Text(hint(pitch, resonance, resonanceGoal), style = caption, color = onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text("Brighter", style = caption, color = onSurface.copy(alpha = 0.6f))
            IconButton(onClick = { showingInfo = true }) { Icon(Icons.Outlined.Info, contentDescription = "About the grid") }
        }
        Text(
            if (resonanceGoal == null) "To set a resonance goal, drag sideways across the grid."
            else "Tap the grid to clear your resonance goal.",
            style = MaterialTheme.typography.labelSmall, color = onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth(),
        )
    }
    if (showingInfo) {
        AlertDialog(
            onDismissRequest = { showingInfo = false },
            confirmButton = { TextButton(onClick = { showingInfo = false }) { Text("OK") } },
            title = { Text("About the grid") },
            text = { Text(rs(GRID_INFO)) },
        )
    }
}

const val GRID_INFO = "Up is higher pitch, right is brighter resonance (formant spacing).\n\nShaded areas show where voices are typically heard as male (M), ambiguous (A) or female (F). The colours blend because there are no hard edges: they're typical ranges, not rules, and intonation, vowels and speech habits matter too.\n\nSet a pitch goal by tapping notes on the Range tab. Set a resonance goal by dragging sideways across the grid; tap the grid to clear it.\n\nVowels change resonance a lot (“ee” reads brighter than “oo”), so compare on the same vowel. There's no correct place on this grid. Work toward what feels like your voice." // regional: shown via rs()

private fun hint(pitch: PitchReading?, resonance: ResonanceReading?, goal: ResonanceGoal?): String {
    if (pitch == null) return " "
    if (resonance == null) return "Hold a vowel like “ee” or “ah”"
    goal ?: return " "
    return when (goal.direction(resonance.score)) {
        null -> "In your resonance goal"
        ResonanceGoal.Direction.BRIGHTER -> "Goal is brighter"
        ResonanceGoal.Direction.DARKER -> "Goal is darker"
    }
}

/** iOS GridView.accessibilityValue: the voice against the resonance goal. */
private fun gridState(pitch: PitchReading?, resonance: ResonanceReading?, goal: ResonanceGoal?): String {
    val goalText = goal?.let { "Resonance goal ${swiftRound(it.low)} to ${swiftRound(it.high)}" }
    if (pitch == null) return listOfNotNull("No reading", goalText).joinToString(". ")
    var text = pitch.note.name
    resonance?.let { r ->
        text += ", resonance ${swiftRound(r.score)} out of 100"
        if (goal != null) text += when (goal.direction(r.score)) {
            ResonanceGoal.Direction.BRIGHTER -> ", goal is brighter"
            ResonanceGoal.Direction.DARKER -> ", goal is darker"
            null -> ", in your resonance goal"
        }
    }
    if (goalText != null) text += ". $goalText"
    return text
}

/** iOS accessibilityAdjustableAction: shift the goal band (20 wide if none yet). */
private fun moveGoal(goal: ResonanceGoal?, resonance: ResonanceReading?, step: Double, set: (Double, Double) -> Unit) {
    val width = goal?.let { it.high - it.low } ?: 20.0
    val centre = ((goal?.let { (it.low + it.high) / 2 } ?: resonance?.score ?: 50.0) + step)
        .coerceIn(width / 2, 100 - width / 2)
    set(centre - width / 2, centre + width / 2)
}

private const val ZONE_CELLS = 64

/** Precomputed 64×64 zone colours (blend + commonness alpha), as ZoneField does. */
fun zoneColours(colourBlind: Boolean): Array<Color> = Array(ZONE_CELLS * ZONE_CELLS) { i ->
    val row = i / ZONE_CELLS; val col = i % ZONE_CELLS
    val midi = GridScale.HIGH_MIDI - (row + 0.5) / ZONE_CELLS * (GridScale.HIGH_MIDI - GridScale.LOW_MIDI)
    val hz = 440 * 2.0.pow((midi - 69) / 12)
    val score = (col + 0.5) / ZONE_CELLS * 100
    val blend = VoicePerception.blend(hz, score)
    val alpha = 0.1 + 0.55 * VoicePerception.commonness(hz, score, blend)
    VoicePerception.color(blend, colourBlind).toColor(alpha.toFloat())
}

fun DrawScope.drawZones(colours: Array<Color>) {
    val cw = size.width / ZONE_CELLS; val ch = size.height / ZONE_CELLS
    for (row in 0 until ZONE_CELLS) for (col in 0 until ZONE_CELLS) {
        val x0 = Math.round(col * cw).toFloat(); val x1 = Math.round((col + 1) * cw).toFloat()
        val y0 = Math.round(row * ch).toFloat(); val y1 = Math.round((row + 1) * ch).toFloat()
        drawRect(colours[row * ZONE_CELLS + col], Offset(x0, y0), Size(x1 - x0, y1 - y0))
    }
}

/** Heat map, square-root scale so brief visits stay visible (HeatmapLayer on iOS). */
fun DrawScope.drawHeat(cells: List<HeatCell>, fill: Color) {
    val max = cells.maxOfOrNull { it.seconds } ?: return
    if (max <= 0) return
    for (c in cells) {
        val left = GridScale.x(c.bin * 5.0, size.width); val right = GridScale.x(c.bin * 5.0 + 5, size.width)
        val top = GridScale.y(c.midi + 0.5, size.height); val bottom = GridScale.y(c.midi - 0.5, size.height)
        if (bottom <= 0f || top >= size.height) continue
        val a = (0.2 + 0.7 * kotlin.math.sqrt(c.seconds / max)).toFloat()
        drawRect(fill.copy(alpha = a), Offset(left, top), Size(right - left, bottom - top))
    }
}

private fun DrawScope.drawZoneLabel(z: PerceivedGender, colourBlind: Boolean, bg: Color, measurer: TextMeasurer) {
    val c = Offset(GridScale.x(z.typicalResonance, size.width), GridScale.y(Note.midiValue(z.typicalFrequency), size.height))
    val color = z.rgb(colourBlind).toColor()
    val r = 11.dp.toPx()
    drawCircle(bg.copy(alpha = 0.85f), r, c)
    drawCircle(color, r, c, style = Stroke(1.5.dp.toPx()))
    val t = measurer.measure(z.letter, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color))
    drawText(t, topLeft = c - Offset(t.size.width / 2f, t.size.height / 2f))
}

private val dash = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))

private fun DrawScope.drawGoalBand(goal: GoalRange, ink: Color, measurer: TextMeasurer) {
    val top = GridScale.y(goal.high + 0.5, size.height); val bottom = GridScale.y(goal.low - 0.5, size.height)
    drawRect(ink.copy(alpha = 0.6f), Offset(0f, top), Size(size.width, maxOf(2f, bottom - top)), style = Stroke(1.5.dp.toPx(), pathEffect = dash))
    val t = measurer.measure("Pitch goal", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = 0.6f)))
    drawText(t, topLeft = Offset(size.width - t.size.width - 4.dp.toPx(), top + 2.dp.toPx()))
}

private fun DrawScope.drawResonanceBand(goal: ResonanceGoal, ink: Color, measurer: TextMeasurer) {
    val left = GridScale.x(goal.low, size.width); val right = GridScale.x(goal.high, size.width)
    drawRect(ink.copy(alpha = 0.6f), Offset(left, 0f), Size(right - left, size.height), style = Stroke(1.5.dp.toPx(), pathEffect = dash))
    val t = measurer.measure("Resonance goal", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = 0.6f)))
    drawText(t, topLeft = Offset(left + 3.dp.toPx(), size.height - t.size.height - 3.dp.toPx()))
}

private fun DrawScope.drawTargetBox(goal: GoalRange, res: ResonanceGoal, ink: Color, bg: Color) {
    val left = GridScale.x(res.low, size.width); val right = GridScale.x(res.high, size.width)
    val top = GridScale.y(goal.high + 0.5, size.height); val bottom = GridScale.y(goal.low - 0.5, size.height)
    val s = Size(right - left, maxOf(2f, bottom - top))
    drawRect(bg.copy(alpha = 0.9f), Offset(left, top), s, style = Stroke(6.dp.toPx()))
    drawRect(ink, Offset(left, top), s, style = Stroke(2.5.dp.toPx()))
}

/** Session averages joined in time order; the newest drawn larger (HistoryTraceLayer). */
private fun DrawScope.drawTrace(points: List<HistoryPoint>, ink: Color, bg: Color) {
    if (points.isEmpty()) return
    val pts = points.map { Offset(GridScale.x(it.score, size.width), GridScale.y(it.midi, size.height)) }
    for (i in 1 until pts.size) drawLine(ink.copy(alpha = 0.7f), pts[i - 1], pts[i], 2.dp.toPx(), cap = StrokeCap.Round)
    pts.forEachIndexed { i, p ->
        val r = if (i == pts.lastIndex) 6.dp.toPx() else 4.dp.toPx()
        drawCircle(bg, r, p); drawCircle(ink, r, p, style = Stroke(2.dp.toPx()))
    }
}

private fun DrawScope.drawTrailAndDot(trail: List<TrailPoint>, pitch: PitchReading?, resonance: ResonanceReading?,
                                      lastScore: Double?, offScale: Boolean, bg: Color) {
    val now = System.currentTimeMillis()
    val live = trail.filter { now - it.ms < GridScale.TRAIL_LIFE_MS }
    for (i in 1 until live.size) {
        val age = (now - live[i].ms).toFloat() / GridScale.TRAIL_LIFE_MS
        drawLine(Accent.copy(alpha = 0.6f * (1 - age)),
            Offset(GridScale.x(live[i - 1].score, size.width), GridScale.y(live[i - 1].midi, size.height)),
            Offset(GridScale.x(live[i].score, size.width), GridScale.y(live[i].midi, size.height)),
            4.dp.toPx(), cap = StrokeCap.Round)
    }
    val score = resonance?.score ?: lastScore
    if (pitch != null && !offScale && score != null) {
        val c = Offset(GridScale.x(score, size.width), GridScale.y(Note.midiValue(pitch.frequency), size.height))
        val r = 9.dp.toPx()
        if (resonance != null) {
            drawCircle(Accent, r, c); drawCircle(Color.White, r, c, style = Stroke(2.dp.toPx()))
        } else {
            // Hollow while resonance can't be tracked (e.g. above 350 Hz).
            drawCircle(bg, r, c); drawCircle(Accent, r, c, style = Stroke(3.dp.toPx()))
        }
    }
}

private fun DrawScope.drawOffScale(pitch: PitchReading, score: Double, measurer: TextMeasurer, bg: Color) {
    val above = Note.midiValue(pitch.frequency) > GridScale.HIGH_MIDI
    val t = measurer.measure((if (above) "↑ " else "↓ ") + pitch.note.name, TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
    val px = GridScale.x(score, size.width).coerceIn(40.dp.toPx(), size.width - 40.dp.toPx())
    val py = if (above) 18.dp.toPx() else size.height - 18.dp.toPx()
    val pad = Offset(10.dp.toPx(), 4.dp.toPx())
    val topLeft = Offset(px - t.size.width / 2f, py - t.size.height / 2f) - pad
    val s = Size(t.size.width + pad.x * 2, t.size.height + pad.y * 2)
    drawRoundRect(bg.copy(alpha = 0.9f), topLeft, s, CornerRadius(s.height / 2))
    drawRoundRect(Accent, topLeft, s, CornerRadius(s.height / 2), style = Stroke(2.dp.toPx()))
    drawText(t, topLeft = topLeft + pad)
}

@Suppress("unused")
private fun Modifier.gridBackground(c: Color) = background(c, RoundedCornerShape(10.dp))
