package com.example.gymbro.session

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.gymbro.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.LocationSource
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapColorScheme

// Run session in progress: map that follows the current GPS position
class RunSessionFragment : SessionFragment(R.layout.fragment_run_session), OnMapReadyCallback {

    private val locationPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private var map: GoogleMap? = null
    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var gpsStatus: View

    // No position shown yet: the first one is shown with a close zoom, then the user's zoom is kept
    private var firstPosition = true

    // The camera follows the user while running. It stops when the user moves the map by hand,
    // and starts again with the "my location" button of the map (top right).
    private var followUser = true

    // Moves the blue "my position" dot of the map (given by the map, see onMapReady)
    private var dotListener: LocationSource.OnLocationChangedListener? = null

    // Receives the GPS positions while the page is visible
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { showPosition(it) }
        }
    }

    // Asks the user for the location permission; the lambda receives the answer
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            Toast.makeText(requireContext(), R.string.location_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fusedLocation = LocationServices.getFusedLocationProviderClient(requireContext())
        gpsStatus = view.findViewById(R.id.gpsStatus)

        // Without an API key Google Maps shows only a blank map: explain what is missing
        view.findViewById<View>(R.id.mapKeyMissing).isVisible = !hasMapsApiKey()

        // The map is loaded in background: onMapReady() is called when it is ready to be used
        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onStart() {
        super.onStart()
        // back to the page (e.g. from another app): restart the GPS updates stopped in onStop()
        if (map != null) startLocationUpdates()
    }

    override fun onStop() {
        super.onStop()
        // the GPS is used only while the page is visible (it consumes battery)
        fusedLocation.removeLocationUpdates(locationCallback)
    }

    override fun onDestroyView() {
        map = null
        dotListener = null
        firstPosition = true
        followUser = true
        super.onDestroyView()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        googleMap.mapColorScheme = MapColorScheme.DARK  // dark map, like the rest of the app

        // The blue dot is moved with the same GPS positions that move the camera, so they always
        // agree (otherwise the map would ask the GPS for its own positions, at a different pace)
        googleMap.setLocationSource(object : LocationSource {
            override fun activate(listener: LocationSource.OnLocationChangedListener) {
                dotListener = listener
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

        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            locationPermissionRequest.launch(locationPermissions)
        }
    }

    // The key really written in the installed app (AndroidManifest.xml), read while the app runs
    private fun hasMapsApiKey(): Boolean {
        val context = requireContext()
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        )
        return !appInfo.metaData?.getString("com.google.android.geo.API_KEY").isNullOrBlank()
    }

    private fun hasLocationPermission() = locationPermissions.any {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    // Shows the blue "my position" dot and starts receiving the GPS positions (every 2 seconds).
    // Must be called only after hasLocationPermission() returned true.
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val map = map ?: return
        if (!hasLocationPermission()) return

        map.isMyLocationEnabled = true
        if (firstPosition) gpsStatus.isVisible = true

        // The last known position (if any) is shown immediately, while waiting for a fresh one
        fusedLocation.lastLocation.addOnSuccessListener { location ->
            if (location != null && firstPosition) showPosition(location)
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    // Called at every new GPS position: moves the blue dot, and the camera if it follows the user
    private fun showPosition(location: Location) {
        val map = map ?: return  // the page was closed meanwhile
        gpsStatus.isVisible = false
        dotListener?.onLocationChanged(location)
        if (!followUser) return

        val position = LatLng(location.latitude, location.longitude)
        if (firstPosition) {
            firstPosition = false
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 17f))
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLng(position))
        }
    }
}
