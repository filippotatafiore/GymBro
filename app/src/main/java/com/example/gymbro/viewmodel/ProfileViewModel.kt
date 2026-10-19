package com.example.gymbro.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.gymbro.Accent
import com.example.gymbro.AppTheme
import com.example.gymbro.data.ProfileEntity
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.Better
import com.example.gymbro.network.Metric
import com.example.gymbro.network.PlanRequest
import com.example.gymbro.network.Post
import com.example.gymbro.network.Sex
import com.example.gymbro.network.User
import com.example.gymbro.plan.Plan
import com.example.gymbro.profile.PersonalBests
import com.example.gymbro.repository.AccountRepository
import com.example.gymbro.repository.PlanRepository
import com.example.gymbro.repository.ProfileRepository
import com.example.gymbro.repository.SessionRepository
import com.example.gymbro.repository.UserRepository
import com.example.gymbro.session.ActiveSession
import com.example.gymbro.session.SessionStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Profile of the logged user (ProfileActivity): what is saved on this phone (posts and personal
// bests, workout plans, followers, followed users, picture), downloaded again from the server every
// time the page is shown; publishing (sessions, results, plans), new plans written by the coach,
// and the menu (personal information, look of the app, account, logout)
class ProfileViewModel(
    private val users: UserRepository,
    private val accounts: AccountRepository,
    private val profiles: ProfileRepository,
    private val plans: PlanRepository,
    private val sessions: SessionRepository
) : ViewModel() {

    // The posts of the user (newest first) and the personal bests chosen among them
    data class Posts(val posts: List<Post>, val personalBests: List<Post>)

    // The workout plans (the active one first, then the last changed)
    data class Plans(val plans: List<Plan>, val activePlanId: Long?)

    // The server is answering: the button of the window (or of the menu) waits
    data class Busy(
        val savingAccount: Boolean = false,
        val deletingAccount: Boolean = false,
        val publishingPlan: Boolean = false,
        val publishingManual: Boolean = false,
        val creatingPlan: Boolean = false
    )

    sealed interface Event {
        data class Error(val error: ApiException) : Event
        data object LoggedOut : Event                                           // the welcome page opens
        data object AccountUpdated : Event
        data class AccountNotUpdated(val error: ApiException) : Event           // shown in the menu
        data object AccountDeleted : Event                                      // the welcome page opens
        data class AccountNotDeleted(val error: ApiException) : Event           // shown in the window
        data class PlansToPublish(val plans: List<Plan>) : Event                // the window with the plans
        data object PlanPublished : Event
        data class PlanNotPublished(val error: ApiException) : Event            // shown in the window
        data class SessionsToPublish(val sessions: List<TrainingSession>, val problem: ApiException?) : Event
        data object SessionPublished : Event
        data object ManualPublished : Event
        data class ManualNotPublished(val error: ApiException) : Event          // shown in the window
        data class PlanCreated(val planId: Long) : Event                        // its page opens
        data class PlanNotCreated(val error: ApiException) : Event              // shown in the window
    }

    // The logged user (username, email, body data)
    private val _user = MutableStateFlow(users.user)
    val user: StateFlow<User?> = _user.asStateFlow()

    // null until read from the database; then updated at every change
    val posts: StateFlow<Posts?> = profiles.posts()
        .map { posts -> Posts(posts, PersonalBests.from(posts)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Followers, followed users and picture (null: never downloaded)
    val profile: StateFlow<ProfileEntity?> = profiles.profile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val activePlanId = MutableStateFlow(users.user?.activePlanId)
    val workoutPlans: StateFlow<Plans?> = combine(plans.plans(), activePlanId) { list, active ->
        Plans(list.sortedByDescending { it.id == active }, active)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow(Busy())
    val busy: StateFlow<Busy> = _busy.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    // A session is being published (the list of sessions waits)
    private var publishingSession = false

    val isLoggedIn: Boolean
        get() = users.isLoggedIn

    // The training session in progress (bottom strip; it cannot be published until it is finished)
    fun sessionStatus(): SessionStatus = ActiveSession.status


    // --------------------------------------------------------- PROFILE

    // Every time the page is shown: the profile is downloaded from the server and saved on this
    // phone (the changes made offline are sent first: personal information, look, workout plans).
    // Offline the profile saved on this phone stays.
    fun onPageShown() {
        showUser(users.user)
        viewModelScope.launch {
            try {
                showUser(profiles.refresh())
            } catch (e: ApiException) {
                when (e.code) {
                    0 -> {}
                    // the login is no longer valid (e.g. logged out from the server)
                    401 -> {
                        _events.send(Event.Error(e))
                        logout()
                        return@launch
                    }
                    else -> _events.send(Event.Error(e))
                }
            }
            plans.trySync()
        }
    }

    // e.g. a plan made active in its page
    private fun showUser(user: User?) {
        _user.value = user
        activePlanId.value = user?.activePlanId
    }

    // Back to the welcome page. The session in progress (if any) is kept for when this user logs in
    // again; the GPS of a run in progress stops until then.
    fun logout() {
        accounts.logout()
        _events.trySend(Event.LoggedOut)
    }

    // The new picture (already prepared, see PictureChooser) is uploaded to the server right away
    // (only online), then saved on this phone
    fun changePicture(jpeg: ByteArray) {
        viewModelScope.launch {
            try {
                profiles.changePicture(jpeg)
            } catch (e: ApiException) {
                _events.send(Event.Error(e))
            }
        }
    }

    // Asked after a confirmation; posts and personal bests are updated through ProfileRepository
    fun deletePost(post: Post) {
        viewModelScope.launch {
            try {
                profiles.deletePost(post)
            } catch (e: ApiException) {
                _events.send(Event.Error(e))
            }
        }
    }


    // --------------------------------------------------------- MENU

    // Personal information: saved on this phone and on the server, also offline
    fun saveBodyData(heightCm: Double?, weightKg: Double?, birthYear: Int?, sex: Sex?) {
        users.saveBodyData(heightCm, weightKg, birthYear, sex)
        showUser(users.user)
    }

    // Theme and favorite color: applied at once to the whole app and saved in the account, also offline
    fun changeAppearance(accent: Accent, theme: AppTheme) {
        users.setAppearance(accent, theme)
    }

    // Username, email and password (the current password is always needed)
    fun updateAccount(username: String, email: String, newPassword: String, currentPassword: String) {
        val user = users.user ?: return
        if (_busy.value.savingAccount) return
        _busy.update { it.copy(savingAccount = true) }
        viewModelScope.launch {
            try {
                val updated = accounts.updateAccount(
                    currentPassword,
                    username.takeIf { it != user.username },
                    email.takeIf { it != user.email },
                    newPassword.ifEmpty { null }
                )
                showUser(updated)
                _events.send(Event.AccountUpdated)
            } catch (e: ApiException) {
                _events.send(Event.AccountNotUpdated(e))
            } finally {
                _busy.update { it.copy(savingAccount = false) }
            }
        }
    }

    // The account and all its data, from the server and from this phone
    fun deleteAccount(password: String) {
        if (_busy.value.deletingAccount) return
        _busy.update { it.copy(deletingAccount = true) }
        viewModelScope.launch {
            try {
                accounts.deleteAccount(password)
                _events.send(Event.AccountDeleted)
            } catch (e: ApiException) {
                _events.send(Event.AccountNotDeleted(e))
            } finally {
                _busy.update { it.copy(deletingAccount = false) }
            }
        }
    }


    // --------------------------------------------------------- PUBLISH

    // The private workout plans, to choose the one that becomes public (only online)
    fun choosePlanToPublish() {
        viewModelScope.launch {
            try {
                plans.sync()
                _events.send(Event.PlansToPublish(plans.all().filter { !it.isPublic }))
            } catch (e: ApiException) {
                _events.send(Event.Error(e))
            }
        }
    }

    // Everyone sees it on the profile and can add it to their plans (it can be made private again
    // in its page)
    fun publishPlan(planId: Long) {
        if (_busy.value.publishingPlan) return
        _busy.update { it.copy(publishingPlan = true) }
        viewModelScope.launch {
            try {
                plans.setPublic(planId, true)
                _events.send(Event.PlanPublished)
            } catch (e: ApiException) {
                _events.send(Event.PlanNotPublished(e))
            } finally {
                _busy.update { it.copy(publishingPlan = false) }
            }
        }
    }

    // The sessions of LAST SESSIONS not published yet (synchronized first with the server, if it
    // is reachable: publishing needs it). problem: why they could not be synchronized.
    fun chooseSessionToPublish() {
        viewModelScope.launch {
            val problem = try {
                sessions.sync()
                null
            } catch (e: ApiException) {
                e
            }
            _events.send(Event.SessionsToPublish(sessions.all().filter { it.publishedPostId == null }, problem))
        }
    }

    // The session in progress is not in the list: it can be published only once it is finished
    val sessionInProgress: Boolean
        get() = ActiveSession.isActive

    fun publishSession(session: TrainingSession) {
        if (publishingSession) return
        publishingSession = true
        viewModelScope.launch {
            try {
                profiles.publishSession(session)
                _events.send(Event.SessionPublished)
            } catch (e: ApiException) {
                _events.send(Event.Error(e))
            } finally {
                publishingSession = false
            }
        }
    }

    // A result added manually, e.g. Deadlift - weight - 150 (kg) - higher is better
    fun publishManual(title: String, metric: Metric, better: Better, amount: Double, notes: String) {
        if (_busy.value.publishingManual) return
        _busy.update { it.copy(publishingManual = true) }
        viewModelScope.launch {
            try {
                profiles.publishManual(title, metric, better, amount, notes)
                _events.send(Event.ManualPublished)
            } catch (e: ApiException) {
                _events.send(Event.ManualNotPublished(e))
            } finally {
                _busy.update { it.copy(publishingManual = false) }
            }
        }
    }


    // --------------------------------------------------------- WORKOUT PLANS

    // A new plan written by the AI coach (only online): saved also on this phone
    fun createPlan(request: PlanRequest) {
        if (_busy.value.creatingPlan) return
        _busy.update { it.copy(creatingPlan = true) }
        viewModelScope.launch {
            try {
                val plan = plans.create(request)
                _events.send(Event.PlanCreated(plan.id))
            } catch (e: ApiException) {
                _events.send(Event.PlanNotCreated(e))
            } finally {
                _busy.update { it.copy(creatingPlan = false) }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = container
                ProfileViewModel(app.users, app.accounts, app.profiles, app.plans, app.sessions)
            }
        }
    }
}
