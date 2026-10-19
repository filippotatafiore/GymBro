package com.example.gymbro.repository

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.UserSession
import com.example.gymbro.session.GeoPoint
import com.example.gymbro.session.RunMath
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// A place found with the search bar of the route planner
data class Place(val name: String, val point: GeoPoint)

// Route and distance of a run planned on the map. alongStreets = false: straight lines, because
// the walking route was not available (problem: why). provider and attribution: the service that
// computed the walking route and its credits.
class PlannedRoute(
    val distanceMeters: Double,
    val path: List<GeoPoint>,
    val alongStreets: Boolean,
    val problem: ApiException? = null,
    val provider: String? = null,
    val attribution: String? = null
)

// Routes and places of the route planner, before a run (Repository pattern)
interface RouteRepository {

    // Walking route from the position of the user to the destination, through the stop (if any):
    // its distance is the goal of the run. If it is not available, straight lines (never an error).
    suspend fun walkingRoute(origin: GeoPoint, stop: GeoPoint?, destination: GeoPoint): PlannedRoute

    // Places matching a name or address: first near the user (about 50 km around), then anywhere
    suspend fun searchPlaces(query: String, near: GeoPoint?): List<Place>
}

// context: of the app (Geocoder)
class RouteRepositoryImpl(private val context: Context) : RouteRepository {

    // Computed by the GymBro server with OpenRouteService (free, OpenStreetMap data) or the Google
    // Routes API: their keys stay on the server. If that is not possible (server not reachable, keys
    // not set up...) the distance is measured along straight lines.
    override suspend fun walkingRoute(origin: GeoPoint, stop: GeoPoint?, destination: GeoPoint): PlannedRoute {
        val problem = try {
            val token = UserSession.token ?: throw ApiException(401, "Login required")
            val route = ApiClient.walkingRoute(token, origin, stop, destination)
            return PlannedRoute(
                route.distanceMeters, route.path, alongStreets = true,
                provider = route.provider, attribution = route.attribution
            )
        } catch (e: ApiException) {
            Log.w(TAG, "Walking route not available, using straight lines: ${e.message}")
            e
        }
        val path = listOfNotNull(origin, stop, destination)
        return PlannedRoute(RunMath.lengthMeters(path), path, alongStreets = false, problem = problem)
    }

    // Android Geocoder, which uses the Google servers
    override suspend fun searchPlaces(query: String, near: GeoPoint?): List<Place> {
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

    companion object {
        private const val TAG = "RouteRepository"
    }
}
