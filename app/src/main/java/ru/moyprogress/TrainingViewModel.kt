package ru.moyprogress

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.moyprogress.data.SettingsRepository
import ru.moyprogress.data.TrainingRepository
import ru.moyprogress.domain.*

class TrainingViewModel(app: Application) : AndroidViewModel(app) {
    private val training = TrainingRepository(app)
    private val settingsRepository = SettingsRepository(app)
    private val commands = Mutex()
    private val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices = messages.asSharedFlow()
    val data = training.data.catch { messages.emit("Не удалось открыть дневник.") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Preferences())

    init {
        perform { training.initialize() }
    }

    private fun perform(action: suspend () -> Unit) {
        viewModelScope.launch {
            commands.withLock {
                try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    messages.emit(
                        if (e is IllegalArgumentException || e is IllegalStateException)
                            e.message ?: "Операция не выполнена."
                        else "Операция не выполнена. Попробуйте ещё раз."
                    )
                }
            }
        }
    }

    fun updateSettings(update: (Preferences) -> Preferences) = perform {
        settingsRepository.set(update(settings.value))
    }

    fun saveExercise(exercise: Exercise) = perform {
        training.change { data ->
            require(exercise.name.isNotBlank() && exercise.name.length <= 80) { "Введите название упражнения." }
            require(data.exercises.none { it.name.equals(exercise.name, true) }) {
                "Упражнение с таким названием уже есть."
            }
            data.copy(exercises = data.exercises + exercise)
        }
    }

    fun saveProgram(program: Program, activate: Boolean = false) = perform {
        training.change { data ->
            require(program.name.isNotBlank() && program.weeks in 1..52) { "Проверьте название и срок программы." }
            val exists = data.programs.any { it.id == program.id }
            val programs = if (exists) data.programs.map { if (it.id == program.id) program else it }
                else data.programs + program
            val retained = data.proposals.filter { proposal ->
                if (proposal.programId != program.id) true
                else program.workouts.firstOrNull { it.id == proposal.workoutId }?.exercises
                    ?.any { it.exerciseId == proposal.exerciseId && it.goals == proposal.before } == true
            }
            data.copy(programs = programs, proposals = retained,
                activeProgramId = if (activate) program.id else data.activeProgramId)
        }
    }

    fun activate(program: Program) = perform {
        training.change { it.copy(activeProgramId = program.id) }
    }

    fun start(programId: String, workoutId: String, day: Long, slot: Long? = null) = perform {
        training.change { data ->
            if (data.draft != null) return@change data
            val program = data.programs.firstOrNull { it.id == programId } ?: return@change data
            val workout = program.workouts.firstOrNull { it.id == workoutId } ?: return@change data
            if (workout.exercises.isEmpty()) return@change data
            val week = program.week(slot ?: day).coerceIn(1, program.weeks)
            val scheduled = slot ?: (program.firstMonday + (week - 1) * 7 + workout.weekday - 1)
            val logs = workout.exercises.mapNotNull { target ->
                val exercise = data.exercises.firstOrNull { it.id == target.exerciseId }
                    ?: return@mapNotNull null
                ExerciseLog(exercise, weekGoals(program, workout, target, week).map { SetLog(it) },
                    control = isControl(program, workout, exercise.id, week))
            }
            if (logs.isEmpty()) return@change data
            data.copy(sessions = data.sessions + Session(programId = program.id,
                workoutId = workout.id, label = workout.name, day = day, slot = scheduled,
                week = week, recovery = program.starter && week in setOf(4, 8), logs = logs))
        }
    }

    private fun alterDraft(change: (Session) -> Session) = perform {
        training.change { data ->
            val draft = data.draft ?: return@change data
            data.copy(sessions = data.sessions.map { if (it.id == draft.id) change(it) else it })
        }
    }

    private fun Session.editLog(id: String, edit: (ExerciseLog) -> ExerciseLog): Session =
        copy(logs = logs.map { if (it.exercise.id == id) edit(it) else it })

    private fun ExerciseLog.editSet(index: Int, edit: (SetLog) -> SetLog): ExerciseLog =
        copy(sets = sets.mapIndexed { i, log -> if (i == index) edit(log) else log })

    fun value(id: String, index: Int, second: Boolean, amount: Int, relative: Boolean = false) =
        alterDraft { session ->
            session.editLog(id) { log ->
                log.editSet(index) { entry ->
                    if (entry.done) entry else {
                        val current = if (second) entry.second else entry.value
                        val value = (if (relative) current + amount else amount).coerceIn(0, log.exercise.limit)
                        if (second) entry.copy(second = value) else entry.copy(value = value)
                    }
                }
            }
        }

    fun mark(id: String, index: Int) = alterDraft { session ->
        val log = session.logs.firstOrNull { it.exercise.id == id } ?: return@alterDraft session
        val set = log.sets.getOrNull(index) ?: return@alterDraft session
        if (!set.done) {
            require(session.stopwatch?.let { it.exerciseId == id && it.setIndex == index } != true) {
                "Сначала остановите секундомер."
            }
            require(set.value > 0 && (!log.exercise.sided || set.second > 0)) {
                "Введите результат подхода."
            }
        }
        var updated = session.editLog(id) { it.editSet(index) { entry -> entry.copy(done = !entry.done) } }
        if (!set.done) updated = updated.copy(restUntil = System.currentTimeMillis() + settings.value.rest * 1000L)
        updated
    }

    fun effort(id: String, rating: Int?) = alterDraft { it.editLog(id) { log -> log.copy(effort = rating) } }

    fun rest() = alterDraft {
        it.copy(restUntil = System.currentTimeMillis() + settings.value.rest * 1000L)
    }

    fun skipRest() = alterDraft { it.copy(restUntil = null) }

    fun stopwatch(id: String, index: Int, second: Boolean) = alterDraft { session ->
        val log = session.logs.firstOrNull { it.exercise.id == id } ?: return@alterDraft session
        if (!log.exercise.timed || log.sets.getOrNull(index)?.done != false) return@alterDraft session
        val current = session.stopwatch
        val now = System.currentTimeMillis()
        if (current == null) {
            session.copy(stopwatch = Stopwatch(id, index, second, now))
        } else {
            require(current.exerciseId == id && current.setIndex == index && current.sideTwo == second) {
                "Сначала остановите запущенный секундомер."
            }
            val seconds = ((now - current.started) / 1000L).coerceIn(1, log.exercise.limit.toLong()).toInt()
            session.editLog(id) { it.editSet(index) { entry ->
                if (second) entry.copy(second = seconds) else entry.copy(value = seconds)
            } }.copy(stopwatch = null)
        }
    }

    fun finish() = perform {
        training.change { data ->
            val draft = data.draft ?: return@change data
            require(draft.stopwatch == null) { "Остановите секундомер перед завершением." }
            val end = draft.copy(finishedAt = System.currentTimeMillis(), restUntil = null)
            val completed = data.copy(sessions = data.sessions.map { if (it.id == draft.id) end else it })
            completed.copy(proposals = completed.proposals + propose(completed, end, settings.value))
        }
    }

    fun discard() = perform { training.change { data -> data.copy(sessions = data.sessions.filter { it.finishedAt != null }) } }

    fun review(proposal: Proposal, step: Int?) = perform {
        training.change { data ->
            val live = data.proposals.firstOrNull { it.id == proposal.id } ?: return@change data
            require(step == null || step in 1..live.step)
            val programs = if (step == null) data.programs else data.programs.map { program ->
                if (program.id != live.programId) program else program.copy(workouts = program.workouts.map { workout ->
                    if (workout.id != live.workoutId) workout else workout.copy(exercises = workout.exercises.map { target ->
                        if (target.exerciseId != live.exerciseId || target.goals != live.before) target
                        else target.copy(goals = target.goals.mapIndexed { index, goal ->
                            if (index == live.setIndex) increasedGoal(goal, step) else goal
                        })
                    })
                })
            }
            data.copy(programs = programs, proposals = data.proposals - live,
                reviewedSources = data.reviewedSources + live.source)
        }
    }

    fun reset() = perform {
        training.change { it.copy(sessions = emptyList(), proposals = emptyList(), reviewedSources = emptySet()) }
        messages.emit("Статистика очищена.")
    }

    fun export(uri: Uri) = perform {
        val backup = training.export()
        withContext(Dispatchers.IO) {
            val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ?: error("Не удалось открыть выбранный файл.")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(backup) }
        }
        messages.emit("Резервная копия сохранена.")
    }

    fun import(uri: Uri) = perform {
        val text = withContext(Dispatchers.IO) {
            val stream = getApplication<Application>().contentResolver.openInputStream(uri)
                ?: error("Не удалось открыть выбранный файл.")
            stream.use {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val length = it.read(buffer)
                    if (length == -1) break
                    require(output.size() + length <= 16 * 1024 * 1024) { "Файл резервной копии слишком большой." }
                    output.write(buffer, 0, length)
                }
                output.toString(Charsets.UTF_8.name())
            }
        }
        training.import(text)
        messages.emit("Данные восстановлены.")
    }
}
