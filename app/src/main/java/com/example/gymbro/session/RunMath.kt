package com.example.gymbro.session

import org.json.JSONArray
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// A position on the map: latitude and longitude in degrees
data class GeoPoint(val lat: Double, val lng: Double)

// The path followed during a run, as lines to draw on the map. More than one line when the
// GPS was interrupted (e.g. during a pause): the gap between two lines is not counted.
typealias Route = List<List<GeoPoint>>

// Calculations of the run sessions (plain Kotlin: tested in app/src/test/.../RunMathTest.kt)
object RunMath {

    private const val EARTH_RADIUS_M = 6_371_008.8
    const val KM_PER_MILE = 1.609344

    // Running speed (m/min) from which the ACSM running equation is used: 100 m/min = 6 km/h
    private const val RUNNING_FROM_M_PER_MIN = 100.0

    // Distance in meters between two positions (haversine formula, Earth as a sphere)
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceAtMost(1.0)))
    }

    // Length in meters of a line made of several positions
    fun lengthMeters(points: List<GeoPoint>): Double =
        points.zipWithNext { a, b -> distanceMeters(a, b) }.sum()

    // Final distance of a run, rounded to the nearest 0.1 km: 10.35 -> 10.4, 10.34 -> 10.3
    // (BigDecimal: 10.35 is not exact as a Double, Math.round would give 10.3)
    fun roundKm(km: Double): Double = BigDecimal.valueOf(km).setScale(1, RoundingMode.HALF_UP).toDouble()

    // Oxygen used (ml of O2 per kg of body weight per minute) moving at a speed in m/min, with the
    // metabolic equations of the American College of Sports Medicine (ACSM), on level ground:
    //   walking: VO2 = 0.1 x speed + 3.5
    //   running: VO2 = 0.2 x speed + 3.5
    // 3.5 is the oxygen used at rest. Running is used from 100 m/min (6 km/h).
    fun vo2(metersPerMinute: Double): Double =
        if (metersPerMinute < RUNNING_FROM_M_PER_MIN) 0.1 * metersPerMinute + 3.5
        else 0.2 * metersPerMinute + 3.5

    // kcal burned moving for some minutes at a speed (m/min): every liter of O2 is about 5 kcal
    fun kcal(metersPerMinute: Double, minutes: Double, weightKg: Double): Double =
        vo2(metersPerMinute) * weightKg / 1000 * 5 * minutes
}

// Google "encoded polyline" format: a line of positions written as a short text, e.g.
// "_p~iF~ps|U_ulLnnqC". Used to save routes (server and local database) and by the Google Routes API.
// https://developers.google.com/maps/documentation/utilities/polylinealgorithm
object PolylineCodec {

    fun encode(points: List<GeoPoint>): String {
        val text = StringBuilder()
        var previousLat = 0L
        var previousLng = 0L
        for (point in points) {
            val lat = Math.round(point.lat * 1e5)
            val lng = Math.round(point.lng * 1e5)
            encodeValue(lat - previousLat, text)
            encodeValue(lng - previousLng, text)
            previousLat = lat
            previousLng = lng
        }
        return text.toString()
    }

    // Invalid texts give the positions read until the error
    fun decode(encoded: String): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        var index = 0
        var lat = 0L
        var lng = 0L

        // next number of the text, null if the text ends in the middle of it
        fun nextValue(): Long? {
            var result = 0L
            var shift = 0
            while (index < encoded.length) {
                val chunk = encoded[index++].code - 63
                result = result or ((chunk and 0x1f).toLong() shl shift)
                shift += 5
                if (chunk < 0x20) return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            }
            return null
        }

        while (index < encoded.length) {
            lat += nextValue() ?: break
            lng += nextValue() ?: break
            points.add(GeoPoint(lat / 1e5, lng / 1e5))
        }
        return points
    }

    private fun encodeValue(value: Long, text: StringBuilder) {
        var rest = if (value < 0) (value shl 1).inv() else value shl 1
        while (rest >= 0x20) {
            text.append(((0x20 or (rest and 0x1f).toInt()) + 63).toChar())
            rest = rest shr 5
        }
        text.append((rest.toInt() + 63).toChar())
    }
}

// A route as JSON (server and local database): a list of lines, each one an encoded polyline,
// e.g. ["_p~iF~ps|U_ulLnnqC", "..."]
fun Route.toJson(): JSONArray = JSONArray(map { PolylineCodec.encode(it) })

fun routeFromJson(json: JSONArray): Route =
    (0 until json.length()).map { PolylineCodec.decode(json.getString(it)) }.filter { it.isNotEmpty() }
