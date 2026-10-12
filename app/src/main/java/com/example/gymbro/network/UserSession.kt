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

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("user_session", Context.MODE_PRIVATE)
    }

    val token: String?
        get() = prefs.getString(KEY_TOKEN, null)

    val isLoggedIn: Boolean
        get() = token != null

    val user: User?
        get() = if (!isLoggedIn) null else User(
            id = prefs.getLong(KEY_ID, 0L),
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            email = prefs.getString(KEY_EMAIL, "").orEmpty(),
            hasPicture = prefs.getBoolean(KEY_HAS_PICTURE, false)
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
        }
    }

    fun clear() {
        prefs.edit { clear() }
    }
}
