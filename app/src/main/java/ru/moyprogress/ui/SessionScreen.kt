package ru.moyprogress.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay
import ru.moyprogress.TrainingViewModel
import ru.moyprogress.domain.*

@Composable
internal fun SessionScreen(session: Session, model: TrainingViewModel) {
    val total = session.logs.sumOf { it.sets.size }
    val completed = session.logs.sumOf { log -> log.sets.count { it.done } }
    val startingExercise = session.logs.firstOrNull { log -> log.sets.any { !it.done } }?.exercise?.id
    var focused by rememberSaveable(session.id) { mutableStateOf(startingExercise) }
    var decision by rememberSaveable(session.id) { mutableStateOf("") }
    val now by produceState(System.currentTimeMillis(), session.id, session.restUntil, session.stopwatch) {
        val active = session.restUntil != null || session.stopwatch != null
        value = System.currentTimeMillis()
        while (active) {
            delay(250L)
            value = System.currentTimeMillis()
            if (session.stopwatch == null && (session.restUntil ?: 0) <= value) break
        }
    }
    val remaining = (((session.restUntil ?: 0) - now + 999) / 1000).coerceAtLeast(0)
    Screen {
        Heading(session.label, "Неделя ${session.week} · ${dateLabel(session.day)}")
        Hero {
            Text("Выполнено $completed из $total подходов")
            LinearProgressIndicator(progress = { completed.toFloat() / total },
                modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (remaining > 0) "Отдых · ${clock(remaining)}" else
                    if (session.restUntil != null) "Отдых завершён" else "Таймер отдыха",
                    style = MaterialTheme.typography.titleMedium)
                if (remaining > 0) OutlinedButton(onClick = model::skipRest) { Text("Пропустить") }
                else OutlinedButton(onClick = model::rest) { Text("Запустить") }
            }
        }
        session.logs.forEach { log ->
            key(log.exercise.id) {
                val open = log.exercise.id == focused
                Panel {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(log.exercise.name, style = MaterialTheme.typography.titleLarge)
                            Text("${log.sets.count { it.done }} из ${log.sets.size} подходов" +
                                if (log.control) " · контрольный подход" else "")
                        }
                        TextButton(onClick = { focused = if (open) null else log.exercise.id }) {
                            Text(if (open) "Свернуть" else "Открыть")
                        }
                    }
                    Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (open) {
                            if (log.control) Text("Запишите лучший результат одного подхода с чистой техникой.")
                            log.sets.forEachIndexed { index, entry ->
                                HorizontalDivider()
                                SetEntry(index, entry, log, session.stopwatch, now, model)
                            }
                            Choice("Сложность упражнения (RPE)", (0..10).toList(), log.effort ?: 0,
                                { if (it == 0) "Без оценки" else "$it из 10" }) {
                                model.effort(log.exercise.id, it.takeUnless { rating -> rating == 0 })
                            }
                            Text("RPE 7 — осталось примерно 3 повтора в запасе. RPE 10 — усилие на пределе.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Button(onClick = { if (completed < total) decision = "finish" else model.finish() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("Завершить тренировку")
        }
        TextButton(onClick = { decision = "discard" }, modifier = Modifier.fillMaxWidth()) {
            Text("Удалить черновик")
        }
    }
    if (decision.isNotEmpty()) {
        val discard = decision == "discard"
        AlertDialog(
            onDismissRequest = { decision = "" },
            title = { Text(if (discard) "Удалить черновик?" else "Завершить тренировку?") },
            text = { Text(if (discard) "Записанные в черновике подходы будут удалены."
                else "В историю попадут отмеченные подходы. Остальные будут считаться пропущенными.") },
            confirmButton = {
                TextButton(onClick = {
                    decision = ""
                    if (discard) model.discard() else model.finish()
                }) { Text(if (discard) "Удалить" else "Завершить") }
            },
            dismissButton = { TextButton(onClick = { decision = "" }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun SetEntry(index: Int, entry: SetLog, log: ExerciseLog, timer: Stopwatch?, now: Long,
    model: TrainingViewModel) {
    val exercise = log.exercise
    Text("Подход ${index + 1}", style = MaterialTheme.typography.titleMedium)
    if (!log.control) Text("Цель · ${entry.goal.label()} ${exercise.unit}" +
        if (exercise.sided) " на сторону" else "")
    val sides = if (exercise.sided) listOf(false, true) else listOf(false)
    sides.forEach { second ->
        val label = when {
            !exercise.sided -> if (exercise.timed) "Секунды" else "Повторы"
            second -> "Правая"
            else -> "Левая"
        }
        val active = timer?.exerciseId == exercise.id && timer.setIndex == index && timer.sideTwo == second
        val actual = if (second) entry.second else entry.value
        SetValue(label, actual, entry.done, exercise.limit,
            adjust = { model.value(exercise.id, index, second, it, relative = true) },
            enter = { model.value(exercise.id, index, second, it) })
        if (exercise.timed && !entry.done) {
            OutlinedButton(
                onClick = { model.stopwatch(exercise.id, index, second) },
                enabled = timer == null || active,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (active) "Остановить · ${clock(((now - timer!!.started) / 1000).coerceAtLeast(0))}"
                    else if (exercise.sided) "Секундомер · $label" else "Запустить секундомер")
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = entry.done,
            onCheckedChange = { model.mark(exercise.id, index) },
            enabled = entry.done || (entry.value > 0 && (!exercise.sided || entry.second > 0))
        )
        Text(if (entry.done) "Выполнено" else "Отметить выполненным")
    }
}

@Composable
private fun SetValue(label: String, value: Int, done: Boolean, limit: Int, adjust: (Int) -> Unit,
    enter: (Int) -> Unit) {
    var digits by rememberSaveable(value) { mutableStateOf(value.toString()) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { adjust(-1) }, enabled = !done && value > 0,
            modifier = Modifier.sizeIn(minWidth = 52.dp, minHeight = 48.dp)) { Text("−") }
        OutlinedTextField(
            value = digits,
            onValueChange = { candidate ->
                if (candidate.isEmpty()) {
                    digits = ""
                    enter(0)
                } else if (candidate.length <= 4 && candidate.all { it in '0'..'9' }) {
                    val number = candidate.toInt()
                    if (number <= limit) {
                        digits = candidate
                        enter(number)
                    }
                }
            },
            label = { Text(label) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = !done,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedButton(onClick = { adjust(1) }, enabled = !done && value < limit,
            modifier = Modifier.sizeIn(minWidth = 52.dp, minHeight = 48.dp)) { Text("+") }
    }
}

private fun clock(seconds: Long): String = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
