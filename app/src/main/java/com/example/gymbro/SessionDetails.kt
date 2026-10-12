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
import com.example.gymbro.session.Exercise
import com.example.gymbro.session.RunSummary
import com.example.gymbro.session.addRunSummary
import com.example.gymbro.session.bindExerciseRow
import com.example.gymbro.session.close
import com.google.android.gms.maps.MapView

// Windows with all the information of a finished session (tap it in LAST SESSIONS) or of a post
// (tap it in the profile). Layout: res/layout/dialog_post_details.xml

// Run: fixed map showing the whole path followed, with the final statistics below it
fun showRunDetails(context: Context, owner: LifecycleOwner, title: String, subtitle: String, summary: RunSummary) {
    var map: MapView? = null
    val (dialog, content) = detailsDialog(context, owner, R.drawable.shoe_icon, title, subtitle) { map?.close() }
    map = content.addRunSummary(summary)
    dialog.show()
}

// Gym: training time and every exercise (sets x reps, weight)
fun showGymDetails(
    context: Context, owner: LifecycleOwner, title: String, subtitle: String,
    durationMillis: Long, exercises: List<Exercise>
) {
    val (dialog, content) = detailsDialog(context, owner, R.drawable.dumbbell_icon, title, subtitle)
    addDetailRow(content, R.string.detail_time, formatDuration(durationMillis))

    content.addView(TextView(context).apply {
        setText(R.string.detail_exercises)
        setTextColor(context.getColor(R.color.lighterBlue))
        setTypeface(typeface, Typeface.BOLD)
        textSize = 16f
        setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
    })
    val inflater = LayoutInflater.from(context)
    exercises.forEach { exercise ->
        val row = inflater.inflate(R.layout.item_exercise, content, false)
        bindExerciseRow(row, exercise)
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
