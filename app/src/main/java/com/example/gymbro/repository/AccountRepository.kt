package com.example.gymbro.repository

import android.content.Context
import androidx.core.content.edit
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.User
import com.example.gymbro.network.UserSession
import com.example.gymbro.session.RunTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// The account of the user on the server and the login on this phone: registration, login,
// logout, changes of username / email / password, deletion of the account (Repository pattern).
// Only online (except the logout). The functions throw ApiException (e.g. wrong password).
interface AccountRepository {

    // A new account: the user is logged in (the personal information are asked when the app opens)
    suspend fun register(username: String, email: String, password: String): User

    // The profile picture chosen in the registration, uploaded right after it
    suspend fun uploadPicture(jpeg: ByteArray)

    suspend fun login(email: String, password: String): User

    // The session in progress (if any) is kept for when this user logs in again; the GPS of a run
    // in progress stops until then. The token is invalidated on the server too (if reachable).
    fun logout()

    // Username, email, new password (null = not changed): the current password is always needed;
    // after a new password, the other phones must log in again. Returns the updated user.
    suspend fun updateAccount(currentPassword: String, username: String?, email: String?, newPassword: String?): User

    // The account and all its data are deleted from the server (profile, results, sessions, workout
    // plans, picture) and from this phone. The user must register again to use the app.
    suspend fun deleteAccount(password: String)
}

// context: of the app (data on this phone, GPS of the run); scope: of the app, the logout on the
// server and the deletion of the data complete also after the page is closed
class AccountRepositoryImpl(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sessions: SessionRepository,
    private val plans: PlanRepository,
    private val profiles: ProfileRepository,
    private val chats: ChatRepository
) : AccountRepository {

    private val token: String
        get() = UserSession.token ?: throw ApiException(401, "Login required")

    override suspend fun register(username: String, email: String, password: String): User {
        val auth = ApiClient.register(username, email, password)
        UserSession.save(auth.token, auth.user)
        // the home asks the personal information of the new user right away
        UserSession.askPersonalInfo = true
        return auth.user
    }

    override suspend fun uploadPicture(jpeg: ByteArray) {
        ApiClient.uploadPicture(token, jpeg)
    }

    override suspend fun login(email: String, password: String): User {
        val auth = ApiClient.login(email, password)
        UserSession.save(auth.token, auth.user)
        return auth.user
    }

    override fun logout() {
        RunTrackingService.stop(context)
        val token = UserSession.token
        UserSession.clear()
        sessions.clear()

        // if the server is not reachable there is nothing to do
        if (token != null) {
            scope.launch {
                try {
                    ApiClient.logout(token)
                } catch (_: ApiException) {
                }
            }
        }
    }

    override suspend fun updateAccount(currentPassword: String, username: String?, email: String?, newPassword: String?): User {
        val updated = ApiClient.updateAccount(token, currentPassword, username, email, newPassword)
        UserSession.updateUser(updated)
        return updated
    }

    override suspend fun deleteAccount(password: String) {
        val userId = UserSession.userId ?: throw ApiException(401, "Login required")
        ApiClient.deleteAccount(token, password)
        deleteLocalData(userId)
    }

    // The data of the deleted account on this phone: session in progress, sessions, workout plans,
    // posts, profile, chats with the coach, login
    private fun deleteLocalData(userId: Long) {
        RunTrackingService.stop(context)
        sessions.clear()
        UserSession.clear()
        // session in progress of the user (ActiveSession, swim and its settings) and plan saved by the
        // older versions of the app: emptied right now (commit), otherwise a write in background
        // would create the file again
        for (name in listOf("active_session_$userId", "swim_$userId", "swim_settings_$userId", "active_plan_$userId")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) { clear() }
            context.deleteSharedPreferences(name)
        }
        scope.launch {
            chats.deleteAll(userId)
            sessions.deleteAll(userId)
            plans.deleteAll(userId)
            profiles.deleteAll(userId)
        }
    }
}
