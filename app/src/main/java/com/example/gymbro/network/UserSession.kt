package com.example.gymbro.network

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

// The logged user, saved on the phone (SharedPreferences) so that the login is kept when the app
// is closed. The token is the proof of the login: it is sent to the REST API with every request.
object UserSession {

    private const val KEY_TOKEN = "token"
    private const val KEY_ID = "id"
    private const val KEY_USERNAME = "username"
    private const val KEY_EMAIL = "email"
    private const val KEY_HAS_PICTURE = "has_picture"
    private const val KEY_HEIGHT = "height_cm"
    private const val KEY_WEIGHT = "weight_kg"
    private const val KEY_BIRTH_YEAR = "birth_year"
    private const val KEY_SEX = "sex"
    private const val KEY_ACTIVE_PLAN = "active_plan_id"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("user_session", Context.MODE_PRIVATE)
    }

    val token: String?
        get() = prefs.getString(KEY_TOKEN, null)

    val isLoggedIn: Boolean
        get() = token != null

    // id of the logged user (null if not logged in)
    val userId: Long?
        get() = if (isLoggedIn) prefs.getLong(KEY_ID, 0L) else null

    val user: User?
        get() = if (!isLoggedIn) null else User(
            id = prefs.getLong(KEY_ID, 0L),
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            email = prefs.getString(KEY_EMAIL, "").orEmpty(),
            hasPicture = prefs.getBoolean(KEY_HAS_PICTURE, false),
            heightCm = prefs.getString(KEY_HEIGHT, null)?.toDoubleOrNull(),
            weightKg = prefs.getString(KEY_WEIGHT, null)?.toDoubleOrNull(),
            birthYear = prefs.getString(KEY_BIRTH_YEAR, null)?.toIntOrNull(),
            sex = prefs.getString(KEY_SEX, null)?.let { value -> Sex.entries.firstOrNull { it.name == value } },
            activePlanId = prefs.getLong(KEY_ACTIVE_PLAN, 0L).takeIf { it > 0 }
        )

    fun save(token: String, user: User) {
        prefs.edit { putString(KEY_TOKEN, token) }
        updateUser(user)
    }

    fun updateUser(user: User) {
        prefs.edit {
            putLong(KEY_ID, user.id)
            putString(KEY_USERNAME, user.username)
            putString(KEY_EMAIL, user.email)
            putBoolean(KEY_HAS_PICTURE, user.hasPicture)
            // body data: saved as texts, null = not set
            putString(KEY_HEIGHT, user.heightCm?.toString())
            putString(KEY_WEIGHT, user.weightKg?.toString())
            putString(KEY_BIRTH_YEAR, user.birthYear?.toString())
            putString(KEY_SEX, user.sex?.name)
            putLong(KEY_ACTIVE_PLAN, user.activePlanId ?: 0L)
        }
    }

    fun clear() {
        prefs.edit { clear() }
    }
}
