package ru.moyprogress.domain

fun propose(data: TrainingData, recent: Session, prefs: Preferences): List<Proposal> {
    if (!prefs.progression || recent.recovery || recent.finishedAt == null) return emptyList()
    val program = data.programs.firstOrNull { it.id == recent.programId } ?: return emptyList()
    val workout = program.workouts.firstOrNull { it.id == recent.workoutId } ?: return emptyList()
    val finished = data.sessions.filter { it.finishedAt != null }
        .sortedByDescending { it.finishedAt }
    return recent.logs.mapNotNull { current ->
        val exercise = current.exercise
        val target = workout.exercises.firstOrNull { it.exerciseId == exercise.id }
            ?: return@mapNotNull null
        val source = "${recent.id}/${exercise.id}"
        if (source in data.reviewedSources || data.proposals.any {
            it.programId == program.id && it.workoutId == workout.id && it.exerciseId == exercise.id
        }) return@mapNotNull null

        val required = if (exercise.id == "pull") maxOf(prefs.streak, 3) else prefs.streak
        val effort = if (exercise.id == "pull") minOf(prefs.maxEffort, 7) else prefs.maxEffort
        val recovery = if (exercise.id == "pull") maxOf(prefs.recoveryDays, 2) else prefs.recoveryDays
        val matching = finished.filter {
            it.programId == recent.programId && it.workoutId == recent.workoutId && !it.recovery
        }.take(required)
        if (matching.size < required || matching.first().id != recent.id) return@mapNotNull null
        val reliable = matching.all { session ->
            val log = session.logs.firstOrNull { it.exercise.id == exercise.id }
            log != null && !log.control && log.sets.map { it.goal } == target.goals &&
                log.successful(effort)
        }
        if (!reliable) return@mapNotNull null

        val exposures = finished.filter { it.logs.any { log ->
            log.exercise.id == exercise.id && log.sets.any { entry -> entry.done }
        } }
        val rested = matching.all { session ->
            val index = exposures.indexOfFirst { it.id == session.id }
            index >= 0 && (exposures.getOrNull(index + 1)?.let { session.day - it.day >= recovery } ?: true)
        }
        if (!rested) return@mapNotNull null

        val index = target.goals.indices.minWithOrNull(
            compareBy<Int> { target.goals[it].low }.thenBy { target.goals[it].high }
        ) ?: return@mapNotNull null
        if (target.goals[index].low >= exercise.limit) return@mapNotNull null
        val step = if (exercise.timed) minOf(5, exercise.limit - target.goals[index].low) else 1
        Proposal(programId = program.id, workoutId = workout.id, exerciseId = exercise.id,
            before = target.goals, setIndex = index, step = step, source = source)
    }
}

fun increasedGoal(previous: Goal, step: Int): Goal {
    val floor = previous.low + step
    return Goal(floor, maxOf(floor, previous.high))
}

data class ScheduledWorkout(val day: Long, val week: Int, val workout: Workout)

fun schedule(data: TrainingData): List<ScheduledWorkout> {
    val program = data.active ?: return emptyList()
    return (1..program.weeks).flatMap { week ->
        program.workouts.filter { it.exercises.isNotEmpty() }
            .sortedBy { it.weekday }.mapNotNull { workout ->
                val day = program.firstMonday + (week - 1) * 7 + workout.weekday - 1
                if (day < program.start) null else ScheduledWorkout(day, week, workout)
            }
    }
}

data class ResultPoint(val day: Long, val best: Int, val volume: Int)
data class Record(val exercise: Exercise, val value: Int, val day: Long)

fun results(sessions: List<Session>, exerciseId: String): List<ResultPoint> =
    sessions.asSequence().filter { it.finishedAt != null }.mapNotNull { session ->
        val log = session.logs.firstOrNull { it.exercise.id == exerciseId } ?: return@mapNotNull null
        val values = log.sets.filter { it.done }.flatMap {
            if (log.exercise.sided) listOf(it.value, it.second) else listOf(it.value)
        }
        if (values.isEmpty()) null else ResultPoint(session.day, values.max(), values.sum())
    }.groupBy { it.day }.map { (day, points) ->
        ResultPoint(day, points.maxOf { it.best }, points.sumOf { it.volume })
    }.sortedBy { it.day }

fun records(data: TrainingData): List<Record> = data.exercises.mapNotNull { exercise ->
    val points = results(data.sessions, exercise.id)
    val best = points.maxOfOrNull { it.best } ?: return@mapNotNull null
    if (best <= (exercise.baseline ?: 0)) return@mapNotNull null
    Record(exercise, best, points.first { it.best == best }.day)
}.sortedByDescending { it.day }
