package com.example.gymbro

import com.example.gymbro.session.RunMath
import com.example.gymbro.session.SwimMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Run with: right click on this file > Run 'SwimMathTest'
class SwimMathTest {

    @Test
    fun distance_lapsTimesPoolLength() {
        assertEquals(1500.0, SwimMath.meters(60, 25.0), 0.0)
        assertEquals(500.0, SwimMath.meters(10, 50.0), 0.0)
    }

    @Test
    fun goalDistance_roundedUpToWholeLaps() {
        assertEquals(40, SwimMath.lapsFor(1000.0, 25.0))
        assertEquals(20, SwimMath.lapsFor(1000.0, 50.0))
        // 1 mile = 1609.344 m: 64.37 laps -> 65 (64 would be less than a mile)
        assertEquals(65, SwimMath.lapsFor(RunMath.KM_PER_MILE * 1000, 25.0))
        // 1.1 km is 1100.0000000000002 m as a decimal number: still 44 laps
        assertEquals(44, SwimMath.lapsFor(1.1 * 1000, 25.0))
        assertEquals(31, SwimMath.lapsFor(1030.0, 33.5))
        assertEquals(1, SwimMath.lapsFor(10.0, 25.0))
    }

    @Test
    fun pace() {
        // 1500 m in 30 minutes: 2:00 every 100 m, 30 s every lap of 25 m
        assertEquals(120L, SwimMath.secondsPer100m(1800, 1500.0))
        assertEquals(30L, SwimMath.secondsPerLap(1800, 60))
        assertNull(SwimMath.secondsPer100m(60, 0.0))
        assertNull(SwimMath.secondsPerLap(60, 0))
    }

    @Test
    fun runSteps_cadenceAndStride() {
        // 10 km in 50 minutes with 8000 steps: 160 steps per minute, 1.25 m per step
        assertEquals(160.0, RunMath.cadence(8000, 3000)!!, 0.001)
        assertEquals(1.25, RunMath.strideMeters(10.0, 8000)!!, 0.001)
        assertNull(RunMath.cadence(0, 3000))
        assertNull(RunMath.strideMeters(0.0, 8000))
    }
}
