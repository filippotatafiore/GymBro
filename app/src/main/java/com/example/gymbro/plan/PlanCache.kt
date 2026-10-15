package com.example.gymbro.plan

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.gymbro.network.UserSession
import org.json.JSONException
import org.json.JSONObject

// The active workout plan as it was last downloaded, saved on the phone (SharedPreferences): a gym
// session can follow it also when the server is not reachable (e.g. a gym without signal).
object PlanCache {

    private const val KEY_PLAN = "plan"
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // File of the logged user (e.g. "active_plan_3")
    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences("active_plan_${UserSession.userId ?: 0}", Context.MODE_PRIVATE)

    fun save(plan: Plan) {
        prefs.edit { putString(KEY_PLAN, plan.toStoredJson().toString()) }
    }

    // The saved plan, if it is the one with this id
    fun load(planId: Long): Plan? =
        try {
            prefs.getString(KEY_PLAN, null)?.let { Plan.fromJson(JSONObject(it)) }?.takeIf { it.id == planId }
        } catch (e: JSONException) {
            null
        }

    // Account deleted
    fun clear() {
        prefs.edit { clear() }
    }
}
