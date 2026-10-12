package com.example.gymbro.session

import org.json.JSONObject

// A single exercise of a gym session. weightKg = null for bodyweight exercises (e.g. dips).
data class Exercise(
    val name: String,
    val muscleGroup: String,
    val sets: Int,
    val reps: Int,
    val weightKg: Double? = null
) {
    // JSON format used both by the local database and by the REST API
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("muscle_group", muscleGroup)
        .put("sets", sets)
        .put("reps", reps)
        .put("weight_kg", weightKg ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = Exercise(
            name = json.getString("name"),
            muscleGroup = json.optString("muscle_group"),
            sets = json.getInt("sets"),
            reps = json.getInt("reps"),
            weightKg = if (json.isNull("weight_kg")) null else json.getDouble("weight_kg")
        )
    }
}
