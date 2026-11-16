package ru.moyprogress.domain

import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Serializable

fun newId() = UUID.randomUUID().toString()

@Serializable
data class Exercise(
    val id: String = newId(),
    val name: String,
    val timed: Boolean = false,
    val sided: Boolean = false,
    val baseline: Int? = null
) {
    val unit get() = if (timed) "с" else "повт."
    val limit get() = if (timed) 3600 else 500
}

@Serializable
data class Goal(val low: Int, val high: Int = low) {
    fun label(): String = if (low == high) "$low" else "$low–$high"
}

@Serializable
data class Prescription(val exerciseId: String, val goals: List<Goal>)

@Serializable
data class Workout(
    val id: String = newId(),
    val name: String,
    val weekday: Int,
    val exercises: List<Prescription> = emptyList()
)

@Serializable
data class Program(
    val id: String = newId(),
    val name: String,
    val start: Long,
    val weeks: Int = 8,
    val starter: Boolean = false,
    val workouts: List<Workout> = emptyList()
) {
    // A program week follows the local calendar, starting on Monday.
    val firstMonday: Long get() {
        val day = LocalDate.ofEpochDay(start)
        return start - day.dayOfWeek.value + 1
    }

    fun week(day: Long): Int =
        if (day < firstMonday) 0 else ((day - firstMonday) / 7 + 1).coerceAtMost(53).toInt()
}

@Serializable
data class SetLog(
    val goal: Goal,
    val value: Int = 0,
    val second: Int = 0,
    val done: Boolean = false
)

@Serializable
data class ExerciseLog(
    val exercise: Exercise,
    val sets: List<SetLog>,
    val effort: Int? = null,
    val control: Boolean = false
) {
    fun successful(maxEffort: Int): Boolean =
        effort?.let { it <= maxEffort } == true && sets.all { entry ->
            entry.done && entry.value >= entry.goal.high &&
                (!exercise.sided || entry.second >= entry.goal.high)
        }
}

@Serializable
data class Stopwatch(
    val exerciseId: String,
    val setIndex: Int,
    val sideTwo: Boolean,
    val started: Long
)

@Serializable
data class Session(
    val id: String = newId(),
    val programId: String,
    val workoutId: String,
    val label: String,
    val day: Long,
    val slot: Long,
    val week: Int,
    val recovery: Boolean = false,
    val finishedAt: Long? = null,
    val logs: List<ExerciseLog>,
    val restUntil: Long? = null,
    val stopwatch: Stopwatch? = null
)

@Serializable
data class Proposal(
    val id: String = newId(),
    val programId: String,
    val workoutId: String,
    val exerciseId: String,
    val before: List<Goal>,
    val setIndex: Int,
    val step: Int,
    val source: String
)

@Serializable
data class TrainingData(
    val exercises: List<Exercise>,
    val programs: List<Program>,
    val activeProgramId: String,
    val sessions: List<Session> = emptyList(),
    val proposals: List<Proposal> = emptyList(),
    val reviewedSources: Set<String> = emptySet()
) {
    val active get() = programs.firstOrNull { it.id == activeProgramId }
    val draft get() = sessions.firstOrNull { it.finishedAt == null }
}

enum class ThemeChoice(val title: String) {
    SYSTEM("Система"),
    LIGHT("Светлая"),
    DARK("Тёмная")
}

@Serializable
data class Preferences(
    val theme: String = ThemeChoice.SYSTEM.name,
    val dynamic: Boolean = true,
    val rest: Int = 90,
    val progression: Boolean = true,
    val streak: Int = 3,
    val maxEffort: Int = 7,
    val recoveryDays: Int = 2
)
