package com.example.gymbro.profile

import android.content.Context
import androidx.annotation.DrawableRes
import com.example.gymbro.R
import com.example.gymbro.formatDistance
import com.example.gymbro.formatDuration
import com.example.gymbro.formatWeight
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

// e.g. "Run · 5 km", "Gym session", "Deadlift"
fun Post.displayTitle(context: Context): String = when (kind) {
    PostKind.RUN -> context.getString(R.string.run_post_title, formatDistance(distanceKm ?: 0.0))
    PostKind.GYM -> context.getString(R.string.gym_post_title)
    PostKind.MANUAL -> title
}

// e.g. "150 kg", "00:45" (manual results), "42:10" (training time of a session)
fun Post.displayValue(): String = when (kind) {
    PostKind.MANUAL ->
        if (metric == Metric.TIME) formatDuration(((amount ?: 0.0) * 1000).toLong())
        else formatWeight(amount ?: 0.0)
    PostKind.GYM, PostKind.RUN -> formatDuration((durationSeconds ?: 0L) * 1000)
}
