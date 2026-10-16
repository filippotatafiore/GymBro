package com.example.gymbro.session

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.gymbro.network.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// The swim in progress of the logged user: pool length, laps swum (added with the buttons or by
// voice) and goal in laps. Everything is saved in SharedPreferences of the user, so the swim is not
// lost when the app is closed. The pool length and the voice counting choice are kept also for the
// next swims. All the functions must be called from the main thread.
object SwimTracker {

    private const val KEY_POOL = "pool_length_m"
    private const val KEY_LAPS = "laps"
    private const val KEY_GOAL = "goal_laps"             // 0 = no goal
    private const val KEY_GOAL_SEEN = "goal_seen"        // "goal reached" window already shown
    private const val KEY_ADDED = "added"                // the laps added, e.g. "4,2,8": the last can be undone

    private const val KEY_LAST_POOL = "last_pool_length_m"
    private const val KEY_VOICE = "voice_counting"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val userId: Long get() = UserSession.userId ?: 0

    // The swim in progress (e.g. "swim_3")
    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences("swim_$userId", Context.MODE_PRIVATE)

    // Kept from a swim to the next one (e.g. "swim_settings_3")
    private val settings: SharedPreferences
        get() = appContext.getSharedPreferences("swim_settings_$userId", Context.MODE_PRIVATE)

    // Increases at every change: the swim page is updated when it changes
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    // The pool length of the last swim (25 m the first time): proposed when the next swim starts
    var lastPoolLengthM: Double
        get() = settings.getFloat(KEY_LAST_POOL, SwimMath.DEFAULT_POOL_M.toFloat()).toDouble()
        set(value) = settings.edit { putFloat(KEY_LAST_POOL, value.toFloat()) }

    // Count the laps also by voice (the user can turn it off on the swim page)
    var voiceCounting: Boolean
        get() = settings.getBoolean(KEY_VOICE, true)
        set(value) = settings.edit { putBoolean(KEY_VOICE, value) }

    val poolLengthM: Double
        get() = prefs.getFloat(KEY_POOL, lastPoolLengthM.toFloat()).toDouble()

    val laps: Int
        get() = prefs.getInt(KEY_LAPS, 0)

    // null = no goal
    val goalLaps: Int?
        get() = prefs.getInt(KEY_GOAL, 0).takeIf { it > 0 }

    val meters: Double
        get() = SwimMath.meters(laps, poolLengthM)

    val goalReached: Boolean
        get() = goalLaps?.let { laps >= it } ?: false

    // The user already saw the "goal reached" window (then chose to keep swimming)
    var goalSeen: Boolean
        get() = prefs.getBoolean(KEY_GOAL_SEEN, false)
        set(value) = prefs.edit { putBoolean(KEY_GOAL_SEEN, value) }

    // The last laps added (null = nothing to undo)
    val lastAdded: Int?
        get() = added().lastOrNull()

    // A new swim starts: the previous data are deleted
    fun start(poolLengthM: Double, goalLaps: Int?) {
        lastPoolLengthM = poolLengthM
        prefs.edit {
            clear()
            putFloat(KEY_POOL, poolLengthM.toFloat())
            putInt(KEY_GOAL, goalLaps ?: 0)
        }
        notifyChanged()
    }

    // Laps completed (buttons +2, +4, +8, +10 or a number said aloud)
    fun add(count: Int) {
        if (count <= 0) return
        val total = (laps + count).coerceAtMost(SwimMath.MAX_LAPS)
        prefs.edit {
            putInt(KEY_LAPS, total)
            putString(KEY_ADDED, (added() + (total - laps)).takeLast(50).joinToString(","))
        }
        notifyChanged()
    }

    // Removes the last laps added (e.g. a button pressed by mistake)
    fun undo() {
        val added = added()
        val last = added.lastOrNull() ?: return
        prefs.edit {
            putInt(KEY_LAPS, (laps - last).coerceAtLeast(0))
            putString(KEY_ADDED, added.dropLast(1).joinToString(","))
        }
        notifyChanged()
    }

    // The swim ended (saved): its data are deleted (the settings stay)
    fun clear() {
        prefs.edit { clear() }
        notifyChanged()
    }

    private fun added(): List<Int> =
        prefs.getString(KEY_ADDED, "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }

    private fun notifyChanged() {
        _changes.value++
    }
}
