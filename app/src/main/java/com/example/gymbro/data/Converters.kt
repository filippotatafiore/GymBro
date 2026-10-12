package com.example.gymbro.data

import androidx.room.TypeConverter
import com.example.gymbro.session.Exercise
import org.json.JSONArray

// Tells Room how to save values that are not numbers or texts:
// the list of exercises of a gym session is saved as a JSON text
class Converters {

    @TypeConverter
    fun exercisesToJson(exercises: List<Exercise>?): String? =
        exercises?.let { list -> JSONArray(list.map { it.toJson() }).toString() }

    @TypeConverter
    fun jsonToExercises(json: String?): List<Exercise>? =
        json?.let { text ->
            val array = JSONArray(text)
            (0 until array.length()).map { Exercise.fromJson(array.getJSONObject(it)) }
        }
}
