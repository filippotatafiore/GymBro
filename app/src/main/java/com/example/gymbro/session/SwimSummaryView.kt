package com.example.gymbro.session

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.example.gymbro.R
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.formatDuration
import com.example.gymbro.formatMeters
import com.example.gymbro.formatPace100
import com.example.gymbro.network.Post

// Final data of a swim: from a saved session, a post or the swim being stopped
data class SwimSummary(
    val durationSeconds: Long,
    val poolLengthM: Double,
    val laps: Int,
    val goalLaps: Int?          // null = no goal
) {
    val meters: Double get() = SwimMath.meters(laps, poolLengthM)
}

fun TrainingSession.toSwimSummary() =
    SwimSummary(durationMillis / 1000, poolLengthM ?: SwimMath.DEFAULT_POOL_M, laps ?: 0, goalLaps)

fun Post.toSwimSummary() =
    SwimSummary(durationSeconds ?: 0, poolLengthM ?: SwimMath.DEFAULT_POOL_M, laps ?: 0, goalLaps)

// Fills res/layout/view_swim_summary.xml: the final statistics and the goal (reached or not)
object SwimSummaryView {

    fun show(view: View, summary: SwimSummary) {
        val context = view.context
        val seconds = summary.durationSeconds
        val none = context.getString(R.string.no_value)

        StatGrid(view.findViewById(R.id.swimSummaryStats), columns = 3).show(listOf(
            context.getString(R.string.stat_distance) to formatMeters(summary.meters),
            context.getString(R.string.stat_time) to formatDuration(seconds * 1000),
            context.getString(R.string.stat_laps) to summary.laps.toString(),
            context.getString(R.string.stat_pool) to formatMeters(summary.poolLengthM),
            context.getString(R.string.stat_avg_pace) to
                    (SwimMath.secondsPer100m(seconds, summary.meters)?.let { formatPace100(it) } ?: none),
            context.getString(R.string.stat_lap_time) to
                    (SwimMath.secondsPerLap(seconds, summary.laps)?.let { formatDuration(it * 1000) } ?: none),
        ))

        // e.g. "Goal 60 laps · reached" (green) / "Goal 60 laps · not reached" (red)
        val goalText = view.findViewById<TextView>(R.id.swimSummaryGoal)
        summary.goalLaps?.let { goal ->
            val reached = summary.laps >= goal
            goalText.text = context.getString(
                if (reached) R.string.goal_summary_reached else R.string.goal_summary_missed,
                context.resources.getQuantityString(R.plurals.laps_count, goal, goal)
            )
            goalText.setTextColor(context.getColor(if (reached) R.color.successGreen else R.color.errorRed))
            goalText.isVisible = true
        }
    }
}

// The swim summary inside a LinearLayout (details window)
fun LinearLayout.addSwimSummary(summary: SwimSummary) {
    val view = View.inflate(context, R.layout.view_swim_summary, null)
    addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    SwimSummaryView.show(view, summary)
}
