package com.thrivestorm2.voicemapper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrivestorm2.voicemapper.VoiceModel
import com.thrivestorm2.voicemapper.core.analysis.HeatmapBinner
import com.thrivestorm2.voicemapper.core.analysis.HistoryPeriod
import com.thrivestorm2.voicemapper.core.analysis.Note
import com.thrivestorm2.voicemapper.core.analysis.PracticeSession
import com.thrivestorm2.voicemapper.core.analysis.Progression
import com.thrivestorm2.voicemapper.core.analysis.VoicePerception
import com.thrivestorm2.voicemapper.core.analysis.swiftRound
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Port of iOS `HistoryView`. */
@Composable
fun HistoryScreen(model: VoiceModel) {
    val prefs = model.prefs
    val period = HistoryPeriod.entries.firstOrNull { it.name == prefs.historyPeriod } ?: HistoryPeriod.WEEK
    val all = model.store.data.sessions.sortedByDescending { it.start }
    val sessions = HeatmapBinner.sessions(all, period)
    val markers = run {
        val first = sessions.minOfOrNull { it.start }; val last = sessions.maxOfOrNull { it.start }
        if (first == null || last == null) emptyList()
        else Progression.stageChanges(model.store.data.results).filter { it.date >= first && it.date <= last }
    }
    var confirmDelete by remember { mutableStateOf<PracticeSession?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                HistoryPeriod.entries.forEachIndexed { i, p ->
                    SegmentedButton(p == period, { prefs.historyPeriod = p.name }, SegmentedButtonDefaults.itemShape(i, HistoryPeriod.entries.size)) { Text(p.label) }
                }
            }
        }
        if (sessions.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ShowChart, null, Modifier.size(48.dp), tint = muted)
                    Text("No sessions yet", style = MaterialTheme.typography.titleMedium)
                    Text(if (all.isEmpty()) rs("Practise on the Live tab and your sessions will appear here.")
                        else "Nothing recorded in this period. Try a longer one.", color = muted, textAlign = TextAlign.Center)
                }
            }
            return@LazyColumn
        }
        item { HeatMapCard(sessions) }
        item { LineChartCard("Median pitch (Hz)", sessions, markers) { it.medianHz } }
        item { LineChartCard("Resonance score", sessions.filter { it.medianResonance != null }, markers) { it.medianResonance ?: 0.0 } }
        item {
            LineChartCard("Vocal weight", sessions.filter { it.quality != null }, emptyList(), fixed = 0.0..1.0,
                axis = listOf(0.0 to "lighter", 1.0 to "heavier")) { it.quality?.weight ?: 0.0 }
            Text("Neither end is better: lighter or heavier is your choice.", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        item { Text("Sessions", style = MaterialTheme.typography.titleMedium) }
        items(sessions, key = { it.id }) { s -> SessionRow(s) { confirmDelete = s } }
        item {
            Text(rs("The colour stripe shows how the whole session typically reads, using every pitch and resonance moment: blue masculine, purple androgynous, pink feminine, with shades in between. It's a typical-listener estimate, not a verdict. Columns: date, length, median note, resonance score. Tap the bin to delete a session. The speaker icon marks a noisy session."),
                style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
    confirmDelete?.let { s ->
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this session?") },
            confirmButton = { TextButton(onClick = { model.deleteSession(s); confirmDelete = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
    }
}

@Composable
private fun HeatMapCard(sessions: List<PracticeSession>) {
    val colourBlind = LocalColourBlind.current
    val zones = remember(colourBlind) { zoneColours(colourBlind) }
    val fill = heatColor()
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val cells = HeatmapBinner.cells(sessions)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Time spent", style = MaterialTheme.typography.titleMedium)
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
            .border(1.dp, muted.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
            .semantics { contentDescription = "Heat map of time spent by pitch and resonance" }) {
            drawRect(bg)
            drawZones(zones.map { it.copy(alpha = it.alpha * 0.5f) }.toTypedArray())
            drawHeat(cells, fill)
        }
        Row { Text("Darker", style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.weight(1f))
            Text("Brighter", style = MaterialTheme.typography.labelSmall, color = muted) }
        Text(if (colourBlind) "Shading shows where you spent voiced time; stronger means longer. Up is higher pitch."
            else "Orange shows where you spent voiced time; stronger means longer. Up is higher pitch.",
            style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

/** Small line chart (Swift Charts LineMark + PointMark + dashed stage RuleMarks). */
@Composable
private fun LineChartCard(title: String, sessions: List<PracticeSession>, markers: List<Progression.StageChange>,
                          fixed: ClosedFloatingPointRange<Double>? = null, axis: List<Pair<Double, String>>? = null,
                          value: (PracticeSession) -> Double) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (sessions.size < 2) {
            Text("Needs at least two sessions.", style = MaterialTheme.typography.bodySmall, color = muted)
            return@Column
        }
        val sorted = sessions.sortedBy { it.start }
        val values = sorted.map(value)
        val lo = fixed?.start ?: values.min(); val hi = fixed?.endInclusive ?: values.max()
        val pad = if (fixed == null) maxOf((hi - lo) * 0.1, 1.0) else 0.0
        val yMin = lo - pad; val yMax = hi + pad
        val t0 = sorted.first().start.toEpochMilli().toDouble(); val t1 = sorted.last().start.toEpochMilli().toDouble()
        val labels = axis ?: listOf(yMin to fmtAxis(yMin), (yMin + yMax) / 2 to fmtAxis((yMin + yMax) / 2), yMax to fmtAxis(yMax))
        val labelStyle = TextStyle(fontSize = 10.sp, color = muted)
        Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = "$title chart, ${sorted.size} sessions" }) {
            val left = 52.dp.toPx(); val top = 14.dp.toPx(); val w = size.width - left; val h = size.height - top
            fun x(t: Double) = left + (if (t1 > t0) ((t - t0) / (t1 - t0)).toFloat() else 0.5f) * w
            fun y(v: Double) = top + h * (1 - ((v - yMin) / (yMax - yMin)).toFloat())
            for ((v, text) in labels) {
                drawLine(muted.copy(alpha = 0.2f), Offset(left, y(v)), Offset(size.width, y(v)), 1f)
                val t = measurer.measure(text, labelStyle)
                drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), y(v) - t.size.height / 2))
            }
            for (m in markers) {
                val mx = x(m.date.toEpochMilli().toDouble())
                drawLine(muted.copy(alpha = 0.6f), Offset(mx, top), Offset(mx, size.height), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 9f)))
                drawText(measurer.measure("Stage ${m.stage.number}", labelStyle), topLeft = Offset(mx + 2.dp.toPx(), 0f))
            }
            val pts = sorted.zip(values).map { (s, v) -> Offset(x(s.start.toEpochMilli().toDouble()), y(v)) }
            for (i in 1 until pts.size) drawLine(Accent, pts[i - 1], pts[i], 2.dp.toPx())
            pts.forEach { drawCircle(Accent, 3.5.dp.toPx(), it) }
        }
        if (markers.isNotEmpty()) Text("Dashed lines mark when you moved to a new stage.", style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

private fun fmtAxis(v: Double) = if (v >= 100) "${swiftRound(v)}" else "%.0f".format(v)

private val rowDate = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())

@Composable
private fun SessionRow(s: PracticeSession, onDelete: () -> Unit) {
    val colourBlind = LocalColourBlind.current
    val blend = VoicePerception.blend(s.cells)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp, 28.dp).background(blend?.let { VoicePerception.color(it, colourBlind).toColor() }
            ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(3.dp)))
        Text(rowDate.format(s.start.atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.bodyMedium)
        if ((s.noisyShare ?: 0.0) >= VoiceModel.NOISY_PROMPT_SHARE) Icon(Icons.AutoMirrored.Filled.VolumeUp, "Noisy session", Modifier.size(14.dp), tint = Color(0xFFFF9500))
        Box(Modifier.weight(1f))
        Text("${maxOf(1, swiftRound(s.duration / 60))} min", style = MaterialTheme.typography.bodyMedium)
        Text(Note.fromFrequency(s.medianHz).name, Modifier.width(44.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
        Text(s.medianResonance?.let { "${swiftRound(it)}" } ?: "–", Modifier.width(30.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete session", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
