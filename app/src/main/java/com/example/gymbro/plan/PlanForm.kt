package com.example.gymbro.plan

import androidx.annotation.StringRes
import com.example.gymbro.R

// Checks of what the user writes when changing a plan by hand (plain Kotlin: tested in PlanTest.kt).
// The limits are the same of the server (clean_plan in server/app.py).
object PlanForm {

    // The checked value, or the error to show (a string resource)
    sealed interface Check<out T> {
        data class Valid<T>(val value: T) : Check<T>
        data class Invalid(@StringRes val message: Int) : Check<Nothing>
    }

    const val MAX_SETS = 20
    const val MAX_REST_SECONDS = 900

    // An exercise written in the window to change or add an exercise. Rest: empty = not set.
    fun exercise(name: String, muscleGroup: String, sets: String, reps: String, rest: String, notes: String): Check<PlanExercise> {
        if (name.isBlank()) return Check.Invalid(R.string.error_exercise_name)
        val setsNumber = sets.trim().toIntOrNull()
        if (setsNumber == null || setsNumber !in 1..MAX_SETS) return Check.Invalid(R.string.error_plan_sets)
        if (reps.isBlank()) return Check.Invalid(R.string.error_plan_reps)
        val restSeconds = if (rest.isBlank()) null else rest.trim().toIntOrNull()
        if (rest.isNotBlank() && (restSeconds == null || restSeconds !in 0..MAX_REST_SECONDS)) {
            return Check.Invalid(R.string.error_plan_rest)
        }
        return Check.Valid(PlanExercise(name.trim(), muscleGroup.trim(), setsNumber, reps.trim(), restSeconds, notes.trim()))
    }

    // Name of a plan or of a day
    fun name(text: String): Check<String> =
        if (text.isBlank()) Check.Invalid(R.string.error_plan_name) else Check.Valid(text.trim())

    // Rest between the sets, e.g. "90 s", "2 min", "2 min 30 s"
    fun formatRest(seconds: Int): String = when {
        seconds < 120 -> "$seconds s"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "${seconds / 60} min ${seconds % 60} s"
    }
}
