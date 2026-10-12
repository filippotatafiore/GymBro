package com.example.gymbro.network

import com.example.gymbro.session.Exercise
import com.example.gymbro.session.Route
import java.time.Instant

// `json` = how the value is written in the requests/answers of the REST API
enum class PostKind(val json: String) { MANUAL("manual"), GYM("gym"), RUN("run") }

// How a manual result is measured
enum class Metric(val json: String) { TIME("time"), WEIGHT("weight") }

// Which result is better
enum class Better(val json: String) { HIGHER("higher"), LOWER("lower") }

// A post published by a user on the profile: a result added manually, or a finished gym/run session
data class Post(
    val id: Long,
    val kind: PostKind,
    val title: String,                       // manual: exercise/activity
    val metric: Metric? = null,              // manual
    val better: Better? = null,              // manual
    val amount: Double? = null,              // manual: seconds (time) or kg (weight)
    val notes: String? = null,               // manual
    val durationSeconds: Long? = null,       // gym / run: training time
    val distanceKm: Double? = null,          // run: rounded to 0.1 km
    val kcal: Double? = null,                // run: calories burned (estimated)
    val maxSpeedKmh: Double? = null,         // run
    val goalKm: Double? = null,              // run: goal distance (null = no goal)
    val route: Route? = null,                // run: path followed (null = not recorded)
    val exercises: List<Exercise>? = null,   // gym
    val sessionStart: Instant? = null,       // gym / run: when the session was done
    val createdAt: Instant
)
