package com.example.gymbro.profile

import com.example.gymbro.network.Better
import com.example.gymbro.network.Post
import com.example.gymbro.network.PostKind

// Personal bests of a user, chosen automatically among the published posts:
// - runs: for every distance, the run done in the shortest time. The distances are grouped by
//   multiples of 0.5 km: 10 and 10.2 km count as 10 km, 10.3 and 10.7 km as 10.5 km, 10.8 as 11 km
// - manual results: for every exercise/activity (and way of measuring it: time or weight), the best
//   result, following the "higher/lower is better" choice of the most recent post of that exercise
// Gym sessions are not considered.
object PersonalBests {

    fun from(posts: List<Post>): List<Post> {
        val runs = posts
            .filter { it.kind == PostKind.RUN && it.distanceKm != null && it.durationSeconds != null }
            .filter { distanceGroupKm(it.distanceKm!!) > 0 }  // less than 0.25 km: not a distance
            .groupBy { distanceGroupKm(it.distanceKm!!) }
            .toSortedMap()
            .values
            .map { sameDistance -> sameDistance.minBy { it.durationSeconds!! } }

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

    // The distance a run counts as, in the personal bests: the nearest multiple of 0.5 km
    fun distanceGroupKm(km: Double): Double = Math.round(km * 2) / 2.0
}
