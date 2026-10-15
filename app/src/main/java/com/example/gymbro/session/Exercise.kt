package com.example.gymbro.session

import com.example.gymbro.plan.PlanExercise
import org.json.JSONObject

// An exercise of a gym session, as it is done: it starts as a copy of the exercise of the workout
// plan and can be changed during the session (e.g. a heavier weight).
// reps: a text, e.g. "10", "8-12", "30 s"; weightKg = null for bodyweight exercises (e.g. dips);
// done: marked as completed; planIndex: position in the day of the plan (null = added during the
// session, or a session without plan).
data class Exercise(
    val name: String,
    val muscleGroup: String,
    val sets: Int,
    val reps: String,
    val weightKg: Double? = null,
    val restSeconds: Int? = null,
    val notes: String = "",
    val done: Boolean = false,
    val planIndex: Int? = null
) {
    // JSON format used by the local database, by the session in progress and by the REST API
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("muscle_group", muscleGroup)
        .put("sets", sets)
        .put("reps", reps)
        .put("weight_kg", weightKg ?: JSONObject.NULL)
        .put("rest_s", restSeconds ?: JSONObject.NULL)
        .put("notes", notes)
        .put("done", done)
        .putOpt("plan_index", planIndex)

    // The editable part, as an exercise of a plan (e.g. to save the changes also in the plan)
    fun toPlanExercise() = PlanExercise(name, muscleGroup, sets, reps, restSeconds, notes, weightKg)

    // The same exercise with the values written in the editing window (done and planIndex stay)
    fun withChanges(changes: PlanExercise) = copy(
        name = changes.name, muscleGroup = changes.muscleGroup, sets = changes.sets, reps = changes.reps,
        weightKg = changes.weightKg, restSeconds = changes.restSeconds, notes = changes.notes
    )

    companion object {
        fun fromJson(json: JSONObject) = Exercise(
            name = json.getString("name"),
            muscleGroup = json.optString("muscle_group"),
            sets = json.getInt("sets"),
            // reps were numbers in the sessions saved before the workout plans: getString turns them into texts
            reps = json.getString("reps"),
            weightKg = if (json.isNull("weight_kg")) null else json.getDouble("weight_kg"),
            restSeconds = if (json.isNull("rest_s")) null else json.getInt("rest_s"),
            notes = if (json.isNull("notes")) "" else json.optString("notes"),
            // sessions saved before the exercises could be marked: all done
            done = json.optBoolean("done", true),
            planIndex = if (json.isNull("plan_index")) null else json.getInt("plan_index")
        )

        // An exercise of the plan at the beginning of a session (not done yet), or a new exercise
        // written during the session (planIndex = null)
        fun fromPlan(exercise: PlanExercise, planIndex: Int?) = Exercise(
            exercise.name, exercise.muscleGroup, exercise.sets, exercise.reps, exercise.weightKg,
            exercise.restSeconds, exercise.notes, done = false, planIndex = planIndex
        )
    }
}
