package com.example.gymbro.session

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import androidx.core.content.edit
import com.example.gymbro.network.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

// The route planned before a run: destination, optional stop on the way, and the path between them
data class RunPlan(val destination: GeoPoint, val stop: GeoPoint?, val path: List<GeoPoint>)

// The run in progress of the logged user: goal, path followed and statistics.
// The GPS positions arrive from RunTrackingService (also with the screen off or on another page)
// and the run page shows them in real time. Everything is saved in files of the user, so the run
// is not lost when the app is closed. All the functions must be called from the main thread.
object RunTracker {

    // used only if the weight is unknown (e.g. runs restored from an older version of the app)
    const val DEFAULT_WEIGHT_KG = 70.0

    private const val KEY_GOAL_KM = "goal_km"
    private const val KEY_GOAL_SEEN = "goal_seen"          // "goal reached" window already shown
    private const val KEY_GOAL_NOTIFIED = "goal_notified"  // "goal reached" notification already sent
    private const val KEY_WEIGHT = "weight_kg"
    private const val KEY_DESTINATION = "plan_destination"
    private const val KEY_STOP = "plan_stop"
    private const val KEY_PATH = "plan_path"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val userId: Long get() = UserSession.userId ?: 0

    // Goal and plan of the run in progress (e.g. "run_3")
    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences("run_$userId", Context.MODE_PRIVATE)

    // Positions of the run in progress: one line "lat,lng,time" per position, "-" = new line
    private val trackFile: File get() = File(appContext.filesDir, "run_track_$userId.txt")

    private var track = RunTrack(DEFAULT_WEIGHT_KG)
    private var trackUserId: Long? = null

    // Increases at every change: the run page redraws when it changes
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    // The latest GPS position (also during a pause): the blue dot of the map
    var lastLocation: Location? = null
        private set

    // Path and statistics of the run in progress
    val current: RunTrack
        get() {
            if (trackUserId != userId) load()
            return track
        }

    // Goal distance, null = no goal
    val goalKm: Double?
        get() = prefs.getString(KEY_GOAL_KM, null)?.toDoubleOrNull()

    val goalReached: Boolean
        get() = goalKm?.let { current.distanceKm >= it } ?: false

    // The user already saw the "goal reached" window (then chose to keep running)
    var goalSeen: Boolean
        get() = prefs.getBoolean(KEY_GOAL_SEEN, false)
        set(value) = prefs.edit { putBoolean(KEY_GOAL_SEEN, value) }

    var goalNotified: Boolean
        get() = prefs.getBoolean(KEY_GOAL_NOTIFIED, false)
        set(value) = prefs.edit { putBoolean(KEY_GOAL_NOTIFIED, value) }

    val plan: RunPlan?
        get() {
            val destination = prefs.getString(KEY_DESTINATION, null)?.let { parsePoint(it) } ?: return null
            val stop = prefs.getString(KEY_STOP, null)?.let { parsePoint(it) }
            val path = PolylineCodec.decode(prefs.getString(KEY_PATH, "") ?: "")
            return RunPlan(destination, stop, path)
        }

    // A new run starts: the previous data are deleted
    fun start(goalKm: Double?, plan: RunPlan?, weightKg: Double) {
        clear()
        prefs.edit {
            putString(KEY_GOAL_KM, goalKm?.toString())
            putString(KEY_WEIGHT, weightKg.toString())
            if (plan != null) {
                putString(KEY_DESTINATION, plan.destination.toText())
                putString(KEY_STOP, plan.stop?.toText())
                putString(KEY_PATH, PolylineCodec.encode(plan.path))
            }
        }
        track = RunTrack(weightKg)
        trackUserId = userId
        notifyChanged()
    }

    // The GPS (re)starts: the next position starts a new line, so the time without GPS (e.g. app
    // closed) is not drawn as a straight line nor counted
    fun onTrackingStarted() {
        breakLine()
    }

    // A new GPS position (from RunTrackingService)
    fun onLocation(location: Location) {
        lastLocation = location
        if (ActiveSession.type == SessionType.RUN) {
            if (ActiveSession.isPaused) {
                // the path walked during a pause is not counted
                breakLine()
            } else {
                val point = TrackPoint(location.latitude, location.longitude, System.currentTimeMillis())
                val accuracy = if (location.hasAccuracy()) location.accuracy else null
                if (current.add(point, accuracy)) appendToFile("${point.lat},${point.lng},${point.time}")
            }
        }
        notifyChanged()
    }

    // The run ended (saved): its data are deleted
    fun clear() {
        prefs.edit { clear() }
        trackFile.delete()
        track = RunTrack(DEFAULT_WEIGHT_KG)
        trackUserId = userId
        notifyChanged()
    }

    private fun breakLine() {
        if (current.isLineOpen) {
            current.breakLine()
            appendToFile("-")
        }
    }

    // Reads the run of the logged user from its file (e.g. after the app was closed)
    private fun load() {
        val weight = prefs.getString(KEY_WEIGHT, null)?.toDoubleOrNull() ?: DEFAULT_WEIGHT_KG
        track = RunTrack(weight)
        trackUserId = userId
        if (!trackFile.exists()) return
        trackFile.forEachLine { line ->
            if (line == "-") {
                track.breakLine()
            } else {
                val parts = line.split(',')
                if (parts.size == 3) {
                    val point = TrackPoint(parts[0].toDouble(), parts[1].toDouble(), parts[2].toLong())
                    track.add(point)
                }
            }
        }
    }

    private fun appendToFile(line: String) {
        trackFile.appendText(line + "\n")
    }

    private fun notifyChanged() {
        _changes.value++
    }

    private fun GeoPoint.toText() = "$lat,$lng"

    private fun parsePoint(text: String): GeoPoint? {
        val parts = text.split(',')
        if (parts.size != 2) return null
        return GeoPoint(parts[0].toDoubleOrNull() ?: return null, parts[1].toDoubleOrNull() ?: return null)
    }
}
