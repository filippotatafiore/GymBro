package com.example.gymbro.session

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.gymbro.R
import com.example.gymbro.createModal
import com.example.gymbro.formatDistance
import com.example.gymbro.formatKcal
import com.example.gymbro.formatLiveDistance
import com.example.gymbro.formatPace
import com.example.gymbro.formatSpeed
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.LocationSource
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Run session in progress: map that follows the current GPS position and draws the path followed,
// statistics in real time, and a window when the goal distance is reached.
// The GPS positions are received by RunTrackingService and collected by RunTracker.
class RunSessionFragment : SessionFragment(R.layout.fragment_run_session), OnMapReadyCallback {

    private val locationPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private var map: GoogleMap? = null
    private lateinit var gpsStatus: View
    private lateinit var distanceText: TextView
    private lateinit var liveStats: StatGrid

    // No position shown yet: the first one is shown with a close zoom, then the user's zoom is kept
    private var firstPosition = true

    // The camera follows the user while running. It stops when the user moves the map by hand,
    // and starts again with the "my location" button of the map (top right).
    private var followUser = true

    // Moves the blue "my position" dot of the map (given by the map, see onMapReady)
    private var dotListener: LocationSource.OnLocationChangedListener? = null
    private var shownLocation: Location? = null

    // Path followed: one line per part (a new part starts after a pause)
    private val trackLines = mutableListOf<Polyline>()
    private var startMarker: Marker? = null

    private var goalDialog: Dialog? = null

    // Asks the location permission again if it was removed (e.g. from the settings of the phone)
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (RunTrackingService.hasLocationPermission(requireContext())) {
            RunTrackingService.start(requireContext())
            showMyLocation()
        } else {
            Toast.makeText(requireContext(), R.string.location_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        gpsStatus = view.findViewById(R.id.gpsStatus)
        gpsStatus.isVisible = RunTracker.lastLocation == null
        distanceText = view.findViewById(R.id.runDistance)
        liveStats = StatGrid(view.findViewById(R.id.liveStats), columns = 3)

        // Without an API key Google Maps shows only a blank map: explain what is missing
        view.findViewById<View>(R.id.mapKeyMissing).isVisible = mapsApiKey(requireContext()) == null

        // The map is loaded in background: onMapReady() is called when it is ready to be used
        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        if (!RunTrackingService.hasLocationPermission(requireContext())) {
            locationPermissionRequest.launch(locationPermissions)
        }

        // While the page is visible: redraw at every new GPS position, and update every second
        // the statistics that change with the time (average speed and pace)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { RunTracker.changes.collect { showTrack() } }
                while (true) {
                    showStats()
                    delay(1000)
                }
            }
        }
    }

    override fun onDestroyView() {
        map = null
        dotListener = null
        shownLocation = null
        trackLines.clear()
        startMarker = null
        goalDialog = null
        firstPosition = true
        followUser = true
        super.onDestroyView()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        if (view == null) return  // the page was closed meanwhile
        map = googleMap
        googleMap.useDarkTheme(requireContext())
        googleMap.isBuildingsEnabled = false  // 3D buildings would hide parts of the path drawn

        // The blue dot is moved with the same GPS positions that draw the path and move the
        // camera, so they always agree (otherwise the map would ask the GPS for its own positions)
        googleMap.setLocationSource(object : LocationSource {
            override fun activate(listener: LocationSource.OnLocationChangedListener) {
                dotListener = listener
                RunTracker.lastLocation?.let { listener.onLocationChanged(it) }
            }

            override fun deactivate() {
                dotListener = null
            }
        })

        // Moving the map by hand stops following the user...
        googleMap.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) followUser = false
        }
        // ...and the "my location" button starts following again
        googleMap.uiSettings.isMyLocationButtonEnabled = true
        googleMap.setOnMyLocationButtonClickListener {
            followUser = true
            false  // false: the map also moves to the current position, as usual
        }

        drawPlan(googleMap)
        showMyLocation()
        showTrack()
    }

    // Shows the blue "my position" dot (only with the location permission)
    @SuppressLint("MissingPermission")
    private fun showMyLocation() {
        val map = map ?: return
        if (RunTrackingService.hasLocationPermission(requireContext())) map.isMyLocationEnabled = true
    }

    // Route planned before the run (dashed), with its stop and destination
    private fun drawPlan(map: GoogleMap) {
        val plan = RunTracker.plan ?: return
        if (plan.path.size >= 2) map.addPolyline(plannedLine(requireContext(), plan.path.map { it.toLatLng() }))
        plan.stop?.let { stop ->
            map.addMarker(MarkerOptions().position(stop.toLatLng()).title(getString(R.string.stop_marker))
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)))
        }
        map.addMarker(MarkerOptions().position(plan.destination.toLatLng()).title(getString(R.string.destination)))
    }

    // Path followed, position of the user, statistics and goal: called at every new GPS position
    private fun showTrack() {
        showStats()
        checkGoal()
        val map = map ?: return
        val context = context ?: return

        // the path: the finished parts do not change, only the last one grows
        val segments = RunTracker.current.segments
        while (trackLines.size > segments.size) trackLines.removeAt(trackLines.lastIndex).remove()
        segments.forEachIndexed { i, segment ->
            if (i < trackLines.size && i < segments.lastIndex) return@forEachIndexed
            val points = segment.map { LatLng(it.lat, it.lng) }
            if (i < trackLines.size) trackLines[i].points = points
            else trackLines.add(map.addPolyline(trackLine(context, points)))
        }

        // green dot where the run started
        val start = segments.firstOrNull()?.firstOrNull()
        if (start != null && startMarker == null) {
            startMarker = map.addMarker(MarkerOptions().position(LatLng(start.lat, start.lng)).anchor(0.5f, 0.5f)
                .icon(dotMarker(context, R.color.successGreen)).title(getString(R.string.marker_start)))
        }

        // blue dot and camera
        val location = RunTracker.lastLocation ?: return
        if (location === shownLocation) return
        shownLocation = location
        gpsStatus.isVisible = false
        dotListener?.onLocationChanged(location)

        if (followUser) {
            val position = LatLng(location.latitude, location.longitude)
            if (firstPosition) {
                // moveCamera (no animation): an animated zoom would be interrupted by the next
                // position, which keeps the zoom of that moment
                firstPosition = false
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(position, 17f))
            } else {
                map.animateCamera(CameraUpdateFactory.newLatLng(position))
            }
        }
    }

    // Distance (red until the goal is reached, then green) and the other statistics
    private fun showStats() {
        val context = context ?: return
        val run = RunTracker.current
        val km = run.distanceKm
        val goal = RunTracker.goalKm
        val seconds = ActiveSession.elapsedMillis() / 1000

        distanceText.text =
            if (goal != null) getString(R.string.run_progress, formatLiveDistance(km), formatDistance(goal))
            else getString(R.string.live_distance_km, formatLiveDistance(km))
        distanceText.setTextColor(context.getColor(when {
            goal == null -> R.color.white
            km >= goal -> R.color.successGreen
            else -> R.color.errorRed
        }))

        val stats = mutableListOf(
            getString(R.string.stat_speed) to formatSpeed(run.currentSpeedMps(System.currentTimeMillis()) * 3.6),
            getString(R.string.stat_avg_speed) to formatSpeed(if (seconds > 0) km / (seconds / 3600.0) else 0.0),
            getString(R.string.stat_avg_pace) to (if (km >= 0.05) formatPace(seconds, km) else getString(R.string.no_pace)),
            getString(R.string.stat_calories) to formatKcal(run.kcal),
            getString(R.string.stat_max_speed) to formatSpeed(run.maxSpeedMps * 3.6),
        )
        if (goal != null) {
            stats += getString(R.string.stat_to_go) to
                    getString(R.string.live_distance_km, formatLiveDistance((goal - km).coerceAtLeast(0.0)))
        }
        liveStats.show(stats)
    }

    // The first time the distance reaches the goal: window to keep running or finish the run
    private fun checkGoal() {
        if (!RunTracker.goalReached || RunTracker.goalSeen || goalDialog != null || view == null) return
        val goal = RunTracker.goalKm ?: return

        val dialog = createModal(requireContext(), R.layout.dialog_goal_reached, viewLifecycleOwner) {
            goalDialog = null
            RunTracker.goalSeen = true
        }
        dialog.findViewById<TextView>(R.id.goalReachedMessage).text = getString(
            R.string.goal_reached_message,
            getString(R.string.live_distance_km, formatLiveDistance(RunTracker.current.distanceKm)),
            formatDistance(goal)
        )
        // keep running: the distance stays green
        dialog.findViewById<View>(R.id.keepRunningButton).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.finishRunButton).setOnClickListener {
            dialog.dismiss()
            showStopDialog()
        }
        goalDialog = dialog
        dialog.show()
    }
}
