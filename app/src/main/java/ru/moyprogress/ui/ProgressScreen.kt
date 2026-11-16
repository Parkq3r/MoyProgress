package ru.moyprogress.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import ru.moyprogress.domain.*

@Composable
internal fun ProgressScreen(data: TrainingData, today: Long) {
    var chosenId by rememberSaveable { mutableStateOf("pull") }
    var showVolume by rememberSaveable { mutableStateOf(false) }
    var historyId by rememberSaveable { mutableStateOf<String?>(null) }
    var shownSessions by rememberSaveable { mutableIntStateOf(10) }
    val exercise = data.exercises.firstOrNull { it.id == chosenId } ?: data.exercises.firstOrNull()
    val points = remember(data.sessions, exercise) { exercise?.let { results(data.sessions, it.id) }.orEmpty() }
    val personal = remember(data) { records(data) }
    val history = remember(data.sessions) { data.sessions.filter { it.finishedAt != null }
        .sortedByDescending { it.finishedAt } }
    val monday = today - LocalDate.ofEpochDay(today).dayOfWeek.value + 1
    Screen {
        Heading("Прогресс")
        Choice("Упражнение", data.exercises, exercise, { it.name }) { chosenId = it.id }
        if (exercise != null) {
            val maximum = points.maxOfOrNull { it.best }
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat(if (exercise.timed) "Лучшее удержание" else "Лучший подход",
                        maximum?.let { "$it ${exercise.unit}" } ?: "—", Modifier.weight(1f))
                    Stat("Общий объём", "${points.sumOf { it.volume }} ${exercise.unit}", Modifier.weight(1f))
                }
                exercise.baseline?.let { Text("Стартовый результат · $it ${exercise.unit}") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showVolume, onClick = { showVolume = false },
                        label = { Text("Лучший подход") })
                    FilterChip(selected = showVolume, onClick = { showVolume = true },
                        label = { Text("Объём") })
                }
                if (points.isEmpty()) Text("График появится после первого выполненного подхода.")
                else Trend(points.takeLast(30), exercise.unit, showVolume)
                if (points.isNotEmpty()) {
                    Text("Последний результат · ${dateLabel(points.last().day)} · " +
                        "${if (showVolume) points.last().volume else points.last().best} ${exercise.unit}")
                }
            }
            Panel {
                Text("Объём по неделям", style = MaterialTheme.typography.titleLarge)
                val recent = points.filter { it.day in monday..today }.sumOf { it.volume }
                val previous = points.filter { it.day in (monday - 7) until monday }.sumOf { it.volume }
                Text("Текущая · $recent ${exercise.unit}")
                Text("Предыдущая · $previous ${exercise.unit}")
                if (previous > 0) {
                    val diff = recent - previous
                    Text("Разница · ${if (diff > 0) "+" else ""}$diff ${exercise.unit}")
                }
            }
        }
        Section("Личные рекорды")
        if (personal.isEmpty()) Panel { Text("Новых рекордов пока нет.") }
        personal.forEach { record ->
            Panel {
                Text(record.exercise.name, style = MaterialTheme.typography.titleMedium)
                Text("${record.value} ${record.exercise.unit} · ${dateLabel(record.day)}")
            }
        }
        Section("История")
        if (history.isEmpty()) Panel { Text("Завершённых тренировок пока нет.") }
        history.take(shownSessions).forEach { session ->
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(session.label, style = MaterialTheme.typography.titleMedium)
                        Text("${dateLabel(session.day)} · Неделя ${session.week}")
                    }
                    TextButton(onClick = { historyId = if (historyId == session.id) null else session.id }) {
                        Text(if (historyId == session.id) "Свернуть" else "Открыть")
                    }
                }
                if (historyId == session.id) session.logs.forEach { log ->
                    val sets = log.sets.joinToString(" · ") {
                        when {
                            !it.done -> "—"
                            log.exercise.sided -> "${it.value}/${it.second}"
                            else -> it.value.toString()
                        }
                    }
                    Text("${log.exercise.name} · $sets ${log.exercise.unit}" +
                        (log.effort?.let { " · RPE $it" } ?: ""))
                }
            }
        }
        if (history.size > shownSessions) OutlinedButton(onClick = { shownSessions += 10 }) {
            Text("Показать ещё")
        }
    }
}

@Composable
private fun Stat(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun Trend(points: List<ResultPoint>, unit: String, volume: Boolean) {
    val values = points.map { if (volume) it.volume else it.best }
    val maximum = values.max().coerceAtLeast(1)
    val firstDay = points.first().day
    val lastDay = points.last().day
    val dayRange = (lastDay - firstDay).coerceAtLeast(1)
    val line = MaterialTheme.colorScheme.primary
    val rule = MaterialTheme.colorScheme.outlineVariant
    val description = points.joinToString(separator = ". ") { point ->
        "${dateLabel(point.day)}, ${if (volume) point.volume else point.best} $unit"
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.height(140.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(maximum.toString(), style = MaterialTheme.typography.labelSmall)
            Text("0", style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.weight(1f).height(140.dp).semantics { contentDescription = description }) {
            val inset = 6.dp.toPx()
            val width = (size.width - 2 * inset).coerceAtLeast(0f)
            val height = (size.height - 2 * inset).coerceAtLeast(0f)
            for (i in 0..4) {
                val y = inset + height * i / 4
                drawLine(rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            val coords = points.mapIndexed { index, point ->
                val fraction = if (points.size == 1) .5f else (point.day - firstDay).toFloat() / dayRange
                Offset(inset + fraction * width, inset + height * (1f - values[index].toFloat() / maximum))
            }
            coords.zipWithNext().forEach { (from, to) ->
                drawLine(line, from, to, 3.dp.toPx(), cap = StrokeCap.Round)
            }
            coords.forEach { drawCircle(line, radius = 4.dp.toPx(), center = it) }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(dateLabel(firstDay), style = MaterialTheme.typography.labelSmall)
        if (firstDay != lastDay) Text(dateLabel(lastDay), style = MaterialTheme.typography.labelSmall)
    }
}
