package com.example.gymbro.plan

import com.example.gymbro.repository.PlanRepository

// The exercise to change is not in the day of the plan anymore (e.g. the plan was changed meanwhile)
class ExerciseNotInPlanException : Exception()

// Changes made to the exercises during a gym session, saved also in the workout plan: on this phone
// at once, on the server now or (offline) at the next sync (see PlanRepository). The plan is
// synchronized first if the server is reachable: it may have been changed meanwhile (e.g. by the coach).
object SessionPlanChanges {

    // Replaces an exercise of the day [dayIndex] (at [planIndex] when the session started, named
    // [oldName] in the session) with [exercise]. Returns the saved plan (synced = false: offline).
    // Throws ApiException, or ExerciseNotInPlanException.
    suspend fun replace(planId: Long, dayIndex: Int, planIndex: Int, oldName: String, exercise: PlanExercise): PlanRepository.Saved {
        val plan = currentPlan(planId)
        val day = plan.days.getOrNull(dayIndex) ?: throw ExerciseNotInPlanException()
        val index = findExercise(day, planIndex, oldName) ?: throw ExerciseNotInPlanException()
        return PlanRepository.update(plan.replaceExercise(dayIndex, index, exercise))
    }

    // Adds an exercise at the end of the day [dayIndex]. Returns its position in the day, and the
    // saved plan. Throws ApiException, or ExerciseNotInPlanException (day not found or full).
    suspend fun add(planId: Long, dayIndex: Int, exercise: PlanExercise): Pair<Int, PlanRepository.Saved> {
        val plan = currentPlan(planId)
        val day = plan.days.getOrNull(dayIndex) ?: throw ExerciseNotInPlanException()
        if (day.exercises.size >= Plan.MAX_DAY_EXERCISES) throw ExerciseNotInPlanException()
        return day.exercises.size to PlanRepository.update(plan.addExercise(dayIndex, exercise))
    }

    // The plan as it is now: the version of the server if it is reachable, otherwise the one saved
    // on this phone (with the changes made offline)
    private suspend fun currentPlan(planId: Long): Plan {
        PlanRepository.trySync()
        return PlanRepository.plan(planId) ?: throw ExerciseNotInPlanException()
    }

    // Position of the exercise in the day: the one at the same position if it has the same name,
    // otherwise the first one with that name, otherwise the same position (the name was changed
    // during the session); null if the day is shorter now. Plain Kotlin: tested in GymSessionsTest.kt
    fun findExercise(day: PlanDay, planIndex: Int, name: String): Int? {
        val exercises = day.exercises
        if (exercises.getOrNull(planIndex)?.name == name) return planIndex
        val sameName = exercises.indexOfFirst { it.name == name }
        if (sameName >= 0) return sameName
        return planIndex.takeIf { it in exercises.indices }
    }
}
