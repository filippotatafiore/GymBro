package com.example.gymbro.session

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.gymbro.MainActivity
import com.example.gymbro.R
import com.example.gymbro.formatDistance
import com.example.gymbro.formatLiveDistance
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

// Receives the GPS positions and the steps during a run and passes them to RunTracker, also when
// the screen is off or the app is on another page. It is a "foreground service": Android keeps it
// running and shows its notification ("Run in progress") for the whole run.
// Steps: the step counter sensor of the phone (a low-power chip that recognizes the steps from the
// movements of the phone), read with SensorManager.
class RunTrackingService : Service() {

    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var tracking = false

    // The step counter gives the steps made since the phone was turned on: RunTracker counts the
    // difference between two values
    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (tracking) RunTracker.onStepTotal(event.values[0])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!tracking) return  // already in the queue when the service stopped
            result.locations.forEach { RunTracker.onLocation(it) }
            updateNotification()
            if (RunTracker.goalReached && !RunTracker.goalNotified) notifyGoalReached()
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SensorManager::class.java)
    }

    // Called by start(): starts the GPS the first time (the next calls do nothing)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasLocationPermission(this) || ActiveSession.type != SessionType.RUN) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        if (!tracking) startLocationUpdates()
        // NOT_STICKY: if Android kills the app, the service is not restarted by itself (the GPS
        // can be used only while the app is open); it restarts when the app is opened again
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tracking = false
        fusedLocation.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(stepListener)
        // the "Run in progress" notification disappears with the service
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // A GPS position every 2 seconds, as precise as possible, and the steps (if they are counted)
    @SuppressLint("MissingPermission")  // checked in onStartCommand
    private fun startLocationUpdates() {
        tracking = true
        RunTracker.onTrackingStarted()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        if (RunTracker.steps != null && canCountSteps(this)) {
            // the values arrive on the main thread, like the GPS positions
            sensorManager.registerListener(
                stepListener, sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER), SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    // e.g. "2.31 / 5 km" (or "2.31 km" without a goal); tap: opens the app
    private fun buildNotification(): Notification {
        val distance = RunTracker.current.distanceKm
        val text = RunTracker.goalKm
            ?.let { goal -> getString(R.string.run_progress, formatLiveDistance(distance), formatDistance(goal)) }
            ?: formatDistance(RunMath.roundKm(distance))

        return NotificationCompat.Builder(this, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.shoe_icon)
            .setContentTitle(getString(R.string.run_in_progress))
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    // Sound/vibration notification when the goal is reached (the phone may be in a pocket)
    private fun notifyGoalReached() {
        RunTracker.goalNotified = true
        val goal = RunTracker.goalKm ?: return
        val notification = NotificationCompat.Builder(this, CHANNEL_GOAL)
            .setSmallIcon(R.drawable.shoe_icon)
            .setContentTitle(getString(R.string.goal_reached_title))
            .setContentText(getString(R.string.goal_reached_notification, formatDistance(goal)))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(GOAL_NOTIFICATION_ID, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val GOAL_NOTIFICATION_ID = 2
        private const val CHANNEL_TRACKING = "run_tracking"
        private const val CHANNEL_GOAL = "run_goal"

        // Starts tracking the run in progress (if it is not already tracked).
        // Must be called while the app is visible: Android does not allow starting the GPS from the background.
        fun start(context: Context) {
            if (!hasLocationPermission(context) || ActiveSession.type != SessionType.RUN) return
            ContextCompat.startForegroundService(context, Intent(context, RunTrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RunTrackingService::class.java))
        }

        // The phone has a step counter sensor, and the user gave the "physical activity" permission
        fun canCountSteps(context: Context): Boolean =
            context.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED

        fun hasLocationPermission(context: Context) =
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }

        // The notification channels (the user can change them in the settings of the app)
        fun createNotificationChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_TRACKING, context.getString(R.string.channel_run_tracking), NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_GOAL, context.getString(R.string.channel_run_goal), NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }
}
