package com.example.gymbro

import com.example.gymbro.plan.Plan
import com.example.gymbro.plan.PlanDay
import com.example.gymbro.plan.PlanExercise
import com.example.gymbro.plan.PlanForm
import com.example.gymbro.plan.PlanGoal
import com.example.gymbro.plan.PlanLevel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

// Run with: right click on this file > Run 'PlanTest'
class PlanTest {

    private val bench = PlanExercise("Bench Press", "Chest", 4, "8-10", 90, "")
    private val row = PlanExercise("Barbell Row", "Back", 4, "8-10", 90, "")
    private val plan = Plan(
        id = 1, name = "Upper / Lower", goal = PlanGoal.BUILD_MUSCLE, level = PlanLevel.BEGINNER,
        daysPerWeek = 2, minutesPerSession = 60, preferences = "", notes = "",
        days = listOf(PlanDay("Upper", listOf(bench, row)), PlanDay("Lower", emptyList())),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH
    )

    @Test
    fun exercise_valid() {
        val check = PlanForm.exercise(" Chest Press ", "Chest", "3", " 10-12 ", "", "slow")
        assertEquals(PlanForm.Check.Valid(PlanExercise("Chest Press", "Chest", 3, "10-12", null, "slow")), check)
    }

    @Test
    fun exercise_errors() {
        assertEquals(PlanForm.Check.Invalid(R.string.error_exercise_name), PlanForm.exercise(" ", "", "3", "10", "", ""))
        assertEquals(PlanForm.Check.Invalid(R.string.error_plan_sets), PlanForm.exercise("Squat", "", "0", "10", "", ""))
        assertEquals(PlanForm.Check.Invalid(R.string.error_plan_sets), PlanForm.exercise("Squat", "", "21", "10", "", ""))
        assertEquals(PlanForm.Check.Invalid(R.string.error_plan_sets), PlanForm.exercise("Squat", "", "", "10", "", ""))
        assertEquals(PlanForm.Check.Invalid(R.string.error_plan_reps), PlanForm.exercise("Squat", "", "3", "  ", "", ""))
        assertEquals(PlanForm.Check.Invalid(R.string.error_plan_rest), PlanForm.exercise("Squat", "", "3", "10", "901", ""))
        assertEquals(PlanForm.Check.Invalid(R.string.error_plan_rest), PlanForm.exercise("Squat", "", "3", "10", "1m", ""))
    }

    @Test
    fun name_trimmedOrError() {
        assertEquals(PlanForm.Check.Valid("Day 1 · Push"), PlanForm.name("  Day 1 · Push "))
        assertEquals(PlanForm.Check.Invalid(R.string.error_plan_name), PlanForm.name("   "))
    }

    @Test
    fun formatRest() {
        assertEquals("45 s", PlanForm.formatRest(45))
        assertEquals("90 s", PlanForm.formatRest(90))
        assertEquals("2 min", PlanForm.formatRest(120))
        assertEquals("2 min 30 s", PlanForm.formatRest(150))
    }

    @Test
    fun replaceExercise_onlyThatOneChanges() {
        val pullUp = PlanExercise("Pull-up", "Back", 4, "max", 120, "")
        val changed = plan.replaceExercise(0, 1, pullUp)
        assertEquals(listOf(bench, pullUp), changed.days[0].exercises)
        assertEquals(plan.days[1], changed.days[1])
        // the original plan is not changed
        assertEquals(listOf(bench, row), plan.days[0].exercises)
    }

    @Test
    fun addAndRemoveExercises() {
        val squat = PlanExercise("Squat", "Legs", 5, "5", 180, "")
        val added = plan.addExercise(1, squat)
        assertEquals(listOf(squat), added.days[1].exercises)
        assertEquals(listOf(row), added.removeExercise(0, 0).days[0].exercises)
    }

    @Test
    fun days() {
        val changed = plan.renameDay(1, "Legs").addDay("Day 3")
        assertEquals(listOf("Upper", "Legs", "Day 3"), changed.days.map { it.name })
        assertEquals(listOf("Upper", "Day 3"), changed.removeDay(1).days.map { it.name })
    }
}
