package com.example.gymbro.session

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.example.gymbro.R
import com.example.gymbro.network.UserSession
import com.example.gymbro.plan.Plan
import org.json.JSONArray

enum class SessionType(
    @StringRes val label: Int,      // e.g. "Gym"
    @StringRes val title: Int,      // e.g. "GYM SESSION"
    @DrawableRes val icon: Int
) {
    GYM(R.string.gym, R.string.gym_session, R.drawable.dumbbell_icon),
    RUN(R.string.run, R.string.run_session, R.drawable.shoe_icon),
    SWIM(R.string.swim, R.string.swim_session, R.drawable.swim_icon)
}

// The session in progress as the pages show it: its type (null = no session) and whether it is
// paused (e.g. the home icon of the bottom strip shines while a session is in progress)
data class SessionStatus(val type: SessionType?, val paused: Boolean) {
    val isActive: Boolean
        get() = type != null
}

// The training session in progress (if any) of the logged user. It is saved in SharedPreferences,
// so it is kept when changing page and even when the app is closed. Every user has their own:
// after a logout the session stays there, waiting for the same user to log in again.
// Gym sessions: also the day of the workout plan followed and the exercises, as they are done.
object ActiveSession {

    private const val KEY_TYPE = "type"
    private const val KEY_START_TIME = "start_time"
    private const val KEY_PAUSED_AT = "paused_at"        // 0 = not paused
    private const val KEY_PAUSED_TOTAL = "paused_total"  // time spent in previous pauses
    private const val KEY_PLAN_ID = "plan_id"            // gym: 0 = no plan
    private const val KEY_PLAN_NAME = "plan_name"
    private const val KEY_PLAN_DAY = "plan_day"          // index of the day of the plan
    private const val KEY_PLAN_DAY_NAME = "plan_day_name"
    private const val KEY_EXERCISES = "exercises"        // JSON

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // File of the logged user (e.g. "active_session_3")
    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences("active_session_${UserSession.userId ?: 0}", Context.MODE_PRIVATE)

    val type: SessionType?
        get() = prefs.getString(KEY_TYPE, null)?.let { SessionType.valueOf(it) }

    val isActive: Boolean
        get() = type != null

    val isPaused: Boolean
        get() = prefs.getLong(KEY_PAUSED_AT, 0L) != 0L

    val status: SessionStatus
        get() = SessionStatus(type, isPaused)

    // When the session was started (milliseconds since 1970)
    val startTime: Long
        get() = prefs.getLong(KEY_START_TIME, 0L)

    fun start(type: SessionType) {
        prefs.edit {
            clear()  // nothing left from the previous session
            putString(KEY_TYPE, type.name)
            putLong(KEY_START_TIME, System.currentTimeMillis())
            putLong(KEY_PAUSED_AT, 0L)
            putLong(KEY_PAUSED_TOTAL, 0L)
        }
    }

    // Gym session following a day of a workout plan (plan = null: without plan, the exercises are
    // added during the session)
    fun startGym(plan: Plan?, dayIndex: Int) {
        start(SessionType.GYM)
        val day = plan?.days?.getOrNull(dayIndex) ?: return
        prefs.edit {
            putLong(KEY_PLAN_ID, plan.id)
            putString(KEY_PLAN_NAME, plan.name)
            putInt(KEY_PLAN_DAY, dayIndex)
            putString(KEY_PLAN_DAY_NAME, day.name)
        }
        exercises = day.exercises.mapIndexed { index, exercise -> Exercise.fromPlan(exercise, index) }
    }

    // Gym session: the plan and the day followed (null = session without plan)
    val planId: Long?
        get() = prefs.getLong(KEY_PLAN_ID, 0L).takeIf { it > 0 }

    val planName: String?
        get() = prefs.getString(KEY_PLAN_NAME, null)

    val planDay: Int?
        get() = if (planId == null) null else prefs.getInt(KEY_PLAN_DAY, 0)

    val planDayName: String?
        get() = prefs.getString(KEY_PLAN_DAY_NAME, null)

    // Gym session: the exercises as they are now (done or not, changes made during the session)
    var exercises: List<Exercise>
        get() = prefs.getString(KEY_EXERCISES, null)?.let { text ->
            val array = JSONArray(text)
            (0 until array.length()).map { Exercise.fromJson(array.getJSONObject(it)) }
        } ?: emptyList()
        set(value) = prefs.edit { putString(KEY_EXERCISES, JSONArray(value.map { it.toJson() }).toString()) }

    fun pause() {
        if (isActive && !isPaused) {
            prefs.edit { putLong(KEY_PAUSED_AT, System.currentTimeMillis()) }
        }
    }

    fun resume() {
        if (!isPaused) return
        val pausedFor = System.currentTimeMillis() - prefs.getLong(KEY_PAUSED_AT, 0L)
        prefs.edit {
            putLong(KEY_PAUSED_TOTAL, prefs.getLong(KEY_PAUSED_TOTAL, 0L) + pausedFor)
            putLong(KEY_PAUSED_AT, 0L)
        }
    }

    // Training time, pauses excluded
    fun elapsedMillis(): Long {
        if (!isActive) return 0L
        val end = if (isPaused) prefs.getLong(KEY_PAUSED_AT, 0L) else System.currentTimeMillis()
        return end - startTime - prefs.getLong(KEY_PAUSED_TOTAL, 0L)
    }

    fun clear() {
        prefs.edit { clear() }
    }
}
