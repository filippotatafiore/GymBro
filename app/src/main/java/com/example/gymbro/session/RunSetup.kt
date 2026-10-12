package com.example.gymbro.session

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.gymbro.MainActivity
import com.example.gymbro.R
import com.example.gymbro.createModal
import com.example.gymbro.showError
import java.text.DecimalFormat

// Before a run starts (before the timer): permissions (location, notifications), then the goal:
// a distance in km or miles, a route planned on the map (RoutePlannerActivity) or no goal.
// Created by MainActivity, which then starts the run (MainActivity.startRun).
class RunSetup(private val activity: MainActivity) {

    private val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS  // notification "Run in progress" / "Goal reached"
    )

    // Asks the permissions; the GPS is needed, the notifications are optional
    private val permissionRequest = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (RunTrackingService.hasLocationPermission(activity)) {
            showGoalChoice()
        } else {
            Toast.makeText(activity, R.string.location_needed_for_run, Toast.LENGTH_LONG).show()
        }
    }

    // The route planner returns the goal distance and the route chosen
    private val routePlanner = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val (goalKm, plan) = RoutePlannerActivity.readResult(data)
            activity.startRun(goalKm, plan, RunTracker.bodyWeightKg ?: RunTracker.DEFAULT_WEIGHT_KG)
        }
    }

    // "Run" pressed in the "start training" window
    fun begin() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) showGoalChoice() else permissionRequest.launch(missing.toTypedArray())
    }

    // Distance or route (or no goal), and the body weight for the calories
    private fun showGoalChoice() {
        val dialog = createModal(activity, R.layout.dialog_run_goal, activity)
        val weightInput = dialog.findViewById<EditText>(R.id.bodyWeightInput)
        val error = dialog.findViewById<TextView>(R.id.goalError)
        RunTracker.bodyWeightKg?.let { weightInput.setText(DecimalFormat("0.#").format(it)) }

        // The weight typed (remembered for the next runs), or null with an error if not valid
        fun readWeight(): Double? {
            val kg = weightInput.text.toString().replace(',', '.').toDoubleOrNull()
            if (kg == null || kg !in 30.0..300.0) {
                error.showError(activity.getString(R.string.error_invalid_body_weight))
                return null
            }
            RunTracker.bodyWeightKg = kg
            return kg
        }

        dialog.findViewById<TextView>(R.id.noGoalOption).setOnClickListener {
            val weight = readWeight() ?: return@setOnClickListener
            dialog.dismiss()
            activity.startRun(goalKm = null, plan = null, weightKg = weight)
        }
        dialog.findViewById<View>(R.id.distanceGoalOption).setOnClickListener {
            val weight = readWeight() ?: return@setOnClickListener
            dialog.dismiss()
            showDistanceGoal(weight)
        }
        dialog.findViewById<View>(R.id.routeGoalOption).setOnClickListener {
            readWeight() ?: return@setOnClickListener
            dialog.dismiss()
            routePlanner.launch(Intent(activity, RoutePlannerActivity::class.java))
        }
        dialog.show()
    }

    // Goal distance typed by the user, in km or miles (saved in km)
    private fun showDistanceGoal(weightKg: Double) {
        val dialog = createModal(activity, R.layout.dialog_goal_distance, activity)
        val input = dialog.findViewById<EditText>(R.id.goalDistanceInput)
        val unitGroup = dialog.findViewById<RadioGroup>(R.id.unitGroup)
        val error = dialog.findViewById<TextView>(R.id.goalDistanceError)

        dialog.findViewById<View>(R.id.startRunButton).setOnClickListener {
            val value = input.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            val km = if (unitGroup.checkedRadioButtonId == R.id.unitMiles) value * RunMath.KM_PER_MILE else value
            if (km !in 0.1..500.0) {
                error.showError(activity.getString(R.string.error_invalid_goal_distance))
                return@setOnClickListener
            }
            dialog.dismiss()
            activity.startRun(goalKm = km, plan = null, weightKg = weightKg)
        }
        dialog.findViewById<View>(R.id.backButton).setOnClickListener {
            dialog.dismiss()
            showGoalChoice()
        }
        dialog.show()
    }
}
