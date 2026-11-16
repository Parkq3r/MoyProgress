package ru.moyprogress.domain

private fun work(exercise: String, sets: Int, low: Int, high: Int = low) =
    Prescription(exercise, List(sets) { Goal(low, high) })

fun starterProgram(day: Long) = Program(
    id = "starter",
    name = "Турник и собственный вес",
    start = day,
    starter = true,
    workouts = listOf(
        Workout("starter-mon", "Силовая", 1, listOf(
            work("pull", 5, 1, 2), work("push", 3, 12), work("squat", 3, 15),
            work("bridge", 3, 15), work("plank", 3, 40)
        )),
        Workout("starter-wed", "Техника, ноги и корпус", 3, listOf(
            work("pull", 5, 1), work("scapular", 3, 5), work("lunge", 3, 8),
            work("side-plank", 2, 25), work("hang", 2, 30), work("calf", 3, 20)
        )),
        Workout("starter-fri", "Объёмная", 5, listOf(
            work("pull", 4, 1, 2), work("push", 3, 15), work("squat", 3, 20),
            work("bridge-one", 2, 8), work("plank", 3, 45), work("hang", 2, 35)
        ))
    )
)

fun initialData(day: Long) = TrainingData(
    exercises = listOf(
        Exercise("pull", "Подтягивания", baseline = 3),
        Exercise("push", "Отжимания", baseline = 25),
        Exercise("squat", "Приседания"),
        Exercise("bridge", "Ягодичный мост"),
        Exercise("plank", "Планка", timed = true, baseline = 90),
        Exercise("scapular", "Лопаточные подтягивания"),
        Exercise("lunge", "Обратные выпады", sided = true),
        Exercise("side-plank", "Боковая планка", timed = true, sided = true),
        Exercise("hang", "Вис на турнике", timed = true, baseline = 60),
        Exercise("calf", "Подъёмы на носки"),
        Exercise("bridge-one", "Ягодичный мост на одной ноге", sided = true)
    ),
    programs = listOf(starterProgram(firstTrainingMonday(day))),
    activeProgramId = "starter"
)

fun weekGoals(program: Program, workout: Workout, exercise: Prescription, week: Int): List<Goal> {
    if (!program.starter) return exercise.goals
    if (week == 4) {
        val count = (exercise.goals.size * 2 / 3).coerceAtLeast(1)
        return exercise.goals.take(count).map { Goal((it.low * 3 / 4).coerceAtLeast(1)) }
    }
    if (week == 8) {
        val test = when (workout.weekday) {
            1 -> exercise.exerciseId in setOf("pull", "push")
            5 -> exercise.exerciseId in setOf("plank", "hang")
            else -> false
        }
        return if (test) listOf(Goal(1)) else
            exercise.goals.take((exercise.goals.size * 2 / 3).coerceAtLeast(1))
                .map { Goal((it.low * 3 / 4).coerceAtLeast(1)) }
    }
    return exercise.goals
}

fun isControl(program: Program, workout: Workout, exercise: String, week: Int): Boolean =
    program.starter && week == 8 && when (workout.weekday) {
        1 -> exercise in setOf("pull", "push")
        5 -> exercise in setOf("plank", "hang")
        else -> false
    }

private fun firstTrainingMonday(day: Long): Long {
    val weekday = java.time.LocalDate.ofEpochDay(day).dayOfWeek.value
    return if (weekday == 1) day else day + 8 - weekday
}
