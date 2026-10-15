package com.example.gymbro.data

import com.example.gymbro.session.Exercise
import com.example.gymbro.session.Route
import com.example.gymbro.session.SessionType

// A finished training session of the user (LAST SESSIONS and calendar of the home), saved on the
// server: the same sessions on every phone where the user logs in. Before being sent to the server
// a session waits in the local database (see PendingSession).
data class TrainingSession(
    val id: Long = 0,                       // id on the server (0 = not sent yet)
    val clientId: String,                   // chosen by the app: the server saves the same session only once
    val type: SessionType,
    val startTime: Long,                    // milliseconds since 1970
    val durationMillis: Long,               // training time, pauses excluded
    val distanceKm: Double? = null,         // run sessions only: rounded to 0.1 km
    val kcal: Double? = null,               // run sessions only: calories burned (estimated)
    val maxSpeedKmh: Double? = null,        // run sessions only
    val goalKm: Double? = null,             // run sessions only: goal distance (null = no goal)
    val route: Route? = null,               // run sessions only: path followed on the map
    val exercises: List<Exercise>? = null,  // gym sessions only: as they were done in the session
    val publishedPostId: Long? = null,      // post on the profile (null = not published)
    val planId: Long? = null,               // gym sessions only: the workout plan followed (null = none)
    val planName: String? = null,           // name of the plan at the time of the session
    val planDay: Int? = null,               // index of the day of the plan followed
    val planDayName: String? = null         // e.g. "Day 1 · Push"
)
