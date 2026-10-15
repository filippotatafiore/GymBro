package com.example.gymbro.session

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.gymbro.MainActivity
import com.example.gymbro.R
import com.example.gymbro.createModal
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.formatDate
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.plan.Plan
import com.example.gymbro.plan.PlanCache
import kotlinx.coroutines.launch
import java.time.Instant

// Before a gym session starts: which day of the active workout plan to follow.
// - no active plan: the session starts without plan (the exercises are added during the session)
// - a plan with one day: that day
// - more days: a window lists them, with the day done in the last session of the plan highlighted
//   (and its date): any day can be chosen
class GymSetup(private val activity: MainActivity) {

    fun begin() {
        val planId = UserSession.user?.activePlanId
        if (planId == null) {
            activity.startGym(null, 0)
            return
        }
        activity.lifecycleScope.launch {
            val plan = loadPlan(planId)
            when {
                plan == null || plan.days.isEmpty() -> activity.startGym(null, 0)
                plan.days.size == 1 -> activity.startGym(plan, 0)
                else -> {
                    // the last sessions (usually already downloaded by the home page)
                    if (TrainingSessions.sessions.value.isEmpty()) {
                        try {
                            TrainingSessions.refresh(activity)
                        } catch (_: ApiException) {
                        }
                    }
                    chooseDay(plan, lastSessionOfPlan(TrainingSessions.sessions.value, plan.id))
                }
            }
        }
    }

    // The active plan from the server, saved on the phone for when the server is not reachable.
    // null: the plan does not exist anymore, or it cannot be read (the session starts without plan).
    private suspend fun loadPlan(planId: Long): Plan? {
        val token = UserSession.token ?: return null
        return try {
            ApiClient.getPlan(token, planId).also { PlanCache.save(it) }
        } catch (e: ApiException) {
            if (e.code == 404) return null
            PlanCache.load(planId) ?: run {
                Toast.makeText(activity, activity.getString(R.string.plan_not_loaded, e.userMessage(activity)), Toast.LENGTH_LONG).show()
                null
            }
        }
    }

    // Window with the days of the plan: tap one to start the session
    private fun chooseDay(plan: Plan, last: TrainingSession?) {
        val dialog = createModal(activity, R.layout.dialog_choose_day, activity)
        dialog.findViewById<TextView>(R.id.chooseDayPlanName).text = plan.name
        val list = dialog.findViewById<LinearLayout>(R.id.chooseDayList)
        val inflater = LayoutInflater.from(activity)

        plan.days.forEachIndexed { index, day ->
            val row = inflater.inflate(R.layout.item_choose_day, list, false)
            row.findViewById<TextView>(R.id.chooseDayName).text = day.name
            row.findViewById<TextView>(R.id.chooseDayCount).text =
                activity.resources.getQuantityString(R.plurals.plan_exercises, day.exercises.size, day.exercises.size)

            // the day done in the last session of this plan, with its date
            if (last != null && last.planDay == index) {
                row.setBackgroundResource(R.drawable.day_last_done_background)
                row.findViewById<TextView>(R.id.chooseDayLast).apply {
                    isVisible = true
                    text = activity.getString(R.string.last_done_on, formatDate(Instant.ofEpochMilli(last.startTime)))
                }
            }
            row.setOnClickListener {
                dialog.dismiss()
                activity.startGym(plan, index)
            }
            list.addView(row)
        }
        dialog.findViewById<View>(R.id.cancelButton).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
