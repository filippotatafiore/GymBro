package com.example.gymbro

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.LifecycleOwner
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.session.Exercise
import com.example.gymbro.session.RunSummary
import com.example.gymbro.session.SessionType
import com.example.gymbro.session.addRunSummary
import com.example.gymbro.session.bindExerciseRow
import com.example.gymbro.session.close
import com.example.gymbro.session.toRunSummary
import com.google.android.gms.maps.MapView

// Windows with all the information of a finished session (tap it in LAST SESSIONS) or of a post
// (tap it in the profile). Layout: res/layout/dialog_post_details.xml

// e.g. "Run · 5.3 km", "Gym · Day 1 · Push", "Gym" (lists of sessions: LAST SESSIONS, calendar)
fun TrainingSession.displayTitle(context: Context): String = when (type) {
    SessionType.RUN -> context.getString(R.string.run_post_title, formatDistance(distanceKm ?: 0.0))
    SessionType.GYM -> planDayName?.let { context.getString(R.string.gym_day_title, it) } ?: context.getString(type.label)
}

// Details of a saved session (tap it in LAST SESSIONS or in the calendar)
fun showSessionDetails(context: Context, owner: LifecycleOwner, session: TrainingSession) {
    val date = formatDateTime(session.startTime)
    when (session.type) {
        SessionType.RUN -> showRunDetails(context, owner, session.displayTitle(context), date, session.toRunSummary())
        SessionType.GYM -> showGymDetails(
            context, owner, session.displayTitle(context), date,
            session.durationMillis, session.exercises.orEmpty(), session.planName
        )
    }
}

// Run: fixed map showing the whole path followed, with the final statistics below it
fun showRunDetails(context: Context, owner: LifecycleOwner, title: String, subtitle: String, summary: RunSummary) {
    var map: MapView? = null
    val (dialog, content) = detailsDialog(context, owner, R.drawable.shoe_icon, title, subtitle) { map?.close() }
    map = content.addRunSummary(summary)
    dialog.show()
}

// Gym: training time, workout plan followed (if any), how many exercises were completed, and every
// exercise as it was done in that session (sets x reps, weight, rest, notes; dimmed if not completed)
fun showGymDetails(
    context: Context, owner: LifecycleOwner, title: String, subtitle: String,
    durationMillis: Long, exercises: List<Exercise>, planName: String? = null
) {
    val (dialog, content) = detailsDialog(context, owner, R.drawable.dumbbell_icon, title, subtitle)
    addDetailRow(content, R.string.detail_time, formatDuration(durationMillis))
    planName?.let { addDetailRow(content, R.string.detail_plan, it) }
    if (exercises.isNotEmpty()) {
        addDetailRow(content, R.string.detail_completed,
            context.getString(R.string.completed_count, exercises.count { it.done }, exercises.size))
    }

    content.addView(TextView(context).apply {
        setText(R.string.detail_exercises)
        setTextColor(context.themeColor(R.attr.accentTextColor))
        setTypeface(typeface, Typeface.BOLD)
        textSize = 16f
        setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
    })
    val inflater = LayoutInflater.from(context)
    exercises.forEach { exercise ->
        val row = inflater.inflate(R.layout.item_exercise, content, false)
        bindExerciseRow(row, exercise)
        row.findViewById<View>(R.id.exerciseDoneIcon).visibility = if (exercise.done) View.VISIBLE else View.INVISIBLE
        if (!exercise.done) row.alpha = 0.5f
        row.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = 0
            marginEnd = 0
        }
        content.addView(row)
    }
    dialog.show()
}

// The details window with icon, title and subtitle: the content is added to the returned layout.
// onDismiss: called when the window is closed.
fun detailsDialog(
    context: Context, owner: LifecycleOwner, @DrawableRes icon: Int, title: String, subtitle: String,
    onDismiss: () -> Unit = {}
): Pair<Dialog, LinearLayout> {
    val dialog = createModal(context, R.layout.dialog_post_details, owner, onDismiss)
    dialog.findViewById<ImageView>(R.id.detailsIcon).setImageResource(icon)
    dialog.findViewById<TextView>(R.id.detailsTitle).text = title
    dialog.findViewById<TextView>(R.id.detailsSubtitle).text = subtitle
    dialog.findViewById<View>(R.id.closeButton).setOnClickListener { dialog.dismiss() }
    return dialog to dialog.findViewById(R.id.detailsContent)
}

// A "label - value" line (res/layout/item_detail_row.xml)
fun addDetailRow(content: LinearLayout, @StringRes label: Int, value: String) {
    val row = LayoutInflater.from(content.context).inflate(R.layout.item_detail_row, content, false)
    row.findViewById<TextView>(R.id.detailLabel).setText(label)
    row.findViewById<TextView>(R.id.detailValue).text = value
    content.addView(row)
}
