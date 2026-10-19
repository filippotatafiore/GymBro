package com.example.gymbro.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.gymbro.R
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.Sex
import com.example.gymbro.network.User
import com.example.gymbro.plan.Plan
import com.example.gymbro.repository.Notices
import com.example.gymbro.repository.PlanRepository
import com.example.gymbro.repository.ProfileRepository
import com.example.gymbro.repository.SessionRepository
import com.example.gymbro.repository.SyncManager
import com.example.gymbro.repository.UserRepository
import com.example.gymbro.session.ActiveSession
import com.example.gymbro.session.RunMath
import com.example.gymbro.session.RunPlan
import com.example.gymbro.session.RunTracker
import com.example.gymbro.session.SessionStatus
import com.example.gymbro.session.SessionType
import com.example.gymbro.session.SwimTracker
import com.example.gymbro.session.lastSessionOfPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import java.util.UUID

// Home page (MainActivity) and the training session in progress, shown in its middle part: start
// (gym, run, swim), pause / resume, stop and save. Used also by the session pages (fragments of
// MainActivity: SessionFragment) and by the windows asked before a session starts (GymSetup,
// RunSetup, SwimSetup).
class MainViewModel(
    private val users: UserRepository,
    private val sessions: SessionRepository,
    private val profiles: ProfileRepository,
    private val plans: PlanRepository,
    private val sync: SyncManager,
    private val notices: Notices,
    private val appScope: CoroutineScope
) : ViewModel() {

    sealed interface Event {
        // Gym session of a plan with more days: which day (last: the last session of the plan)
        data class ChooseGymDay(val plan: Plan, val last: TrainingSession?) : Event
        // The active plan is not on this phone and the server is not reachable
        data class PlanNotLoaded(val error: ApiException) : Event
    }

    // The session in progress: the page shown in the middle (home, gym, run or swim session) and
    // the home icon of the bottom strip
    private val _session = MutableStateFlow(ActiveSession.status)
    val session: StateFlow<SessionStatus> = _session.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    // The training time, pauses excluded: every second, and at once after a pause / resume
    @OptIn(ExperimentalCoroutinesApi::class)
    val timer: Flow<Long> = session.transformLatest { session ->
        if (!session.isActive) return@transformLatest
        while (true) {
            emit(ActiveSession.elapsedMillis())
            delay(1000)
        }
    }

    val isLoggedIn: Boolean
        get() = users.isLoggedIn

    val user: User?
        get() = users.user

    // Every time the page is shown: LAST SESSIONS and calendar show the sessions saved on this
    // phone (also offline): what changed on this phone is sent, then the sessions of the server
    // are downloaded
    fun onPageShown() {
        _session.value = ActiveSession.status
        sync.syncInBackground()
    }

    private fun onSessionChanged() {
        _session.value = ActiveSession.status
    }


    // --------------------------------------------------------- PERSONAL INFORMATION

    // Just registered: the personal information are asked when the app opens (once)
    val askPersonalInfo: Boolean
        get() = users.askPersonalInfo

    // Saved or skipped: not asked again (they can be changed in the menu of the profile)
    fun personalInfoAsked() {
        users.askPersonalInfo = false
    }

    // Saved on this phone and on the server, also offline
    fun saveBodyData(heightCm: Double?, weightKg: Double?, birthYear: Int?, sex: Sex?) {
        users.saveBodyData(heightCm, weightKg, birthYear, sex)
    }


    // --------------------------------------------------------- START

    // Gym: the day of the active workout plan to follow (it works also offline: the plan and the
    // last sessions are those saved on this phone)
    // - no active plan: the session starts without plan (the exercises are added during the session)
    // - a plan with one day: that day
    // - more days: the user chooses it (ChooseGymDay)
    fun prepareGym() {
        val planId = users.user?.activePlanId
        if (planId == null) {
            startGym(null, 0)
            return
        }
        viewModelScope.launch {
            val plan = loadPlan(planId)
            when {
                plan == null || plan.days.isEmpty() -> startGym(null, 0)
                plan.days.size == 1 -> startGym(plan, 0)
                // the last sessions saved on this phone (also those finished offline)
                else -> _events.send(Event.ChooseGymDay(plan, lastSessionOfPlan(sessions.all(), plan.id)))
            }
        }
    }

    // The active plan saved on this phone, synchronized first with the server if it is reachable
    // (e.g. changed by the coach). null: the plan does not exist anymore, or it was never downloaded
    // (the session starts without plan).
    private suspend fun loadPlan(planId: Long): Plan? {
        val problem = plans.trySync()
        return plans.plan(planId) ?: run {
            if (problem != null) _events.send(Event.PlanNotLoaded(problem))
            null
        }
    }

    // plan = null: session without plan (the exercises are added during the session)
    fun startGym(plan: Plan?, dayIndex: Int) {
        ActiveSession.startGym(plan, dayIndex)
        onSessionChanged()
    }

    // Run: the body weight of the profile (for the calories), null if missing (asked before the run)
    val bodyWeightKg: Double?
        get() = users.user?.weightKg

    // The weight typed before a run, saved in the profile (phone and server, also offline): it is
    // not asked again
    fun saveBodyWeight(kg: Double) {
        val user = users.user ?: return
        users.saveBodyData(user.heightCm, kg, user.birthYear, user.sex)
    }

    // The timer starts now; the GPS positions and the steps are received by RunTrackingService.
    // countSteps: the step sensor can be used.
    fun startRun(goalKm: Double?, plan: RunPlan?, weightKg: Double, countSteps: Boolean) {
        RunTracker.start(goalKm, plan, weightKg, countSteps)
        ActiveSession.start(SessionType.RUN)
        onSessionChanged()
    }

    // Swim: the voice counting chosen and the pool length of the last swim (25 m the first time)
    val swimVoiceCounting: Boolean
        get() = SwimTracker.voiceCounting

    val lastPoolLengthM: Double
        get() = SwimTracker.lastPoolLengthM

    // The timer starts now; the laps are added on the swim page (buttons or voice)
    fun startSwim(poolLengthM: Double, goalLaps: Int?) {
        SwimTracker.start(poolLengthM, goalLaps)
        ActiveSession.start(SessionType.SWIM)
        onSessionChanged()
    }


    // --------------------------------------------------------- IN PROGRESS

    fun pauseOrResume() {
        if (ActiveSession.isPaused) ActiveSession.resume() else ActiveSession.pause()
        onSessionChanged()
    }

    // The session in progress as it would be saved now (shown in the window to stop it)
    fun finishedSession(): TrainingSession? {
        val type = ActiveSession.type ?: return null
        val run = if (type == SessionType.RUN) RunTracker.current else null
        val gym = type == SessionType.GYM
        val swim = type == SessionType.SWIM
        return TrainingSession(
            // a new id for each session: the server saves it only once, also if sent again
            clientId = UUID.randomUUID().toString(),
            type = type,
            startTime = ActiveSession.startTime,
            durationMillis = ActiveSession.elapsedMillis(),
            // run: distance rounded to the nearest 0.1 km (the one used for the personal bests);
            // swim: laps x pool length
            distanceKm = run?.let { RunMath.roundKm(it.distanceKm) } ?: if (swim) SwimTracker.meters / 1000 else null,
            kcal = run?.kcal,
            maxSpeedKmh = run?.let { it.maxSpeedMps * 3.6 },
            goalKm = if (run != null) RunTracker.goalKm else null,
            route = run?.route?.filter { it.size >= 2 },
            steps = if (run != null) RunTracker.steps else null,
            // gym: the exercises as they were done, and the day of the plan followed
            exercises = if (gym) ActiveSession.exercises else null,
            planId = if (gym) ActiveSession.planId else null,
            planName = if (gym) ActiveSession.planName else null,
            planDay = if (gym) ActiveSession.planDay else null,
            planDayName = if (gym) ActiveSession.planDayName else null,
            // swim: the pool, the laps swum and the goal
            poolLengthM = if (swim) SwimTracker.poolLengthM else null,
            laps = if (swim) SwimTracker.laps else null,
            goalLaps = if (swim) SwimTracker.goalLaps else null
        )
    }

    // Saves the session on this phone (it appears at once in LAST SESSIONS and in the calendar) and
    // on the server (the same on every phone of the user), publishes it on the profile if requested,
    // and goes back to the home. Server not reachable: the session is sent later (it can be
    // published then: publishing needs the server).
    fun saveSession(session: TrainingSession, publish: Boolean) {
        if (session.type == SessionType.RUN) RunTracker.clear()
        if (session.type == SessionType.SWIM) SwimTracker.clear()
        ActiveSession.clear()

        // Saved (and published) in background: database and network must not block the app.
        // appScope: the work completes even if this page is closed meanwhile.
        appScope.launch {
            val saved = sessions.save(session)
            if (saved == null) {
                notices.show(R.string.session_saved_offline, long = true)
                return@launch
            }
            if (publish) {
                try {
                    profiles.publishSession(saved)
                    notices.show(R.string.session_published)
                } catch (e: ApiException) {
                    // the session is saved anyway: it can be published later from LAST SESSIONS
                    notices.showError(e)
                }
            }
        }
        onSessionChanged()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = container
                MainViewModel(app.users, app.sessions, app.profiles, app.plans, app.sync, app.notices, app.appScope)
            }
        }
    }
}
