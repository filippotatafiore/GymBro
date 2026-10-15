package com.example.gymbro.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.gymbro.session.Exercise
import com.example.gymbro.session.Route
import com.example.gymbro.session.SessionType

// A finished session not sent to the server yet: one row of the "training_sessions" table of the
// local database. It is sent as soon as the server is reachable (see TrainingSessions), then
// deleted from here. The sessions saved by the older versions of the app, when all the sessions
// stayed on the phone, are in this table too: they are sent to the server the same way.
@Entity(tableName = "training_sessions")
data class PendingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,                       // the user who did the session (id on the server)
    val type: SessionType,
    val startTime: Long,
    val durationMillis: Long,
    val distanceKm: Double? = null,
    val kcal: Double? = null,
    val maxSpeedKmh: Double? = null,
    val goalKm: Double? = null,
    val route: Route? = null,
    val exercises: List<Exercise>? = null,
    val publishedPostId: Long? = null,
    val clientId: String? = null,           // null in the sessions saved by the older versions
    val planId: Long? = null,
    val planName: String? = null,
    val planDay: Int? = null,
    val planDayName: String? = null
) {
    fun toSession() = TrainingSession(
        clientId = clientId ?: "local-$userId-$id-$startTime",
        type = type, startTime = startTime, durationMillis = durationMillis, distanceKm = distanceKm,
        kcal = kcal, maxSpeedKmh = maxSpeedKmh, goalKm = goalKm, route = route, exercises = exercises,
        publishedPostId = publishedPostId, planId = planId, planName = planName, planDay = planDay,
        planDayName = planDayName
    )

    companion object {
        fun of(userId: Long, session: TrainingSession) = PendingSession(
            userId = userId, type = session.type, startTime = session.startTime,
            durationMillis = session.durationMillis, distanceKm = session.distanceKm, kcal = session.kcal,
            maxSpeedKmh = session.maxSpeedKmh, goalKm = session.goalKm, route = session.route,
            exercises = session.exercises, publishedPostId = session.publishedPostId,
            clientId = session.clientId, planId = session.planId, planName = session.planName,
            planDay = session.planDay, planDayName = session.planDayName
        )
    }
}
