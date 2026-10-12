package com.example.gymbro.profile

import com.example.gymbro.network.Better
import com.example.gymbro.network.Post
import com.example.gymbro.network.PostKind

// Personal bests of a user, chosen automatically among the published posts:
// - runs: for every distance, the run done in the shortest time
// - manual results: for every exercise/activity (and way of measuring it: time or weight), the best
//   result, following the "higher/lower is better" choice of the most recent post of that exercise
// Gym sessions are not considered.
object PersonalBests {

    fun from(posts: List<Post>): List<Post> {
        val runs = posts
            .filter { it.kind == PostKind.RUN && it.distanceKm != null && it.durationSeconds != null }
            .groupBy { Math.round(it.distanceKm!! * 100) }  // same distance (to the nearest 10 m)
            .values
            .map { sameDistance -> sameDistance.minBy { it.durationSeconds!! } }
            .sortedBy { it.distanceKm }

        val manual = posts
            .filter { it.kind == PostKind.MANUAL && it.metric != null && it.amount != null }
            .groupBy { it.title.trim().lowercase() to it.metric }
            .values
            .map { sameExercise ->
                val better = sameExercise.maxBy { it.createdAt }.better
                if (better == Better.LOWER) sameExercise.minBy { it.amount!! } else sameExercise.maxBy { it.amount!! }
            }
            .sortedBy { it.title.lowercase() }

        return runs + manual
    }
}
