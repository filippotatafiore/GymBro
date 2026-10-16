package com.example.gymbro.profile

import com.example.gymbro.network.Better
import com.example.gymbro.network.Post
import com.example.gymbro.network.PostKind
import kotlin.math.abs

// Personal bests of a user, chosen automatically among the published posts:
// - runs: for every distance, the run done in the shortest time. The distances are grouped by
//   multiples of 0.5 km: 10 and 10.2 km count as 10 km, 10.3 and 10.7 km as 10.5 km, 10.8 as 11 km
// - swims: for every distance, the swim with the best average pace (the shortest time every 100 m).
//   The distances are grouped by multiples of 0.5 km, at most 0.2 km away: 1.3 and 1.7 km count as
//   1.5 km; 1.25 km (0.25 km from 1 and from 1.5 km) is in no group
// - manual results: for every exercise/activity (and way of measuring it: time or weight), the best
//   result, following the "higher/lower is better" choice of the most recent post of that exercise
// Gym sessions are not considered. The same rules are on the server (personal_best_ids in
// server/app.py), which marks the personal bests in the feed of the social page.
object PersonalBests {

    // A swim counts for a distance if it is at most this far from it
    private const val SWIM_MARGIN_M = 200

    fun from(posts: List<Post>): List<Post> {
        val runs = posts
            .filter { it.kind == PostKind.RUN && it.distanceKm != null && it.durationSeconds != null }
            .filter { distanceGroupKm(it.distanceKm!!) > 0 }  // less than 0.25 km: not a distance
            .groupBy { distanceGroupKm(it.distanceKm!!) }
            .toSortedMap()
            .values
            .map { sameDistance -> sameDistance.minBy { it.durationSeconds!! } }

        val swims = posts
            .filter { it.kind == PostKind.SWIM && it.durationSeconds != null && (it.distanceKm ?: 0.0) > 0 }
            .mapNotNull { post -> swimGroupKm(post.distanceKm!!)?.let { group -> group to post } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .values
            .map { sameDistance -> sameDistance.minBy { it.durationSeconds!! / it.distanceKm!! } }

        val manual = posts
            .filter { it.kind == PostKind.MANUAL && it.metric != null && it.amount != null }
            .groupBy { it.title.trim().lowercase() to it.metric }
            .values
            .map { sameExercise ->
                val better = sameExercise.maxBy { it.createdAt }.better
                if (better == Better.LOWER) sameExercise.minBy { it.amount!! } else sameExercise.maxBy { it.amount!! }
            }
            .sortedBy { it.title.lowercase() }

        return runs + swims + manual
    }

    // The distance a run counts as, in the personal bests: the nearest multiple of 0.5 km
    fun distanceGroupKm(km: Double): Double = Math.round(km * 2) / 2.0

    // The distance a swim counts as: the nearest multiple of 0.5 km, if it is at most 0.2 km away
    // (null: no distance, e.g. 1.25 km or less than 0.3 km). In meters: no errors of the decimals.
    fun swimGroupKm(km: Double): Double? {
        val meters = Math.round(km * 1000)
        val group = Math.round(meters / 500.0) * 500
        if (group == 0L || abs(meters - group) > SWIM_MARGIN_M) return null
        return group / 1000.0
    }
}
