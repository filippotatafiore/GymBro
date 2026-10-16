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
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.plan.Plan
import com.example.gymbro.repository.PlanRepository
import com.example.gymbro.repository.SessionRepository
import kotlinx.coroutines.launch
import java.time.Instant

// Before a gym session starts: which day of the active workout plan to follow. It works also
// offline: the plan and the last sessions are those saved on this phone.
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
                // the last sessions saved on this phone (also those finished offline)
                else -> chooseDay(plan, lastSessionOfPlan(SessionRepository.all(), plan.id))
            }
        }
    }

    // The active plan saved on this phone, synchronized first with the server if it is reachable
    // (e.g. changed by the coach). null: the plan does not exist anymore, or it was never downloaded
    // (the session starts without plan).
    private suspend fun loadPlan(planId: Long): Plan? {
        val problem = PlanRepository.trySync()
        return PlanRepository.plan(planId) ?: run {
            if (problem != null) {
                Toast.makeText(activity, activity.getString(R.string.plan_not_loaded, problem.userMessage(activity)), Toast.LENGTH_LONG).show()
            }
            null
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

// The most recent gym session that followed the plan with this id (null if none): it tells which
// day of the plan was done last. Plain Kotlin: tested in GymSessionsTest.kt
fun lastSessionOfPlan(sessions: List<TrainingSession>, planId: Long): TrainingSession? =
    sessions.filter { it.type == SessionType.GYM && it.planId == planId && it.planDay != null }
        .maxByOrNull { it.startTime }
