package com.example.gymbro

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource

// FragmentActivity (instead of ComponentActivity) is needed to host the map (SupportMapFragment)
class RunActivity : FragmentActivity(), OnMapReadyCallback {

    private val locationPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private var map: GoogleMap? = null

    // Asks the user for the location permission; the lambda receives the answer
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasLocationPermission()) {
            showCurrentLocation()
        } else {
            Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // link to the layout
        setContentView(R.layout.activity_run)

        // Back button - return to the home page
        findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            finish()
        }


        // --------------------------------------------------------- MAP

        // The map is loaded in background: onMapReady() is called when it is ready to be used
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)


        // --------------------------------------------------------- NAVBAR

        // Navbar button references
        val homeButton = findViewById<ImageButton>(R.id.homeButton)
        val profileButton = findViewById<ImageButton>(R.id.profileButton)

        // Home Button - return to the home page
        homeButton.setOnClickListener {
            finish()
        }

        // Profile Button - Switch to ProfileActivity
        profileButton.setOnClickListener {
            val intent = Intent(this, ProfileActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap

        if (hasLocationPermission()) {
            showCurrentLocation()
        } else {
            locationPermissionRequest.launch(locationPermissions)
        }
    }

    private fun hasLocationPermission() = locationPermissions.any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    // Shows the blue "my position" dot and centers the map on the current GPS position.
    // Must be called only after hasLocationPermission() returned true.
    @SuppressLint("MissingPermission")
    private fun showCurrentLocation() {
        val map = map ?: return

        map.isMyLocationEnabled = true

        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    val position = LatLng(location.latitude, location.longitude)
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 17f))
                } else {
                    Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_LONG).show()
                }
            }
    }
}
