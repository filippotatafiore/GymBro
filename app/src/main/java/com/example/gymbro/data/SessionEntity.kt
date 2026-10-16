package com.example.gymbro.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.gymbro.session.Exercise
import com.example.gymbro.session.Route
import com.example.gymbro.session.SessionType

// A finished session of a user (LAST SESSIONS and calendar of the home): one row of the
// "training_sessions" table of the local database, so that the sessions can be seen also offline.
// - serverId = null: saved only on this phone (e.g. finished offline), sent to the server as soon
//   as it is reachable (see SessionRepository)
// - deleted: deleted on this phone while offline, deleted on the server as soon as it is reachable
// The other rows are the copy of the sessions saved on the server, downloaded at every sync.
@Entity(tableName = "training_sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,     // local id
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
    val clientId: String? = null,           // chosen by the app (filled also for the old rows, see AppDatabase)
    val planId: Long? = null,
    val planName: String? = null,
    val planDay: Int? = null,
    val planDayName: String? = null,
    val serverId: Long? = null,             // id on the server (null = not sent yet)
    @ColumnInfo(defaultValue = "0")
    val deleted: Boolean = false            // deleted on this phone, not on the server yet
) {
    fun toSession() = TrainingSession(
        id = serverId ?: 0,
        localId = id,
        clientId = clientId ?: "local-$userId-$id-$startTime",
        type = type, startTime = startTime, durationMillis = durationMillis, distanceKm = distanceKm,
        kcal = kcal, maxSpeedKmh = maxSpeedKmh, goalKm = goalKm, route = route, exercises = exercises,
        publishedPostId = publishedPostId, planId = planId, planName = planName, planDay = planDay,
        planDayName = planDayName
    )

    companion object {
        // serverId: the session as saved on the server (null = not sent yet)
        fun of(userId: Long, session: TrainingSession, serverId: Long? = null) = SessionEntity(
            userId = userId, type = session.type, startTime = session.startTime,
            durationMillis = session.durationMillis, distanceKm = session.distanceKm, kcal = session.kcal,
            maxSpeedKmh = session.maxSpeedKmh, goalKm = session.goalKm, route = session.route,
            exercises = session.exercises, publishedPostId = session.publishedPostId,
            clientId = session.clientId, planId = session.planId, planName = session.planName,
            planDay = session.planDay, planDayName = session.planDayName, serverId = serverId
        )
    }
}
