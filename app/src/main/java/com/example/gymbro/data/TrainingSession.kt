package com.example.gymbro.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.gymbro.session.Exercise
import com.example.gymbro.session.Route
import com.example.gymbro.session.SessionType

// A finished training session: one row of the "training_sessions" table of the local database
@Entity(tableName = "training_sessions")
data class TrainingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,                       // the user who did the session (id on the server)
    val type: SessionType,
    val startTime: Long,                    // milliseconds since 1970
    val durationMillis: Long,               // training time, pauses excluded
    val distanceKm: Double? = null,         // run sessions only: rounded to 0.1 km
    val kcal: Double? = null,               // run sessions only: calories burned (estimated)
    val maxSpeedKmh: Double? = null,        // run sessions only
    val goalKm: Double? = null,             // run sessions only: goal distance (null = no goal)
    val route: Route? = null,               // run sessions only: path followed on the map
    val exercises: List<Exercise>? = null,  // gym sessions only
    val publishedPostId: Long? = null       // post on the profile (null = not published)
)
