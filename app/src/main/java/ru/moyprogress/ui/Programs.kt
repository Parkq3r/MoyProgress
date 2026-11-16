package ru.moyprogress.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.moyprogress.TrainingViewModel
import ru.moyprogress.domain.*

@Composable
internal fun Programs(data: TrainingData, today: Long, model: TrainingViewModel) {
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var newExercise by rememberSaveable { mutableStateOf(false) }
    var shown by rememberSaveable { mutableStateOf<String?>(null) }
    val current = data.programs.firstOrNull { it.id == shown } ?: data.active
    val draft = editor?.let { Json.decodeFromString<Program>(it) }
    if (newExercise) ExerciseDialog(
        onDismiss = { newExercise = false },
        save = { model.saveExercise(it); newExercise = false }
    )
    if (draft != null) {
        BackHandler { editor = null }
        ProgramEditor(draft, data.exercises, update = { editor = Json.encodeToString(it) },
            close = { editor = null }, addExercise = { newExercise = true },
            save = {
                model.saveProgram(draft)
                shown = draft.id
                editor = null
            })
        return
    }

    Screen {
        Heading("Тренировки")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { editor = Json.encodeToString(Program(name = "", start = today)) },
                modifier = Modifier.weight(1f)
            ) { Text("Новая программа") }
            OutlinedButton(onClick = { newExercise = true }, modifier = Modifier.weight(1f)) {
                Text("Новое упражнение")
            }
        }
        if (data.programs.size > 1) Choice("Программа", data.programs, current, { it.name }) {
            shown = it.id
        }
        if (current != null) {
            Panel {
                Text(current.name, style = MaterialTheme.typography.titleLarge)
                Text("Начало ${dateLabel(current.start)} · ${current.weeks} нед.")
                if (current.starter) Text("Неделя 4 · облегчённая\nНеделя 8 · контроль результатов")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editor = Json.encodeToString(current) }) { Text("Изменить") }
                    if (current.id != data.activeProgramId) {
                        Button(onClick = { model.activate(current) }) { Text("Выбрать") }
                    }
                }
            }
            val week = current.week(today).coerceIn(1, current.weeks)
            current.workouts.sortedBy { it.weekday }.forEach { workout ->
                Panel {
                    Text("${weekdays[workout.weekday - 1]} · ${workout.name}",
                        style = MaterialTheme.typography.titleLarge)
                    workout.exercises.forEach { item ->
                        val exercise = data.exercises.firstOrNull { it.id == item.exerciseId }
                        if (exercise != null) {
                            val goals = weekGoals(current, workout, item, week)
                            Text("${exercise.name} · " + goalSummary(goals) + " ${exercise.unit}" +
                                if (exercise.sided) " на сторону" else "")
                        }
                    }
                    Button(
                        onClick = { model.start(current.id, workout.id, today) },
                        enabled = workout.exercises.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Начать тренировку") }
                }
            }
        }
        val pending = data.proposals.filter { it.programId == current?.id }
        if (pending.isNotEmpty()) Section("Предложения по нагрузке")
        pending.forEach { proposal ->
            val exercise = data.exercises.firstOrNull { it.id == proposal.exerciseId }
            val workout = current?.workouts?.firstOrNull { it.id == proposal.workoutId }
            if (exercise != null && workout != null) ProposalCard(proposal, exercise, workout, model)
        }
    }
}

@Composable
private fun ProposalCard(proposal: Proposal, exercise: Exercise, workout: Workout, model: TrainingViewModel) {
    var step by rememberSaveable(proposal.id) { mutableIntStateOf(proposal.step) }
    var setIndex by rememberSaveable(proposal.id) { mutableIntStateOf(proposal.setIndex) }
    val available = proposal.before.indices.filter { proposal.before[it].low < exercise.limit }
    Panel {
        Text(exercise.name, style = MaterialTheme.typography.titleLarge)
        Text(workout.name)
        val before = proposal.before[setIndex]
        val maxStep = minOf(proposal.step, exercise.limit - before.low)
        val increment = step.coerceIn(1, maxStep)
        val after = increasedGoal(before, increment)
        if (available.size > 1) {
            Choice("Изменить подход", available, setIndex, { "Подход ${it + 1}" }) {
                setIndex = it
            }
        } else {
            Text("Подход ${setIndex + 1}")
        }
        Text("${before.label()} → ${after.label()} ${exercise.unit}")
        if (maxStep > 1) {
            Text("Прибавка · $increment ${exercise.unit}")
            Slider(value = increment.toFloat(), onValueChange = { step = kotlin.math.round(it).toInt() },
                valueRange = 1f..maxStep.toFloat(), steps = maxStep - 2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.review(proposal, increment, setIndex) }) { Text("Принять") }
            TextButton(onClick = { model.review(proposal, null) }) { Text("Отклонить") }
        }
    }
}

private fun goalSummary(goals: List<Goal>): String =
    if (goals.distinct().size == 1) "${goals.size} × ${goals.first().label()}"
    else goals.joinToString(" + ") { it.label() }

@Composable
private fun ProgramEditor(
    program: Program,
    exercises: List<Exercise>,
    update: (Program) -> Unit,
    close: () -> Unit,
    addExercise: () -> Unit,
    save: () -> Unit
) {
    var selectedDay by rememberSaveable(program.id) { mutableStateOf<String?>(null) }
    var editExercise by rememberSaveable(program.id) { mutableStateOf<String?>(null) }
    var targetDay by rememberSaveable(program.id) { mutableStateOf<String?>(null) }
    val target = program.workouts.firstOrNull { it.id == targetDay }
    val selectedExercise = target?.exercises?.firstOrNull { it.exerciseId == editExercise }
    val workout = program.workouts.firstOrNull { it.id == selectedDay }
    fun updateDay(day: Workout) {
        val list = if (program.workouts.any { it.id == day.id })
            program.workouts.map { if (it.id == day.id) day else it }
        else program.workouts + day
        update(program.copy(workouts = list))
    }
    val invalidName = program.name.trim().isEmpty()
    Screen {
        Heading("Программа")
        OutlinedTextField(
            value = program.name, onValueChange = { update(program.copy(name = it.take(80))) },
            label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Panel {
            var start by rememberSaveable(program.id) { mutableStateOf(LocalDate.ofEpochDay(program.start).toString()) }
            val parsed = runCatching { LocalDate.parse(start) }.getOrNull()?.toEpochDay()
                ?.takeIf { it in -365_000L..365_000L }
            OutlinedTextField(value = start, onValueChange = {
                start = it.take(10)
                runCatching { LocalDate.parse(start).toEpochDay() }.getOrNull()
                    ?.takeIf { it in -365_000L..365_000L }?.let { day ->
                        update(program.copy(start = day))
                    }
            }, label = { Text("Дата старта") }, supportingText = { Text("ГГГГ-ММ-ДД") },
                isError = parsed == null, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Choice("Количество недель", if (program.starter) listOf(8) else (1..52).toList(),
                program.weeks, { it.toString() }) { update(program.copy(weeks = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = save, enabled = !invalidName && parsed != null) { Text("Сохранить") }
                TextButton(onClick = close) { Text("Отмена") }
            }
        }
        program.workouts.sortedBy { it.weekday }.forEach { day ->
            Panel {
                Text("${weekdays[day.weekday - 1]} · ${day.name}",
                    style = MaterialTheme.typography.titleLarge)
                day.exercises.forEach { item ->
                    val exercise = exercises.firstOrNull { it.id == item.exerciseId }
                    if (exercise != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${exercise.name} · ${goalSummary(item.goals)} ${exercise.unit}",
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = { targetDay = day.id; editExercise = exercise.id }) { Text("Цель") }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { selectedDay = day.id }) { Text("Изменить день") }
                    TextButton(onClick = { targetDay = day.id; editExercise = null }) { Text("Добавить упражнение") }
                }
            }
        }
        OutlinedButton(onClick = { selectedDay = "" }, modifier = Modifier.fillMaxWidth()) {
            Text("Добавить тренировочный день")
        }
        TextButton(onClick = addExercise) { Text("Создать упражнение") }
    }
    if (selectedDay != null) WorkoutDialog(workout,
        close = { selectedDay = null },
        save = { updateDay(it); selectedDay = null },
        remove = {
            update(program.copy(workouts = program.workouts.filter { it.id != workout?.id }))
            selectedDay = null
        })
    if (target != null) TargetDialog(
        options = exercises.filter { exercise -> target.exercises.none { it.exerciseId == exercise.id } ||
            exercise.id == editExercise },
        current = selectedExercise,
        close = { targetDay = null; editExercise = null },
        save = { prescription ->
            val list = if (selectedExercise == null) target.exercises + prescription
                else target.exercises.map { if (it == selectedExercise) prescription else it }
            updateDay(target.copy(exercises = list))
            targetDay = null
        },
        remove = {
            updateDay(target.copy(exercises = target.exercises.filterNot { it == selectedExercise }))
            targetDay = null
        })
}

@Composable
internal fun <T> Choice(
    label: String,
    entries: List<T>,
    selected: T?,
    labelFor: (T) -> String,
    choose: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(),
                enabled = entries.isNotEmpty()) {
                Text(selected?.let(labelFor) ?: "Выбрать", maxLines = 2)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 330.dp)) {
                entries.forEach { item ->
                    DropdownMenuItem(text = { Text(labelFor(item)) }, onClick = {
                        expanded = false
                        choose(item)
                    })
                }
            }
        }
    }
}

@Composable
private fun ExerciseDialog(onDismiss: () -> Unit, save: (Exercise) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var timed by rememberSaveable { mutableStateOf(false) }
    var sided by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Новое упражнение") }, text = {
        Column {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Название") }, singleLine = true)
            Toggle("На время", timed) { timed = it }
            Toggle("Учитывать обе стороны", sided) { sided = it }
        }
    }, confirmButton = {
        TextButton(onClick = { save(Exercise(name = name.trim(), timed = timed, sided = sided)) },
            enabled = name.isNotBlank()) { Text("Добавить") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
private fun WorkoutDialog(current: Workout?, close: () -> Unit, save: (Workout) -> Unit, remove: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current?.name.orEmpty()) }
    var day by rememberSaveable { mutableIntStateOf(current?.weekday ?: 1) }
    AlertDialog(onDismissRequest = close, title = { Text("Тренировочный день") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Название") }, singleLine = true)
            Choice("День недели", (1..7).toList(), day, { weekdays[it - 1] }) { day = it }
            if (current != null) TextButton(onClick = remove) { Text("Удалить день из программы") }
        }
    }, confirmButton = {
        TextButton(onClick = {
            save(current?.copy(name = name.trim(), weekday = day) ?: Workout(name = name.trim(), weekday = day))
        }, enabled = name.isNotBlank()) { Text("Готово") }
    }, dismissButton = { TextButton(onClick = close) { Text("Отмена") } })
}

@Composable
private fun TargetDialog(
    options: List<Exercise>, current: Prescription?, close: () -> Unit,
    save: (Prescription) -> Unit, remove: () -> Unit
) {
    var id by rememberSaveable { mutableStateOf(current?.exerciseId) }
    var count by rememberSaveable { mutableStateOf((current?.goals?.size ?: 3).toString()) }
    var low by rememberSaveable { mutableStateOf((current?.goals?.first()?.low ?: 1).toString()) }
    var high by rememberSaveable { mutableStateOf((current?.goals?.first()?.high ?: 1).toString()) }
    val exercise = options.firstOrNull { it.id == id }
    val n = count.toIntOrNull()
    val a = low.toIntOrNull()
    val b = high.toIntOrNull()
    val valid = exercise != null && n != null && n in 1..20 &&
        a != null && a in 1..exercise.limit && b != null && b in a..exercise.limit
    AlertDialog(onDismissRequest = close, title = { Text("Цель упражнения") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice("Упражнение", options, exercise, { it.name }) { id = it.id }
            NumberField("Подходы (1–20)", count) { count = it }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField("От (${exercise?.unit ?: "повт."})", low, Modifier.weight(1f)) { low = it }
                NumberField("До", high, Modifier.weight(1f)) { high = it }
            }
            if (current != null) TextButton(onClick = remove) { Text("Убрать из тренировки") }
            if (current != null && current.goals.distinct().size > 1)
                Text("Изменение полей назначает одинаковую цель всем подходам.")
        }
    }, confirmButton = {
        TextButton(onClick = {
            if (valid) {
                val uniform = List(n!!) { Goal(a!!, b!!) }
                val preserve = current != null && current.exerciseId == id && current.goals.size == n &&
                    current.goals.first() == uniform.first()
                save(Prescription(id!!, if (preserve) current!!.goals else uniform))
            }
        }, enabled = valid) { Text("Готово") }
    }, dismissButton = { TextButton(onClick = close) { Text("Отмена") } })
}

@Composable
internal fun NumberField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= 4 && it.all { char -> char in '0'..'9' }) onChange(it) },
        modifier = modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
internal fun Toggle(title: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = change)
    }
}
