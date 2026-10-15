package com.example.gymbro

import com.example.gymbro.data.TrainingSession
import com.example.gymbro.plan.PlanDay
import com.example.gymbro.plan.PlanExercise
import com.example.gymbro.plan.PlanForm
import com.example.gymbro.plan.SessionPlanChanges
import com.example.gymbro.session.Exercise
import com.example.gymbro.session.SessionType
import com.example.gymbro.session.lastSessionOfPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Run with: right click on this file > Run 'GymSessionsTest'
class GymSessionsTest {

    private fun gym(start: Long, planId: Long?, day: Int?) = TrainingSession(
        clientId = "c$start", type = SessionType.GYM, startTime = start, durationMillis = 1000,
        planId = planId, planDay = day
    )

    @Test
    fun lastSessionOfPlan_mostRecentOfThatPlan() {
        val sessions = listOf(
            gym(100, planId = 1, day = 0),
            gym(300, planId = 1, day = 2),
            gym(200, planId = 1, day = 1),
            gym(400, planId = 2, day = 0),                  // another plan
            gym(500, planId = null, day = null),            // without plan
            TrainingSession(clientId = "run", type = SessionType.RUN, startTime = 600, durationMillis = 1000)
        )
        assertEquals(300L, lastSessionOfPlan(sessions, 1)?.startTime)
        assertEquals(2, lastSessionOfPlan(sessions, 1)?.planDay)
        assertNull(lastSessionOfPlan(sessions, 3))
    }

    private val day = PlanDay("Push", listOf(
        PlanExercise("Bench Press", "Chest", 4, "8", 90, ""),
        PlanExercise("Dips", "Triceps", 3, "10", 60, "")
    ))

    @Test
    fun findExercise_samePlaceSameName() {
        assertEquals(1, SessionPlanChanges.findExercise(day, 1, "Dips"))
    }

    @Test
    fun findExercise_movedInThePlan() {
        // the plan was changed during the session: Dips is now the first exercise
        val changed = PlanDay("Push", day.exercises.reversed())
        assertEquals(0, SessionPlanChanges.findExercise(changed, 1, "Dips"))
    }

    @Test
    fun findExercise_renamedInTheSession_samePlace() {
        assertEquals(0, SessionPlanChanges.findExercise(day, 0, "Dumbbell Press"))
    }

    @Test
    fun findExercise_dayShorterNow() {
        assertNull(SessionPlanChanges.findExercise(PlanDay("Push", day.exercises.take(1)), 1, "Squat"))
    }

    @Test
    fun weight_optionalWithCommaOrPoint() {
        fun weight(text: String) = PlanForm.exercise("Squat", "", "3", "10", "", "", text)
        assertEquals(22.5, (weight("22,5") as PlanForm.Check.Valid).value.weightKg!!, 0.001)
        assertEquals(60.0, (weight(" 60 ") as PlanForm.Check.Valid).value.weightKg!!, 0.001)
        assertNull((weight("") as PlanForm.Check.Valid).value.weightKg)
        assertEquals(PlanForm.Check.Invalid(R.string.error_plan_weight), weight("1001"))
        assertEquals(PlanForm.Check.Invalid(R.string.error_plan_weight), weight("heavy"))
    }

    @Test
    fun sessionExercise_changesKeepDoneAndPlace() {
        val exercise = Exercise.fromPlan(PlanExercise("Bench Press", "Chest", 4, "8", 90, "", 60.0), planIndex = 2)
            .copy(done = true)
        val changed = exercise.withChanges(PlanExercise("Bench Press", "Chest", 4, "6", 120, "heavy", 65.0))
        assertEquals(Exercise("Bench Press", "Chest", 4, "6", 65.0, 120, "heavy", done = true, planIndex = 2), changed)
        // back to the plan, without done and position
        assertEquals(PlanExercise("Bench Press", "Chest", 4, "6", 120, "heavy", 65.0), changed.toPlanExercise())
    }

    @Test
    fun sessionExercise_fromThePlan_notDoneYet() {
        val exercise = Exercise.fromPlan(day.exercises[1], planIndex = 1)
        assertEquals(Exercise("Dips", "Triceps", 3, "10", null, 60, "", done = false, planIndex = 1), exercise)
        assertNull(Exercise.fromPlan(day.exercises[1], planIndex = null).planIndex)
    }
}
