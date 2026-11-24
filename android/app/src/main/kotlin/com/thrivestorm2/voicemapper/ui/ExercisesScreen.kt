package com.thrivestorm2.voicemapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thrivestorm2.voicemapper.VoiceModel
import com.thrivestorm2.voicemapper.core.analysis.Exercise
import com.thrivestorm2.voicemapper.core.analysis.ExerciseLog
import com.thrivestorm2.voicemapper.core.analysis.Progression
import com.thrivestorm2.voicemapper.core.analysis.Stage
import java.time.LocalDate

/** Material stand-ins for the iOS SF Symbols. */
fun exerciseIcon(symbol: String): ImageVector = when (symbol) {
    "sun.horizon" -> Icons.Filled.WbTwilight
    "waveform" -> Icons.Filled.GraphicEq
    "arrow.up.right" -> Icons.AutoMirrored.Filled.TrendingUp
    "scope" -> Icons.Filled.GpsFixed
    "mouth" -> Icons.Filled.RecordVoiceOver
    "textformat.abc" -> Icons.Filled.SortByAlpha
    "text.bubble" -> Icons.Filled.ChatBubbleOutline
    "book" -> Icons.AutoMirrored.Filled.MenuBook
    "point.topleft.down.to.point.bottomright.curvepath" -> Icons.Filled.Timeline
    "scalemass" -> Icons.Filled.Scale
    "moon.stars" -> Icons.Filled.Nightlight
    else -> Icons.Filled.GraphicEq
}

val Green = Color(0xFF34C759)

/** Port of iOS `ExercisesView`. */
@Composable
fun ExercisesScreen(model: VoiceModel) {
    val prefs = model.prefs
    val done = ExerciseLog.done(prefs.doneToday, LocalDate.now())
    val todayMinutes = (model.todaySeconds / 60).toInt()
    val stage = model.stage
    val status = Progression.status(model.progressionInput())
    val plan = Progression.plan(stage, prefs.capMinutes - todayMinutes)
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val open: (Exercise) -> Unit = { model.runningExercise = it }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card { Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) {}) {
                Row { Text("Today", Modifier.weight(1f)); Text("$todayMinutes / ${prefs.capMinutes} min") }
                LinearProgressIndicator(progress = { minOf(todayMinutes.toFloat() / maxOf(prefs.capMinutes, 1), 1f) },
                    Modifier.fillMaxWidth().padding(top = 6.dp))
            } }
        }
        item {
            Card { StagePathHeader(stage, status) { stage.next?.let { model.chooseStage(it) } } }
            Footer(rs("Stages are suggestions. You can move to any stage in Settings ▸ Goal. Voice guidelines recommend seeing a speech-language pathologist at least once before changing your voice on your own."))
        }
        item { SectionHeader("Today's plan") }
        if (plan.items.isEmpty()) {
            item { Text("You've reached today's practice cap. Rest is part of training. Come back tomorrow.", color = secondary) }
        } else {
            item {
                Card { Column { plan.items.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider()
                    ExerciseRow(item.exercise, item.exercise.id in done, Exercise.WARM_UP_ID !in done, item.role) { open(item.exercise) }
                } } }
                if (plan.trimmed) Footer("Shortened to fit what's left of your daily cap.")
            }
        }
        item { SectionHeader("All exercises") }
        item {
            Card { Column { Exercise.all.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider()
                ExerciseRow(e, e.id in done, Exercise.WARM_UP_ID !in done, null) { open(e) }
            } } }
            Footer("Stop if anything hurts or feels scratchy. Strain alerts and your daily cap stay on during exercises.")
        }
    }
}

@Composable
fun SectionHeader(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 4.dp))

@Composable
fun Footer(text: String) = Text(text, style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))

@Composable
private fun ExerciseRow(exercise: Exercise, isDone: Boolean, needsWarmUp: Boolean, role: Progression.PlanItem.Role?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)
        .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(exerciseIcon(exercise.symbol), null, tint = Accent, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(exercise.title + if (exercise.isWarmUp) " (required)" else "",
                fontWeight = if (exercise.isWarmUp && needsWarmUp) FontWeight.SemiBold else FontWeight.Normal)
            Text(rs(if (role == Progression.PlanItem.Role.REVIEW) "Review · ${exercise.summary}" else exercise.summary),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isDone) Icon(Icons.Filled.CheckCircle, "Done today", tint = Green)
        else Text("${exercise.minutes} min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StagePathHeader(stage: Stage, status: Progression.Status, moveOn: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().semantics { contentDescription = "Stage ${stage.number} of ${Stage.entries.size}" },
            verticalAlignment = Alignment.CenterVertically) {
            Stage.entries.forEach { s ->
                if (s != Stage.FOUNDATIONS) Box(Modifier.weight(1f).height(2.dp).background(if (s.number <= stage.number) Accent else muted.copy(alpha = 0.3f)))
                Box(Modifier.size(28.dp).background(
                    when { s == stage -> Accent; s.number < stage.number -> Accent.copy(alpha = 0.35f); else -> muted.copy(alpha = 0.15f) }, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text("${s.number}", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (s == stage) Color.White else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Column {
            Text("Stage ${stage.number}: ${stage.title}", style = MaterialTheme.typography.titleMedium)
            Text(stage.focus, style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (stage.next == null) "Keeping it steady" else "Ready to move on when…",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = muted)
            status.criteria.forEach { c ->
                Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "${c.text}: ${if (c.met) "Done" else "Not yet"}" },
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (c.met) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null, tint = if (c.met) Green else muted,
                        modifier = Modifier.size(20.dp))
                    Text(rs(c.text), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        val next = stage.next
        if (status.ready && next != null) Button(onClick = moveOn) { Text("Move on to stage ${next.number}: ${next.title}") }
    }
}
