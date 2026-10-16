package com.example.gymbro.session

import android.Manifest
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.example.gymbro.MainActivity
import com.example.gymbro.R
import com.example.gymbro.createModal
import com.example.gymbro.formatMeters
import com.example.gymbro.showError
import java.text.DecimalFormat

// Before a swim starts (before the timer): the microphone permission (to count the laps by voice,
// optional), then one window with the length of the pool and the goal. The pool length is the last
// one used (25 m the first time). The goal (optional) is a number of laps or a distance in km or
// miles: a distance becomes the laps that reach it, rounded up (shown while typing).
// Created by MainActivity, which then starts the swim (MainActivity.startSwim).
class SwimSetup(private val activity: MainActivity) {

    // The answer does not matter: without the microphone the laps are counted with the buttons
    private val micPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        showSetup()
    }

    // "Swim" pressed in the "start training" window
    fun begin() {
        if (SwimTracker.voiceCounting && !VoiceLapCounter.hasPermission(activity)) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            showSetup()
        }
    }

    private fun showSetup() {
        val dialog = createModal(activity, R.layout.dialog_swim_setup, activity)
        val poolInput = dialog.findViewById<EditText>(R.id.poolLengthInput)
        val goalInput = dialog.findViewById<EditText>(R.id.swimGoalInput)
        val unitGroup = dialog.findViewById<RadioGroup>(R.id.swimGoalUnit)
        val preview = dialog.findViewById<TextView>(R.id.swimGoalPreview)
        val error = dialog.findViewById<TextView>(R.id.swimSetupError)

        poolInput.setText(DecimalFormat("0.#").format(SwimTracker.lastPoolLengthM))

        fun readPool(): Double? =
            poolInput.text.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it in SwimMath.POOL_RANGE_M }

        // The goal in laps: null = no goal; -1 = not valid
        fun readGoal(pool: Double): Int? {
            val text = goalInput.text.toString().replace(',', '.').trim()
            if (text.isEmpty()) return null
            val value = text.toDoubleOrNull() ?: return -1
            val laps = when (unitGroup.checkedRadioButtonId) {
                R.id.goalUnitKm -> SwimMath.lapsFor(value * 1000, pool)
                R.id.goalUnitMiles -> SwimMath.lapsFor(value * RunMath.KM_PER_MILE * 1000, pool)
                // laps: a whole number
                else -> if (value == Math.floor(value)) value.toInt() else return -1
            }
            return laps.takeIf { it in 1..SwimMath.MAX_LAPS } ?: -1
        }

        // e.g. "= 40 laps · 1,000 m", while the goal is typed
        fun showPreview() {
            val pool = readPool()
            val laps = pool?.let { readGoal(it) }
            preview.isVisible = pool != null && laps != null && laps > 0
            if (pool != null && laps != null && laps > 0) {
                preview.text = activity.getString(
                    R.string.swim_goal_preview,
                    activity.resources.getQuantityString(R.plurals.laps_count, laps, laps),
                    formatMeters(SwimMath.meters(laps, pool))
                )
            }
            error.isVisible = false
        }
        poolInput.doAfterTextChanged { showPreview() }
        goalInput.doAfterTextChanged { showPreview() }
        unitGroup.setOnCheckedChangeListener { _, _ -> showPreview() }

        dialog.findViewById<TextView>(R.id.startSwimButton).setOnClickListener {
            val pool = readPool()
            if (pool == null) {
                error.showError(activity.getString(R.string.error_invalid_pool_length))
                return@setOnClickListener
            }
            val goal = readGoal(pool)
            if (goal == -1) {
                error.showError(activity.getString(R.string.error_invalid_swim_goal))
                return@setOnClickListener
            }
            dialog.dismiss()
            activity.startSwim(pool, goal)
        }
        dialog.show()
    }
}
