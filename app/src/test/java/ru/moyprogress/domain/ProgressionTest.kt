package ru.moyprogress.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ProgressionTest {
    private val monday = LocalDate.parse("2026-11-16").toEpochDay()
    private val data = initialData(monday)
    private val plan = data.programs.single()
    private val workout = plan.workouts.first()
    private val exercise = data.exercises.first()
    private val goals = workout.exercises.first().goals

    @Test
    fun calendarContainsEightWeeksAndRecoveryIsLighter() {
        assertEquals(24, schedule(data).size)
        assertEquals(listOf(1, 3, 5), plan.workouts.map { it.weekday })
        assertEquals(5, goals.size)
        assertTrue(weekGoals(plan, workout, workout.exercises.first(), 4).size < goals.size)
        assertTrue(isControl(plan, workout, "pull", 8))
        assertFalse(isControl(plan, workout, "pull", 4))
    }

    @Test
    fun pullUpProgressionAdjustsOneSetAfterThreeSuccessfulSessions() {
        val sessions = listOf(session(0), session(7), session(14))
        val next = propose(data.copy(sessions = sessions), sessions.last(), Preferences(streak = 2)).single()
        assertEquals(1, next.step)
        assertEquals(Goal(2), increasedGoal(next.before[next.setIndex], next.step))
        assertEquals(5, next.before.size)
    }

    @Test
    fun lowVolumeHighEffortAndShortRecoveryBlockProgression() {
        val records = listOf(session(0), session(7))
        for (recent in listOf(session(14, reps = 1), session(14, effort = 8), session(8))) {
            val all = records + recent
            assertTrue(propose(data.copy(sessions = all), recent, Preferences()).isEmpty())
        }
    }

    @Test
    fun volumesCombineBothSidesAndIgnoreUnfinishedSets() {
        val exercise = Exercise(name = "Выпады", sided = true)
        val session = session(0).copy(logs = listOf(ExerciseLog(exercise,
            listOf(SetLog(Goal(5), 6, 5, true), SetLog(Goal(5), 9, 9, false)))))
        assertEquals(ResultPoint(monday, 6, 11), results(listOf(session), exercise.id).single())
    }

    private fun session(offset: Int, reps: Int = 2, effort: Int = 7): Session {
        val day = monday + offset
        return Session(programId = plan.id, workoutId = workout.id, label = workout.name,
            day = day, slot = day, week = offset / 7 + 1, finishedAt = day * 86_400_000L,
            logs = listOf(ExerciseLog(exercise, goals.map { SetLog(it, reps, done = true) }, effort)))
    }
}
