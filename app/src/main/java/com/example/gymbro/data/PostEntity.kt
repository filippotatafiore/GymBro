package com.example.gymbro.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.gymbro.network.Better
import com.example.gymbro.network.Metric
import com.example.gymbro.network.Post
import com.example.gymbro.network.PostKind
import com.example.gymbro.session.Exercise
import com.example.gymbro.session.Route
import java.time.Instant

// A post published by the logged user: one row of the "posts" table of the local database, the copy
// of the posts of the server, so that the profile (POSTS and PERSONAL BESTS) can be seen also
// offline. The posts are published and deleted only online (see ProfileRepository).
@Entity(tableName = "posts")
data class PostEntity(
    @PrimaryKey val id: Long,               // id on the server
    val userId: Long,
    val kind: PostKind,
    val title: String,
    val metric: Metric?,
    val better: Better?,
    val amount: Double?,
    val notes: String?,
    val durationSeconds: Long?,
    val distanceKm: Double?,
    val kcal: Double?,
    val maxSpeedKmh: Double?,
    val goalKm: Double?,
    val route: Route?,
    val exercises: List<Exercise>?,
    val sessionStart: Long?,                // milliseconds since 1970
    val createdAt: Long,
    val steps: Int? = null,
    val poolLengthM: Double? = null,
    val laps: Int? = null,
    val goalLaps: Int? = null
) {
    fun toPost() = Post(
        id = id, kind = kind, title = title, metric = metric, better = better, amount = amount, notes = notes,
        durationSeconds = durationSeconds, distanceKm = distanceKm, kcal = kcal, maxSpeedKmh = maxSpeedKmh,
        goalKm = goalKm, route = route, exercises = exercises,
        sessionStart = sessionStart?.let { Instant.ofEpochMilli(it) }, createdAt = Instant.ofEpochMilli(createdAt),
        steps = steps, poolLengthM = poolLengthM, laps = laps, goalLaps = goalLaps
    )

    companion object {
        fun of(userId: Long, post: Post) = PostEntity(
            id = post.id, userId = userId, kind = post.kind, title = post.title, metric = post.metric,
            better = post.better, amount = post.amount, notes = post.notes, durationSeconds = post.durationSeconds,
            distanceKm = post.distanceKm, kcal = post.kcal, maxSpeedKmh = post.maxSpeedKmh, goalKm = post.goalKm,
            route = post.route, exercises = post.exercises, sessionStart = post.sessionStart?.toEpochMilli(),
            createdAt = post.createdAt.toEpochMilli(), steps = post.steps, poolLengthM = post.poolLengthM,
            laps = post.laps, goalLaps = post.goalLaps
        )
    }
}
