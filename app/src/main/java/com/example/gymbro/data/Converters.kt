package com.example.gymbro.data

import androidx.room.TypeConverter
import com.example.gymbro.session.Exercise
import com.example.gymbro.session.Route
import com.example.gymbro.session.routeFromJson
import com.example.gymbro.session.toJson
import org.json.JSONArray

// Tells Room how to save values that are not numbers or texts, as JSON texts:
// the list of exercises of a gym session, the route of a run session
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

    @TypeConverter
    fun routeToJson(route: Route?): String? = route?.toJson()?.toString()

    @TypeConverter
    fun jsonToRoute(json: String?): Route? = json?.let { routeFromJson(JSONArray(it)) }
}
