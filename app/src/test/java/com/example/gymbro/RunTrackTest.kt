package com.example.gymbro

import com.example.gymbro.session.RunTrack
import com.example.gymbro.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Run with: right click on this file > Run 'RunTrackTest'
class RunTrackTest {

    // 0.0001 degrees of latitude = 11.1 m
    private val step = 0.0001
    private val metersPerStep = 11.12

    // A position going north, one step every [secondsPerStep]
    private fun point(steps: Int, secondsPerStep: Int = 4, lat0: Double = 45.0) =
        TrackPoint(lat0 + steps * step, 9.0, steps * secondsPerStep * 1000L)

    @Test
    fun distance_sumOfTheSteps() {
        val track = RunTrack(70.0)
        (0..100).forEach { track.add(point(it)) }

        assertEquals(100 * metersPerStep, track.distanceMeters, 5.0)
        assertEquals(1, track.segments.size)
        assertEquals(101, track.segments[0].size)
    }

    @Test
    fun impreciseOrTooClosePositions_areIgnored() {
        val track = RunTrack(70.0)
        assertTrue(track.add(TrackPoint(45.0, 9.0, 0)))
        // 2 m away: the GPS moving by itself while standing still
        assertFalse(track.add(TrackPoint(45.0 + 0.00002, 9.0, 2000)))
        // precise only to 40 m
        assertFalse(track.add(TrackPoint(45.001, 9.0, 4000), accuracyMeters = 40f))

        assertEquals(0.0, track.distanceMeters, 0.0)
    }

    @Test
    fun afterABreak_theGapIsNotCounted() {
        val track = RunTrack(70.0)
        (0..10).forEach { track.add(point(it)) }
        track.breakLine()
        // 1 km further (walked during a pause), then 10 more steps
        (100..110).forEach { track.add(point(it)) }

        assertEquals(20 * metersPerStep, track.distanceMeters, 2.0)
        assertEquals(2, track.segments.size)
    }

    @Test
    fun speed_currentAndMax() {
        val track = RunTrack(70.0)
        // 11.12 m every 4 s = 2.78 m/s (10 km/h)
        (0..20).forEach { track.add(point(it)) }
        val now = 20 * 4000L

        assertEquals(2.78, track.currentSpeedMps(now), 0.01)
        assertEquals(2.78, track.maxSpeedMps, 0.01)
        // no new positions for 10 s: standing still
        assertEquals(0.0, track.currentSpeedMps(now + 10_000), 0.0)
    }

    @Test
    fun kcal_tenKmAtTenKmPerHour_for70Kg() {
        val track = RunTrack(70.0)
        // 10 km at 10 km/h: 900 steps of 11.12 m every 4 s (1 hour)
        (0..900).forEach { track.add(point(it)) }

        assertEquals(10.0, track.distanceKm, 0.01)
        assertEquals(773.0, track.kcal, 5.0)
    }
}
