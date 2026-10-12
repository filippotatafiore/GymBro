package com.example.gymbro.session

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import com.example.gymbro.R
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.formatDistance
import com.example.gymbro.formatDuration
import com.example.gymbro.formatKcal
import com.example.gymbro.formatPace
import com.example.gymbro.formatSpeed
import com.example.gymbro.network.Post
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.MarkerOptions

// Final data of a run: from a saved session, a post or the run being stopped
data class RunSummary(
    val durationSeconds: Long,
    val distanceKm: Double?,    // rounded to 0.1 km
    val kcal: Double?,
    val maxSpeedKmh: Double?,
    val goalKm: Double?,        // null = no goal
    val route: Route?           // null = not recorded (runs saved before the routes existed)
)

fun TrainingSession.toRunSummary() =
    RunSummary(durationMillis / 1000, distanceKm, kcal, maxSpeedKmh, goalKm, route)

fun Post.toRunSummary() =
    RunSummary(durationSeconds ?: 0, distanceKm, kcal, maxSpeedKmh, goalKm, route)

// Fills res/layout/view_run_summary.xml: a fixed map centered and zoomed on the whole path
// followed, with the final statistics below it
object RunSummaryView {

    private const val NONE = "—"

    // Returns the map (null if there is no route): call close() on it when the window is closed
    fun show(view: View, summary: RunSummary): MapView? {
        val context = view.context
        val km = summary.distanceKm
        val seconds = summary.durationSeconds

        StatGrid(view.findViewById(R.id.summaryStats), columns = 3).show(listOf(
            context.getString(R.string.stat_distance) to (km?.let { formatDistance(it) } ?: NONE),
            context.getString(R.string.stat_time) to formatDuration(seconds * 1000),
            context.getString(R.string.stat_avg_pace) to
                    (if (km != null && km > 0) formatPace(seconds, km) else NONE),
            context.getString(R.string.stat_avg_speed) to
                    (if (km != null && seconds > 0) formatSpeed(km / (seconds / 3600.0)) else NONE),
            context.getString(R.string.stat_max_speed) to (summary.maxSpeedKmh?.let { formatSpeed(it) } ?: NONE),
            context.getString(R.string.stat_calories) to (summary.kcal?.let { formatKcal(it) } ?: NONE),
        ))

        // e.g. "Goal 5 km · reached" (green) / "Goal 5 km · not reached" (red)
        val goalText = view.findViewById<TextView>(R.id.summaryGoal)
        summary.goalKm?.let { goal ->
            val reached = (km ?: 0.0) >= goal
            goalText.text = context.getString(
                if (reached) R.string.goal_summary_reached else R.string.goal_summary_missed, formatDistance(goal)
            )
            goalText.setTextColor(context.getColor(if (reached) R.color.successGreen else R.color.errorRed))
            goalText.isVisible = true
        }

        val container = view.findViewById<FrameLayout>(R.id.summaryMapContainer)
        val route = summary.route.orEmpty().filter { it.isNotEmpty() }
        if (route.isEmpty()) {
            view.findViewById<View>(R.id.noRouteText).isVisible = true
            return null
        }
        return showMap(container, route)
    }

    // Fixed map: the gestures are disabled. (Not a "lite mode" map, the static image of Google Maps:
    // it cannot be dark.) A MapView must be told when it starts and stops (onCreate... and close()).
    private fun showMap(container: FrameLayout, route: Route): MapView {
        val context = container.context
        val options = GoogleMapOptions().mapToolbarEnabled(false)
        val mapView = MapView(context, options)
        container.addView(mapView, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()

        mapView.getMapAsync { map ->
            map.useDarkTheme(context)
            map.uiSettings.setAllGesturesEnabled(false)
            map.isBuildingsEnabled = false
            map.setOnMarkerClickListener { true }  // no info windows on the markers

            val lines = route.map { line -> line.map { it.toLatLng() } }
            lines.filter { it.size >= 2 }.forEach { map.addPolyline(trackLine(context, it)) }

            val points = lines.flatten()
            map.addMarker(MarkerOptions().position(points.first()).anchor(0.5f, 0.5f)
                .icon(dotMarker(context, R.color.successGreen)).title(context.getString(R.string.marker_start)))
            map.addMarker(MarkerOptions().position(points.last()).anchor(0.5f, 0.5f)
                .icon(dotMarker(context, R.color.errorRed)).title(context.getString(R.string.marker_end)))

            // the size of the map is needed to fit the path in it
            container.doOnLayout {
                val padding = (28 * context.resources.displayMetrics.density).toInt()
                map.moveCamera(cameraShowing(points, it.width, it.height, padding))
            }
        }
        return mapView
    }
}

// Stops a map shown by RunSummaryView (when its window is closed)
fun MapView.close() {
    onPause()
    onStop()
    onDestroy()
}

// The run summary inside a LinearLayout (details window): returns the map, as RunSummaryView.show
fun LinearLayout.addRunSummary(summary: RunSummary): MapView? {
    val view = View.inflate(context, R.layout.view_run_summary, null)
    addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return RunSummaryView.show(view, summary)
}
