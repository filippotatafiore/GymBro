package com.example.gymbro.viewmodel

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.gymbro.session.ActiveSession
import com.example.gymbro.session.RunPlan
import com.example.gymbro.session.RunTracker
import com.example.gymbro.session.TrackPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

// Run session in progress (RunSessionFragment): the path followed and the statistics in real time.
// The GPS positions and the steps are received by RunTrackingService and collected by RunTracker.
class RunSessionViewModel : ViewModel() {

    // The path and the position, updated at every new GPS position. segments: one line per part of
    // the path (a new part starts after a pause); lastLocation: the blue dot (null: no position
    // yet); showGoalReached: the goal was just reached, the window was not shown yet
    class Track(
        val segments: List<List<TrackPoint>>,
        val lastLocation: Location?,
        val showGoalReached: Boolean
    )

    // The statistics, updated at every new GPS position and every second
    data class Stats(
        val distanceKm: Double,
        val goalKm: Double?,
        val seconds: Long,
        val speedKmh: Double,
        val kcal: Double,
        val maxSpeedKmh: Double,
        val steps: Int?        // null: not counted (no step sensor, or no permission)
    ) {
        val avgSpeedKmh: Double
            get() = if (seconds > 0) distanceKm / (seconds / 3600.0) else 0.0
    }

    val track: Flow<Track> = RunTracker.changes.map {
        Track(
            segments = RunTracker.current.segments.map { it.toList() },
            lastLocation = RunTracker.lastLocation,
            showGoalReached = RunTracker.goalReached && !RunTracker.goalSeen
        )
    }

    val stats: Flow<Stats> = combine(RunTracker.changes, everySecond()) { _, _ ->
        val run = RunTracker.current
        Stats(
            distanceKm = run.distanceKm,
            goalKm = RunTracker.goalKm,
            seconds = ActiveSession.elapsedMillis() / 1000,
            speedKmh = run.currentSpeedMps(System.currentTimeMillis()) * 3.6,
            kcal = run.kcal,
            maxSpeedKmh = run.maxSpeedMps * 3.6,
            steps = RunTracker.steps
        )
    }

    // The route planned before the run (null: none)
    val plan: RunPlan?
        get() = RunTracker.plan

    // No GPS position yet: "searching the GPS"
    val waitingForGps: Boolean
        get() = RunTracker.lastLocation == null

    // The goal and the distance now (for the "goal reached" window)
    val goalKm: Double?
        get() = RunTracker.goalKm
    val distanceKm: Double
        get() = RunTracker.current.distanceKm

    // The "goal reached" window was closed (then the user chose to keep running)
    fun goalReachedSeen() {
        RunTracker.goalSeen = true
    }

    private fun everySecond(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(1000)
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { RunSessionViewModel() }
        }
    }
}
