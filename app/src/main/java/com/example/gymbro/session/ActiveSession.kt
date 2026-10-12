package com.example.gymbro.session

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.example.gymbro.R
import com.example.gymbro.network.UserSession

enum class SessionType(
    @StringRes val label: Int,      // e.g. "Gym"
    @StringRes val title: Int,      // e.g. "GYM SESSION"
    @DrawableRes val icon: Int
) {
    GYM(R.string.gym, R.string.gym_session, R.drawable.dumbbell_icon),
    RUN(R.string.run, R.string.run_session, R.drawable.shoe_icon)
}

// The training session in progress (if any) of the logged user. It is saved in SharedPreferences,
// so it is kept when changing page and even when the app is closed. Every user has their own:
// after a logout the session stays there, waiting for the same user to log in again.
object ActiveSession {

    private const val KEY_TYPE = "type"
    private const val KEY_START_TIME = "start_time"
    private const val KEY_PAUSED_AT = "paused_at"        // 0 = not paused
    private const val KEY_PAUSED_TOTAL = "paused_total"  // time spent in previous pauses

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

    // When the session was started (milliseconds since 1970)
    val startTime: Long
        get() = prefs.getLong(KEY_START_TIME, 0L)

    fun start(type: SessionType) {
        prefs.edit {
            putString(KEY_TYPE, type.name)
            putLong(KEY_START_TIME, System.currentTimeMillis())
            putLong(KEY_PAUSED_AT, 0L)
            putLong(KEY_PAUSED_TOTAL, 0L)
        }
    }

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
