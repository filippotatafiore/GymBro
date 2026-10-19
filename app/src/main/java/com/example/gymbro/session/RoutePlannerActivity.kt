package com.example.gymbro.session

import android.annotation.SuppressLint
import android.content.Intent
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
import androidx.activity.viewModels
import androidx.core.view.isVisible
import com.example.gymbro.R
import com.example.gymbro.ThemedActivity
import com.example.gymbro.formatLiveDistance
import com.example.gymbro.network.userMessage
import com.example.gymbro.observe
import com.example.gymbro.repository.Place
import com.example.gymbro.repository.PlannedRoute
import com.example.gymbro.viewmodel.RoutePlannerViewModel
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

// Page to plan the route of a run, before it starts: the user chooses the destination (and, if
// wanted, a stop on the way) by tapping the map or searching a place by name. The goal of the run
// is the walking distance from the current position to the destination, through the stop.
// Opened by RunSetup; it gives back the goal and the route (readResult). Points and route:
// RoutePlannerViewModel.
class RoutePlannerActivity : ThemedActivity(), OnMapReadyCallback {

    private val viewModel: RoutePlannerViewModel by viewModels { RoutePlannerViewModel.Factory }

    private lateinit var fusedLocation: FusedLocationProviderClient
    private var map: GoogleMap? = null

    // What the map and the list show now (they are drawn again only when they change)
    private var shownOrigin: GeoPoint? = null
    private var shownDestination: GeoPoint? = null
    private var shownStop: GeoPoint? = null
    private var shownRoute: PlannedRoute? = null
    private var shownPlaces: List<Place>? = null

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
            val location = result.lastLocation ?: return
            fusedLocation.removeLocationUpdates(this)
            viewModel.setOrigin(GeoPoint(location.latitude, location.longitude))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        removeStopButton.setOnClickListener { viewModel.removeStop() }
        startButton.setOnClickListener { finishWithRoute() }

        status.setText(R.string.searching_position)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.plannerMap) as SupportMapFragment
        mapFragment.getMapAsync(this)

        observe(viewModel.state) { state -> show(state) }
    }

    override fun onStart() {
        super.onStart()
        if (viewModel.state.value.origin == null) startLocationUpdates()
    }

    override fun onStop() {
        super.onStop()
        fusedLocation.removeLocationUpdates(locationCallback)
    }

    @SuppressLint("MissingPermission")  // the permission is asked by RunSetup before opening this page
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        googleMap.useAppTheme(this)
        googleMap.isBuildingsEnabled = false  // 3D buildings would hide parts of the route drawn
        if (RunTrackingService.hasLocationPermission(this)) googleMap.isMyLocationEnabled = true
        googleMap.setOnMapClickListener { setPoint(it.toGeoPoint()) }
        viewModel.state.value.origin?.let { googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(it.toLatLng(), 15f)) }
        shownOrigin = viewModel.state.value.origin
        // the points and the route chosen before the map was ready
        shownDestination = null
        shownStop = null
        shownRoute = null
        show(viewModel.state.value)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!RunTrackingService.hasLocationPermission(this)) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    // A tap on the map or a place found: sets the destination or the stop
    private fun setPoint(point: GeoPoint) {
        viewModel.setPoint(point, asStop = modeGroup.checkedRadioButtonId == R.id.modeStop)
    }

    private fun show(state: RoutePlannerViewModel.State) {
        // the position of the user found: the map goes there (if no destination yet)
        status.isVisible = state.origin == null
        val origin = state.origin
        if (origin != null && shownOrigin == null && map != null) {
            shownOrigin = origin
            if (state.destination == null) map?.animateCamera(CameraUpdateFactory.newLatLngZoom(origin.toLatLng(), 15f))
        }

        if (map != null && (state.destination != shownDestination || state.stop != shownStop)) showPoints(state)
        removeStopButton.isVisible = state.stop != null
        showRoute(state)
        showPlaces(state.places)
    }

    private fun showPoints(state: RoutePlannerViewModel.State) {
        val map = map ?: return
        shownDestination = state.destination
        shownStop = state.stop
        destinationMarker?.remove()
        stopMarker?.remove()
        destinationMarker = state.destination?.let {
            map.addMarker(MarkerOptions().position(it.toLatLng()).title(getString(R.string.destination)))
        }
        stopMarker = state.stop?.let {
            map.addMarker(MarkerOptions().position(it.toLatLng()).title(getString(R.string.stop_marker))
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)))
        }
    }

    // Goal distance: walking route from the current position to the destination, through the stop
    private fun showRoute(state: RoutePlannerViewModel.State) {
        setStartEnabled(state.canStart)
        distanceText.isVisible = state.destination != null
        val route = state.route
        routeKindText.isVisible = route != null
        when {
            state.destination == null -> {}
            state.origin == null -> distanceText.setText(R.string.searching_position)
            route == null -> distanceText.setText(R.string.calculating_route)
            else -> {
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
                        getString(R.string.route_straight_line, route.problem?.userMessage(this).orEmpty())
                    }
            }
        }

        // the line on the map, and the whole route visible
        val map = map ?: return
        if (route === shownRoute) return
        shownRoute = route
        routeLine?.remove()
        routeLine = null
        val origin = state.origin ?: return
        if (route == null) return
        val points = route.path.map { it.toLatLng() }
        routeLine = map.addPolyline(plannedLine(this, points))
        val mapView = supportFragmentManager.findFragmentById(R.id.plannerMap)?.view ?: return
        val padding = (48 * resources.displayMetrics.density).toInt()
        map.animateCamera(cameraShowing(points + origin.toLatLng(), mapView.width, mapView.height, padding))
    }

    private fun setStartEnabled(enabled: Boolean) {
        startButton.isEnabled = enabled
        startButton.alpha = if (enabled) 1f else 0.5f
    }

    // Places matching the text of the search bar
    private fun search() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(searchInput.windowToken, 0)
        viewModel.search(query)
    }

    // The places found, in a list over the map (null: hidden)
    private fun showPlaces(places: List<Place>?) {
        placeResults.isVisible = places != null
        if (places == null || places === shownPlaces) return
        shownPlaces = places
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
    }

    // Back to RunSetup with the goal (km) and the route
    private fun finishWithRoute() {
        val state = viewModel.state.value
        val route = state.route ?: return
        val destination = state.destination ?: return
        val data = Intent()
            .putExtra(EXTRA_GOAL_KM, route.distanceMeters / 1000)
            .putExtra(EXTRA_DESTINATION, doubleArrayOf(destination.lat, destination.lng))
            .putExtra(EXTRA_PATH, PolylineCodec.encode(route.path))
        state.stop?.let { data.putExtra(EXTRA_STOP, doubleArrayOf(it.lat, it.lng)) }
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
