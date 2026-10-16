package com.example.gymbro.repository

import com.example.gymbro.Accent
import com.example.gymbro.AppTheme
import com.example.gymbro.GymBroApp
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.Sex
import com.example.gymbro.network.User
import com.example.gymbro.network.UserSession
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// The data of the logged user that can be changed also offline: personal information (body data),
// look of the app (theme, favorite color) and active workout plan (Repository pattern).
// A change is applied on this phone at once (UserSession) and sent to the server in background;
// offline it stays marked as pending (UserSession.pending...) and is sent at the next sync. Until
// then the values downloaded from the server do not replace it: the value chosen offline wins.
object UserRepository {

    private lateinit var app: GymBroApp
    private val mutex = Mutex()

    fun init(app: GymBroApp) {
        this.app = app
    }

    // Personal information (null = not set)
    fun saveBodyData(heightCm: Double?, weightKg: Double?, birthYear: Int?, sex: Sex?) {
        val user = UserSession.user ?: return
        UserSession.pendingBodyData = true
        UserSession.changeLocally(user.copy(heightCm = heightCm, weightKg = weightKg, birthYear = birthYear, sex = sex))
        pushInBackground()
    }

    // Theme and favorite color: applied at once to the whole app (see Appearance)
    fun setAppearance(accent: Accent, theme: AppTheme) {
        val user = UserSession.user ?: return
        UserSession.pendingAppearance = true
        UserSession.changeLocally(user.copy(accent = accent, theme = theme))
        pushInBackground()
    }

    // The workout plan followed in the gym sessions (null = none)
    fun setActivePlan(planId: Long?) {
        val user = UserSession.user ?: return
        UserSession.pendingActivePlan = true
        UserSession.changeLocally(user.copy(activePlanId = planId))
        pushInBackground()
    }

    // Sends the pending changes; the saving completes also if the page is closed meanwhile
    private fun pushInBackground() {
        app.applicationScope.launch {
            try {
                push()
            } catch (e: ApiException) {
                if (e.code == 0) OfflineNotice.show(app)
            }
        }
    }

    // Sends the changes made on this phone and not sent yet. A change made again while it was being
    // sent stays pending (it is sent next time). Throws ApiException (e.g. server not reachable).
    suspend fun push() {
        val token = UserSession.token ?: return
        mutex.withLock {
            if (UserSession.pendingBodyData) {
                val sent = UserSession.user ?: return
                val answer = ApiClient.updateProfile(token, sent.heightCm, sent.weightKg, sent.birthYear, sent.sex)
                if (UserSession.user?.let { sameBodyData(it, sent) } == true) UserSession.pendingBodyData = false
                UserSession.updateUser(answer)
            }
            if (UserSession.pendingAppearance) {
                val sent = UserSession.user ?: return
                val answer = ApiClient.setAppearance(token, sent.accent, sent.theme)
                if (UserSession.user?.let { it.accent == sent.accent && it.theme == sent.theme } == true) {
                    UserSession.pendingAppearance = false
                }
                UserSession.updateUser(answer)
            }
            if (UserSession.pendingActivePlan) {
                val sent = UserSession.user ?: return
                val answer = try {
                    ApiClient.setActivePlan(token, sent.activePlanId)
                } catch (e: ApiException) {
                    // the plan was deleted meanwhile (e.g. on another phone): no active plan
                    if (e.code == 404) ApiClient.setActivePlan(token, null) else throw e
                }
                if (UserSession.user?.activePlanId == sent.activePlanId) UserSession.pendingActivePlan = false
                UserSession.updateUser(answer)
            }
        }
    }

    // Sends the pending changes, then downloads the logged user (e.g. changed on another phone).
    // Throws ApiException.
    suspend fun refresh(): User {
        val token = UserSession.token ?: throw ApiException(401, "Login required")
        push()
        UserSession.updateUser(ApiClient.getMe(token))
        return UserSession.user ?: throw ApiException(401, "Login required")
    }

    private fun sameBodyData(a: User, b: User) =
        a.heightCm == b.heightCm && a.weightKg == b.weightKg && a.birthYear == b.birthYear && a.sex == b.sex
}
