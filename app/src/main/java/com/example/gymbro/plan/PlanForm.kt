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
    const val MAX_WEIGHT_KG = 1000.0

    // An exercise written in the window to change or add an exercise. Rest and weight: empty = not
    // set; weight: also with a comma (e.g. "22,5").
    fun exercise(
        name: String, muscleGroup: String, sets: String, reps: String, rest: String, notes: String, weight: String = ""
    ): Check<PlanExercise> {
        if (name.isBlank()) return Check.Invalid(R.string.error_exercise_name)
        val setsNumber = sets.trim().toIntOrNull()
        if (setsNumber == null || setsNumber !in 1..MAX_SETS) return Check.Invalid(R.string.error_plan_sets)
        if (reps.isBlank()) return Check.Invalid(R.string.error_plan_reps)
        val restSeconds = if (rest.isBlank()) null else rest.trim().toIntOrNull()
        if (rest.isNotBlank() && (restSeconds == null || restSeconds !in 0..MAX_REST_SECONDS)) {
            return Check.Invalid(R.string.error_plan_rest)
        }
        val weightKg = if (weight.isBlank()) null else weight.trim().replace(',', '.').toDoubleOrNull()
        if (weight.isNotBlank() && (weightKg == null || weightKg !in 0.0..MAX_WEIGHT_KG)) {
            return Check.Invalid(R.string.error_plan_weight)
        }
        return Check.Valid(PlanExercise(name.trim(), muscleGroup.trim(), setsNumber, reps.trim(), restSeconds, notes.trim(), weightKg))
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
