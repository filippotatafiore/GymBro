package com.example.gymbro.plan

import androidx.annotation.StringRes
import com.example.gymbro.R
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

// Goal of a workout plan. `json` = how it is written in the requests/answers of the REST API
enum class PlanGoal(val json: String, @StringRes val label: Int) {
    LOSE_WEIGHT("lose_weight", R.string.goal_lose_weight),
    BUILD_MUSCLE("build_muscle", R.string.goal_build_muscle),
    GAIN_MASS("gain_mass", R.string.goal_gain_mass),
    STRENGTH("strength", R.string.goal_strength),
    FITNESS("fitness", R.string.goal_fitness)
}

enum class PlanLevel(val json: String, @StringRes val label: Int) {
    BEGINNER("beginner", R.string.level_beginner),
    INTERMEDIATE("intermediate", R.string.level_intermediate),
    ADVANCED("advanced", R.string.level_advanced)
}

// An exercise of a workout plan. reps: a text, e.g. "10", "8-12", "30 s" (planks, cardio);
// restSeconds: rest between the sets (null = not set); weightKg: the load used (null = not set,
// e.g. bodyweight exercises)
data class PlanExercise(
    val name: String,
    val muscleGroup: String,
    val sets: Int,
    val reps: String,
    val restSeconds: Int?,
    val notes: String,
    val weightKg: Double? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("muscle_group", muscleGroup)
        .put("sets", sets)
        .put("reps", reps)
        .put("rest_s", restSeconds ?: JSONObject.NULL)
        .put("weight_kg", weightKg ?: JSONObject.NULL)
        .put("notes", notes)

    companion object {
        fun fromJson(json: JSONObject) = PlanExercise(
            name = json.getString("name"),
            muscleGroup = json.optString("muscle_group"),
            sets = json.getInt("sets"),
            reps = json.getString("reps"),
            restSeconds = if (json.isNull("rest_s")) null else json.getInt("rest_s"),
            notes = json.optString("notes"),
            weightKg = if (json.isNull("weight_kg")) null else json.getDouble("weight_kg")
        )
    }
}

// A training day of a plan, e.g. "Day 1 · Push", with its exercises in the order to do them
data class PlanDay(val name: String, val exercises: List<PlanExercise>) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("exercises", JSONArray(exercises.map { it.toJson() }))

    companion object {
        fun fromJson(json: JSONObject) = PlanDay(
            name = json.getString("name"),
            exercises = json.getJSONArray("exercises").let { array ->
                (0 until array.length()).map { PlanExercise.fromJson(array.getJSONObject(it)) }
            }
        )
    }
}

// A gym workout plan of the user, written by the AI coach and saved on the server (it can then be
// changed by hand or by the coach). goal, level, daysPerWeek, minutesPerSession, preferences: what
// the user asked when creating it. notes: the advice of the coach. isPublic: everyone can see it on
// the profile of the user (and add a copy of it to their plans). fromUsername: the plan is a copy of
// a plan of that user (public or shared with Messages), null if not.
data class Plan(
    val id: Long,
    val name: String,
    val goal: PlanGoal?,
    val level: PlanLevel?,
    val daysPerWeek: Int?,
    val minutesPerSession: Int?,
    val preferences: String,
    val notes: String,
    val days: List<PlanDay>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isPublic: Boolean = false,
    val fromUsername: String? = null
) {
    // What the user can change (sent to the server when saving the changes)
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("notes", notes)
        .put("days", JSONArray(days.map { it.toJson() }))


    // --------------------------------------------------------- CHANGES (they return the changed plan)

    fun replaceExercise(dayIndex: Int, exerciseIndex: Int, exercise: PlanExercise) =
        changeDay(dayIndex) { day -> day.copy(exercises = day.exercises.toMutableList().also { it[exerciseIndex] = exercise }) }

    fun removeExercise(dayIndex: Int, exerciseIndex: Int) =
        changeDay(dayIndex) { day -> day.copy(exercises = day.exercises.toMutableList().also { it.removeAt(exerciseIndex) }) }

    fun addExercise(dayIndex: Int, exercise: PlanExercise) =
        changeDay(dayIndex) { day -> day.copy(exercises = day.exercises + exercise) }

    fun renameDay(dayIndex: Int, name: String) = changeDay(dayIndex) { it.copy(name = name) }

    fun removeDay(dayIndex: Int) = copy(days = days.toMutableList().also { it.removeAt(dayIndex) })

    fun addDay(name: String) = copy(days = days + PlanDay(name, emptyList()))

    private fun changeDay(dayIndex: Int, change: (PlanDay) -> PlanDay) =
        copy(days = days.toMutableList().also { it[dayIndex] = change(it[dayIndex]) })

    companion object {
        // Limits of the server (server/app.py)
        const val MAX_DAYS = 7
        const val MAX_DAY_EXERCISES = 15

        fun fromJson(json: JSONObject) = Plan(
            id = json.getLong("id"),
            name = json.getString("name"),
            goal = json.optString("goal").let { value -> PlanGoal.entries.firstOrNull { it.json == value } },
            level = json.optString("level").let { value -> PlanLevel.entries.firstOrNull { it.json == value } },
            daysPerWeek = if (json.isNull("days_per_week")) null else json.getInt("days_per_week"),
            minutesPerSession = if (json.isNull("minutes_per_session")) null else json.getInt("minutes_per_session"),
            preferences = if (json.isNull("preferences")) "" else json.getString("preferences"),
            notes = if (json.isNull("notes")) "" else json.getString("notes"),
            days = json.getJSONArray("days").let { array ->
                (0 until array.length()).map { PlanDay.fromJson(array.getJSONObject(it)) }
            },
            createdAt = Instant.parse(json.getString("created_at")),
            updatedAt = Instant.parse(json.getString("updated_at")),
            isPublic = json.optBoolean("public"),
            fromUsername = if (json.isNull("from_username")) null else json.getString("from_username")
        )
    }
}
