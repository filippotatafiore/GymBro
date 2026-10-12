package com.example.gymbro

import com.example.gymbro.session.GeoPoint
import com.example.gymbro.session.PolylineCodec
import com.example.gymbro.session.RunMath
import org.junit.Assert.assertEquals
import org.junit.Test

// Run with: right click on this file > Run 'RunMathTest'
class RunMathTest {

    @Test
    fun finalDistance_roundedToTheNearestTenthOfKm() {
        assertEquals(10.4, RunMath.roundKm(10.35), 0.0)
        assertEquals(10.3, RunMath.roundKm(10.34), 0.0)
        assertEquals(5.0, RunMath.roundKm(4.96), 0.0)
        assertEquals(0.0, RunMath.roundKm(0.04), 0.0)
    }

    @Test
    fun distance_oneDegreeOfLatitudeIsAbout111Km() {
        val meters = RunMath.distanceMeters(GeoPoint(45.0, 9.0), GeoPoint(46.0, 9.0))
        assertEquals(111_195.0, meters, 50.0)
    }

    @Test
    fun distance_milanDuomoToSanBabila() {
        // about 650 m in a straight line
        val meters = RunMath.distanceMeters(GeoPoint(45.4642, 9.1900), GeoPoint(45.4663, 9.1980))
        assertEquals(660.0, meters, 20.0)
    }

    @Test
    fun kcal_acsmRunningEquation() {
        // 10 km/h = 166.7 m/min -> VO2 = 0.2 x 166.7 + 3.5 = 36.8 ml/kg/min
        // 70 kg for 60 minutes: 36.8 x 70 / 1000 x 5 x 60 = 773 kcal
        assertEquals(36.83, RunMath.vo2(10_000.0 / 60), 0.01)
        assertEquals(773.5, RunMath.kcal(10_000.0 / 60, 60.0, 70.0), 0.5)
    }

    @Test
    fun kcal_acsmWalkingEquation() {
        // 5 km/h = 83.3 m/min -> VO2 = 0.1 x 83.3 + 3.5 = 11.8 ml/kg/min
        assertEquals(11.83, RunMath.vo2(5_000.0 / 60), 0.01)
        // standing still: oxygen used at rest
        assertEquals(3.5, RunMath.vo2(0.0), 0.0)
    }

    @Test
    fun polyline_googleExample() {
        // example of https://developers.google.com/maps/documentation/utilities/polylinealgorithm
        val points = listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453))
        val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

        assertEquals(encoded, PolylineCodec.encode(points))
        assertEquals(points, PolylineCodec.decode(encoded))
    }

    @Test
    fun polyline_invalidText_givesThePositionsReadUntilTheError() {
        assertEquals(listOf(GeoPoint(38.5, -120.2)), PolylineCodec.decode("_p~iF~ps|U_ulL"))
        assertEquals(emptyList<GeoPoint>(), PolylineCodec.decode(""))
    }
}
