package com.example.gymbro.session

import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.gymbro.R
import com.example.gymbro.enableDarkEdgeToEdge
import com.example.gymbro.formatLiveDistance
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Page to plan the route of a run, before it starts: the user chooses the destination (and, if
// wanted, a stop on the way) by tapping the map or searching a place by name. The goal of the run
// is the walking distance from the current position to the destination, through the stop.
// Opened by RunSetup; it gives back the goal and the route (readResult).
class RoutePlannerActivity : FragmentActivity(), OnMapReadyCallback {

    private lateinit var fusedLocation: FusedLocationProviderClient
    private var map: GoogleMap? = null

    private var origin: GeoPoint? = null        // current position of the user
    private var destination: GeoPoint? = null
    private var stop: GeoPoint? = null
    private var plannedRoute: RouteFinder.PlannedRoute? = null

    private var routeJob: Job? = null
    private var searchJob: Job? = null

    private var destinationMarker: Marker? = null
    private var stopMarker: Marker? = null
    private var routeLine: Polyline? = null

    private lateinit var searchInput: EditText
    private lateinit var placeResults: LinearLayout
    private lateinit var status: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var hint: TextView
    private lateinit var distanceText: TextView
    private lateinit var routeKindText: TextView
    private lateinit var removeStopButton: Button
    private lateinit var startButton: Button

    // The first precise position of the user is the start of the route
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { setOrigin(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()
        setContentView(R.layout.activity_route_planner)
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)

        searchInput = findViewById(R.id.placeSearchInput)
        placeResults = findViewById(R.id.placeResults)
        status = findViewById(R.id.plannerStatus)
        modeGroup = findViewById(R.id.pointModeGroup)
        hint = findViewById(R.id.plannerHint)
        distanceText = findViewById(R.id.plannedDistance)
        routeKindText = findViewById(R.id.plannedRouteKind)
        removeStopButton = findViewById(R.id.removeStopButton)
        startButton = findViewById(R.id.plannerStartButton)

        findViewById<View>(R.id.plannerBackButton).setOnClickListener { finish() }

        // search: keyboard "search" key or the magnifier button
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) search()
            actionId == EditorInfo.IME_ACTION_SEARCH
        }
        findViewById<View>(R.id.placeSearchButton).setOnClickListener { search() }

        // what a tap on the map (or a place found) sets: the destination or the stop
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            hint.setText(if (checkedId == R.id.modeStop) R.string.planner_hint_stop else R.string.planner_hint_destination)
        }
        removeStopButton.setOnClickListener {
            stop = null
            showPoints()
            updateRoute()
        }
        startButton.setOnClickListener { finishWithRoute() }

        status.setText(R.string.searching_position)
        status.isVisible = true

        val mapFragment = supportFragmentManager.findFragmentById(R.id.plannerMap) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onStart() {
        super.onStart()
        if (origin == null) startLocationUpdates()
    }

    override fun onStop() {
        super.onStop()
        fusedLocation.removeLocationUpdates(locationCallback)
    }

    @SuppressLint("MissingPermission")  // the permission is asked by RunSetup before opening this page
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        googleMap.useDarkTheme(this)
        googleMap.isBuildingsEnabled = false  // 3D buildings would hide parts of the route drawn
        if (RunTrackingService.hasLocationPermission(this)) googleMap.isMyLocationEnabled = true
        googleMap.setOnMapClickListener { setPoint(it.toGeoPoint()) }
        origin?.let { googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(it.toLatLng(), 15f)) }
        showPoints()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!RunTrackingService.hasLocationPermission(this)) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun setOrigin(location: Location) {
        if (origin != null) return
        origin = GeoPoint(location.latitude, location.longitude)
        fusedLocation.removeLocationUpdates(locationCallback)
        status.isVisible = false
        if (destination == null) map?.animateCamera(CameraUpdateFactory.newLatLngZoom(origin!!.toLatLng(), 15f))
        updateRoute()
    }

    // A tap on the map or a place found: sets the destination or the stop
    private fun setPoint(point: GeoPoint) {
        placeResults.isVisible = false
        if (modeGroup.checkedRadioButtonId == R.id.modeStop) stop = point else destination = point
        showPoints()
        updateRoute()
    }

    private fun showPoints() {
        val map = map ?: return
        destinationMarker?.remove()
        stopMarker?.remove()
        destinationMarker = destination?.let {
            map.addMarker(MarkerOptions().position(it.toLatLng()).title(getString(R.string.destination)))
        }
        stopMarker = stop?.let {
            map.addMarker(MarkerOptions().position(it.toLatLng()).title(getString(R.string.stop_marker))
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)))
        }
        removeStopButton.isVisible = stop != null
    }

    // Goal distance: walking route from the current position to the destination, through the stop
    private fun updateRoute() {
        routeJob?.cancel()
        plannedRoute = null
        routeLine?.remove()
        routeLine = null
        setStartEnabled(false)

        val origin = origin
        val destination = destination
        distanceText.isVisible = destination != null
        routeKindText.isVisible = false
        if (destination == null || origin == null) {
            if (destination != null) distanceText.setText(R.string.searching_position)
            return
        }

        distanceText.setText(R.string.calculating_route)
        val stop = stop
        routeJob = lifecycleScope.launch {
            val route = RouteFinder.walkingRoute(this@RoutePlannerActivity, origin, stop, destination)
            plannedRoute = route

            distanceText.text = getString(
                R.string.planned_goal,
                getString(R.string.live_distance_km, formatLiveDistance(route.distanceMeters / 1000))
            )
            // straight lines: also why the walking route is not available (e.g. key not set up on the server)
            // walking route: also the service used and the credits it requires (OpenStreetMap)
            routeKindText.text =
                if (route.alongStreets) {
                    listOfNotNull(getString(R.string.route_along_streets, route.provider.orEmpty()), route.attribution)
                        .joinToString("\n")
                } else {
                    getString(R.string.route_straight_line, route.problem.orEmpty())
                }
            routeKindText.isVisible = true
            setStartEnabled(route.distanceMeters >= 100)

            val map = map ?: return@launch
            val points = route.path.map { it.toLatLng() }
            routeLine = map.addPolyline(plannedLine(this@RoutePlannerActivity, points))
            // the whole route visible
            val mapView = supportFragmentManager.findFragmentById(R.id.plannerMap)?.view ?: return@launch
            val padding = (48 * resources.displayMetrics.density).toInt()
            map.animateCamera(cameraShowing(points + origin.toLatLng(), mapView.width, mapView.height, padding))
        }
    }

    private fun setStartEnabled(enabled: Boolean) {
        startButton.isEnabled = enabled
        startButton.alpha = if (enabled) 1f else 0.5f
    }

    // Places matching the text of the search bar, shown in a list over the map
    private fun search() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(searchInput.windowToken, 0)

        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val places = RouteFinder.searchPlaces(this@RoutePlannerActivity, query, origin)
            placeResults.removeAllViews()
            if (places.isEmpty()) {
                val row = layoutInflater.inflate(R.layout.item_place, placeResults, false) as TextView
                row.setText(R.string.no_places_found)
                row.setTextColor(getColor(R.color.textSecondary))
                placeResults.addView(row)
            }
            places.forEach { place ->
                val row = layoutInflater.inflate(R.layout.item_place, placeResults, false) as TextView
                row.text = place.name
                row.setOnClickListener {
                    setPoint(place.point)
                    map?.animateCamera(CameraUpdateFactory.newLatLngZoom(place.point.toLatLng(), 15f))
                }
                placeResults.addView(row)
            }
            placeResults.isVisible = true
        }
    }

    // Back to RunSetup with the goal (km) and the route
    private fun finishWithRoute() {
        val route = plannedRoute ?: return
        val destination = destination ?: return
        val data = Intent()
            .putExtra(EXTRA_GOAL_KM, route.distanceMeters / 1000)
            .putExtra(EXTRA_DESTINATION, doubleArrayOf(destination.lat, destination.lng))
            .putExtra(EXTRA_PATH, PolylineCodec.encode(route.path))
        stop?.let { data.putExtra(EXTRA_STOP, doubleArrayOf(it.lat, it.lng)) }
        setResult(RESULT_OK, data)
        finish()
    }

    companion object {
        private const val EXTRA_GOAL_KM = "goal_km"
        private const val EXTRA_DESTINATION = "destination"
        private const val EXTRA_STOP = "stop"
        private const val EXTRA_PATH = "path"

        // Goal (km) and route given back by this page
        fun readResult(data: Intent): Pair<Double, RunPlan> {
            fun point(key: String) = data.getDoubleArrayExtra(key)?.let { GeoPoint(it[0], it[1]) }
            val plan = RunPlan(
                destination = point(EXTRA_DESTINATION) ?: GeoPoint(0.0, 0.0),
                stop = point(EXTRA_STOP),
                path = PolylineCodec.decode(data.getStringExtra(EXTRA_PATH).orEmpty())
            )
            return data.getDoubleExtra(EXTRA_GOAL_KM, 0.0) to plan
        }
    }
}
