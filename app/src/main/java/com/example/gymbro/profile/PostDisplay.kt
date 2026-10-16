package com.example.gymbro.profile

import android.content.Context
import androidx.annotation.DrawableRes
import com.example.gymbro.R
import com.example.gymbro.formatDistance
import com.example.gymbro.formatKcal
import com.example.gymbro.formatPace
import com.example.gymbro.formatDuration
import com.example.gymbro.formatWeight
import com.example.gymbro.network.Better
import com.example.gymbro.network.Metric
import com.example.gymbro.network.Post
import com.example.gymbro.network.PostKind

// How a post is shown in the lists of the profile: icon, title and main value

@DrawableRes
fun Post.icon(): Int = when (kind) {
    PostKind.RUN -> R.drawable.shoe_icon
    PostKind.GYM -> R.drawable.dumbbell_icon
    PostKind.MANUAL -> if (metric == Metric.TIME) R.drawable.timer_icon else R.drawable.dumbbell_icon
}

// e.g. "Run · 5 km", "Gym session", "Gym · Day 1 · Push", "Deadlift"
fun Post.displayTitle(context: Context): String = when (kind) {
    PostKind.RUN -> context.getString(R.string.run_post_title, formatDistance(distanceKm ?: 0.0))
    // e.g. "Gym · Day 1 · Push" (written by the app when publishing)
    PostKind.GYM -> title.ifBlank { context.getString(R.string.gym_post_title) }
    PostKind.MANUAL -> title
}

// e.g. "150 kg", "00:45" (manual results), "42:10" (training time of a session)
fun Post.displayValue(): String = when (kind) {
    PostKind.MANUAL ->
        if (metric == Metric.TIME) formatDuration(((amount ?: 0.0) * 1000).toLong())
        else formatWeight(amount ?: 0.0)
    PostKind.GYM, PostKind.RUN -> formatDuration((durationSeconds ?: 0L) * 1000)
}

// Line under the title in the feed, e.g. "Pace 05:15 /km · 620 kcal" (run), "5 of 6 exercises"
// (gym), the notes or "Weight · higher is better" (manual); null if there is nothing to add
fun Post.details(context: Context): String? = when (kind) {
    PostKind.RUN -> listOfNotNull(
        distanceKm?.takeIf { it > 0 && durationSeconds != null }
            ?.let { context.getString(R.string.run_post_details, formatPace(durationSeconds!!, it)) },
        kcal?.let { formatKcal(it) }
    ).joinToString(" · ").ifEmpty { null }
    PostKind.GYM -> exercises?.takeIf { it.isNotEmpty() }
        ?.let { context.getString(R.string.completed_count, it.count { exercise -> exercise.done }, it.size) }
    PostKind.MANUAL -> notes?.takeIf { it.isNotBlank() } ?: context.getString(
        R.string.manual_post_details,
        context.getString(if (metric == Metric.TIME) R.string.metric_time else R.string.metric_weight),
        context.getString(if (better == Better.LOWER) R.string.lower_is_better else R.string.higher_is_better)
    )
}
