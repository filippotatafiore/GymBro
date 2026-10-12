package com.example.gymbro.session

import kotlin.math.max

// A GPS position of the run, with the time it was received (milliseconds since 1970)
data class TrackPoint(val lat: Double, val lng: Double, val time: Long) {
    val geo: GeoPoint get() = GeoPoint(lat, lng)
}

// The path followed during a run and its statistics, updated at every GPS position.
// Plain Kotlin (no Android): tested in app/src/test/.../RunTrackTest.kt
class RunTrack(private val weightKg: Double) {

    private val lines = mutableListOf<MutableList<TrackPoint>>()
    private var startNewLine = true

    var distanceMeters = 0.0
        private set

    // Calories burned, estimated with the ACSM equations (see RunMath.kcal)
    var kcal = 0.0
        private set

    var maxSpeedMps = 0.0
        private set

    val segments: List<List<TrackPoint>> get() = lines

    val route: Route get() = lines.map { line -> line.map { it.geo } }

    val distanceKm: Double get() = distanceMeters / 1000

    // Adds a GPS position to the track. Returns false if it was ignored:
    // - not precise enough (accuracy = radius of uncertainty of the GPS, in meters)
    // - too close to the previous one: standing still, the GPS "moves" by a few meters by itself
    fun add(point: TrackPoint, accuracyMeters: Float? = null): Boolean {
        if (accuracyMeters != null && accuracyMeters > MAX_ACCURACY_M) return false

        val line = lines.lastOrNull()
        val last = line?.lastOrNull()
        if (startNewLine || line == null || last == null) {
            lines.add(mutableListOf(point))
            startNewLine = false
            return true
        }

        val meters = RunMath.distanceMeters(last.geo, point.geo)
        val seconds = (point.time - last.time) / 1000.0
        if (meters < max(MIN_STEP_M, (accuracyMeters ?: 0f) / 2.0) || seconds <= 0) return false

        line.add(point)
        distanceMeters += meters
        kcal += RunMath.kcal(metersPerMinute = meters / seconds * 60, minutes = seconds / 60, weightKg = weightKg)
        speedEndingAt(line, line.lastIndex)?.let { maxSpeedMps = max(maxSpeedMps, it) }
        return true
    }

    // The next position starts a new line: the path between the last position and the next one
    // (e.g. walked during a pause) is not drawn nor counted
    fun breakLine() {
        startNewLine = true
    }

    // True if the next position continues the current line
    val isLineOpen: Boolean get() = !startNewLine && lines.isNotEmpty()

    // Current speed (m/s): the speed of the last seconds, 0 when standing still
    fun currentSpeedMps(now: Long): Double {
        val line = lines.lastOrNull() ?: return 0.0
        val last = line.lastOrNull() ?: return 0.0
        if (startNewLine || now - last.time > STILL_AFTER_MS) return 0.0
        return speedEndingAt(line, line.lastIndex) ?: 0.0
    }

    // Average speed in the SPEED_WINDOW_MS before line[end] (less sensitive to GPS errors than
    // the speed between two positions); null if the line is too short to know it
    private fun speedEndingAt(line: List<TrackPoint>, end: Int): Double? {
        var start = end
        var meters = 0.0
        while (start > 0 && line[end].time - line[start].time < SPEED_WINDOW_MS) {
            meters += RunMath.distanceMeters(line[start - 1].geo, line[start].geo)
            start--
        }
        val seconds = (line[end].time - line[start].time) / 1000.0
        return if (seconds >= MIN_SPEED_SECONDS) meters / seconds else null
    }

    companion object {
        const val MAX_ACCURACY_M = 25f
        const val MIN_STEP_M = 5.0
        const val SPEED_WINDOW_MS = 10_000L
        const val MIN_SPEED_SECONDS = 4.0
        const val STILL_AFTER_MS = 8_000L
    }
}
