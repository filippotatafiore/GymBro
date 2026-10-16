package com.example.gymbro.session

import kotlin.math.ceil

// Calculations of the swim sessions (plain Kotlin: tested in SwimMathTest.kt). A lap is one length
// of the pool.
object SwimMath {

    const val DEFAULT_POOL_M = 25.0
    val POOL_RANGE_M = 5.0..100.0
    const val MAX_LAPS = 10_000

    // Distance swum in meters
    fun meters(laps: Int, poolM: Double): Double = laps * poolM

    // Laps needed to swim at least [meters]: rounded up, so the goal is really reached
    // (1000 m in a 25 m pool: 40 laps; 1 mile = 1609.344 m: 65 laps). The small margin ignores the
    // errors of the decimal numbers (1.1 km = 1100.0000000000002 m: 44 laps, not 45).
    fun lapsFor(meters: Double, poolM: Double): Int = ceil(meters / poolM - 1e-6).toInt()

    // Average time to swim 100 m (seconds), null if nothing was swum
    fun secondsPer100m(seconds: Long, meters: Double): Long? =
        if (meters > 0) Math.round(seconds * 100 / meters) else null

    // Average time of a lap (seconds), null if no lap was swum
    fun secondsPerLap(seconds: Long, laps: Int): Long? =
        if (laps > 0) Math.round(seconds.toDouble() / laps) else null
}
