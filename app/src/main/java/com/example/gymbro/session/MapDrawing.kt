package com.example.gymbro.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.annotation.ColorRes
import com.example.gymbro.Appearance
import com.example.gymbro.R
import com.example.gymbro.themeColor
import com.google.android.gms.maps.CameraUpdate
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapColorScheme
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.RoundCap

// How the runs are drawn on the Google maps (run page, run summary, route planner)

// The way Google Maps draws the maps on this phone, known when the app starts (GymBroApp)
var mapsRenderer: MapsInitializer.Renderer? = null

// Map in the theme of the app: dark or light (see Appearance). The dark color scheme of Google Maps
// needs its "latest" renderer: with the "legacy" one (on some phones and emulators) a dark style is
// used instead (res/raw/map_dark_style.json).
fun GoogleMap.useAppTheme(context: Context) {
    if (!Appearance.isDark(context)) {
        mapColorScheme = MapColorScheme.LIGHT
    } else if (mapsRenderer == MapsInitializer.Renderer.LEGACY) {
        setMapStyle(MapStyleOptions.loadRawResourceStyle(context, R.raw.map_dark_style))
    } else {
        mapColorScheme = MapColorScheme.DARK
    }
}

fun GeoPoint.toLatLng() = LatLng(lat, lng)

fun LatLng.toGeoPoint() = GeoPoint(latitude, longitude)

private fun Context.dp(value: Float) = value * resources.displayMetrics.density

// Path followed: thick line in the favorite color, with rounded ends
fun trackLine(context: Context, points: List<LatLng>): PolylineOptions = PolylineOptions()
    .addAll(points)
    .color(context.themeColor(R.attr.accentColor))
    .width(context.dp(5f))
    .jointType(JointType.ROUND)
    .startCap(RoundCap())
    .endCap(RoundCap())

// Route planned before the run: dashed line, white on the dark map (dark on the light one)
fun plannedLine(context: Context, points: List<LatLng>): PolylineOptions = PolylineOptions()
    .addAll(points)
    .color(context.getColor(R.color.plannedRoute))
    .width(context.dp(4f))
    .pattern(listOf(Dash(context.dp(10f)), Gap(context.dp(8f))))
    .jointType(JointType.ROUND)

// Round marker with a white border (start and end of a run). To be used only once the map is ready.
fun dotMarker(context: Context, @ColorRes color: Int): BitmapDescriptor {
    val size = context.dp(18f).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    paint.color = context.getColor(color)
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - context.dp(3f), paint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

// Camera showing all the points in a map of width x height pixels (with a margin around them)
fun cameraShowing(points: List<LatLng>, width: Int, height: Int, paddingPx: Int): CameraUpdate {
    val bounds = LatLngBounds.builder().apply { points.forEach { include(it) } }.build()
    val sizeMeters = RunMath.distanceMeters(bounds.southwest.toGeoPoint(), bounds.northeast.toGeoPoint())
    // a few meters (e.g. a single point): the bounds would zoom in too much
    return if (sizeMeters < 50) CameraUpdateFactory.newLatLngZoom(bounds.center, 16f)
    // (a margin bigger than the map is not accepted)
    else CameraUpdateFactory.newLatLngBounds(bounds, width, height, minOf(paddingPx, width / 4, height / 4))
}
