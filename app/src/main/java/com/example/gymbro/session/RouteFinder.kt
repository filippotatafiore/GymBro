package com.example.gymbro.session

import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.resume

// Google Maps API key of the installed app (AndroidManifest.xml, from local.properties), or null
fun mapsApiKey(context: Context): String? {
    val appInfo = context.packageManager.getApplicationInfo(
        context.packageName,
        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
    )
    return appInfo.metaData?.getString("com.google.android.geo.API_KEY")?.takeIf { it.isNotBlank() }
}

// A place found with the search bar of the route planner
data class Place(val name: String, val point: GeoPoint)

// Route and distance of a run planned on the map
object RouteFinder {

    private const val TAG = "RouteFinder"

    // alongStreets = false: straight lines, because the walking route was not available
    class PlannedRoute(val distanceMeters: Double, val path: List<GeoPoint>, val alongStreets: Boolean)

    // Walking route from the position of the user to the destination, through the stop (if any):
    // its distance is the goal of the run. It is asked to the Google Routes API (Google Cloud);
    // if that is not possible (API not enabled for the key, no internet...) the distance is
    // measured along straight lines between the points.
    suspend fun walkingRoute(context: Context, origin: GeoPoint, stop: GeoPoint?, destination: GeoPoint): PlannedRoute {
        try {
            return requestWalkingRoute(context, origin, stop, destination)
        } catch (e: CancellationException) {
            throw e  // the route is no longer needed (e.g. another destination was chosen)
        } catch (e: Exception) {
            Log.w(TAG, "Walking route not available, using straight lines: ${e.message}")
        }
        val path = listOfNotNull(origin, stop, destination)
        return PlannedRoute(RunMath.lengthMeters(path), path, alongStreets = false)
    }

    // Google Routes API: https://developers.google.com/maps/documentation/routes/compute_route_directions
    private suspend fun requestWalkingRoute(
        context: Context, origin: GeoPoint, stop: GeoPoint?, destination: GeoPoint
    ): PlannedRoute = withContext(Dispatchers.IO) {
        val key = mapsApiKey(context) ?: throw IOException("Google Maps API key missing")
        val body = JSONObject()
            .put("origin", waypoint(origin))
            .put("destination", waypoint(destination))
            .put("travelMode", "WALK")
        if (stop != null) body.put("intermediates", JSONArray().put(waypoint(stop)))

        val connection = URL("https://routes.googleapis.com/directions/v2:computeRoutes").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Goog-Api-Key", key)
            // only the needed fields of the answer
            connection.setRequestProperty("X-Goog-FieldMask", "routes.distanceMeters,routes.polyline.encodedPolyline")
            // the key may be restricted to this app: Google checks its package name and signature
            connection.setRequestProperty("X-Android-Package", context.packageName)
            signatureSha1(context)?.let { connection.setRequestProperty("X-Android-Cert", it) }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().decodeToString() }.orEmpty()
            if (code !in 200..299) throw IOException("Routes API error $code: $text")

            val route = JSONObject(text).optJSONArray("routes")?.optJSONObject(0)
                ?: throw IOException("No walking route found")
            val path = PolylineCodec.decode(route.getJSONObject("polyline").getString("encodedPolyline"))
            PlannedRoute(route.optDouble("distanceMeters", 0.0), path, alongStreets = true)
        } finally {
            connection.disconnect()
        }
    }

    private fun waypoint(point: GeoPoint) = JSONObject().put(
        "location", JSONObject().put("latLng", JSONObject().put("latitude", point.lat).put("longitude", point.lng))
    )

    // SHA-1 of the certificate the app is signed with, e.g. "BB0D...A1" (as in the API key restrictions)
    private fun signatureSha1(context: Context): String? {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        )
        val certificate = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-1").digest(certificate.toByteArray())
            .joinToString("") { "%02X".format(it) }
    }

    // Places matching a name or address (Android Geocoder, which uses the Google servers).
    // First near the user (about 50 km around), then anywhere.
    suspend fun searchPlaces(context: Context, query: String, near: GeoPoint?): List<Place> {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(context)
        if (near != null) {
            val nearby = geocode { listener ->
                geocoder.getFromLocationName(query, 5, near.lat - 0.5, near.lng - 0.5, near.lat + 0.5, near.lng + 0.5, listener)
            }
            if (nearby.isNotEmpty()) return nearby
        }
        return geocode { listener -> geocoder.getFromLocationName(query, 5, listener) }
    }

    // Waits for the answer of the Geocoder (it arrives later, in a listener)
    private suspend fun geocode(search: (Geocoder.GeocodeListener) -> Unit): List<Place> =
        suspendCancellableCoroutine { continuation ->
            search(object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    val places = addresses.map { Place(describe(it), GeoPoint(it.latitude, it.longitude)) }
                    if (continuation.isActive) continuation.resume(places)
                }

                override fun onError(errorMessage: String?) {
                    if (continuation.isActive) continuation.resume(emptyList())
                }
            })
        }

    // e.g. "Piazza del Duomo, 20122 Milano MI, Italy"
    private fun describe(address: Address): String =
        address.getAddressLine(0) ?: address.featureName ?: "%.5f, %.5f".format(address.latitude, address.longitude)
}
