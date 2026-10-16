package com.example.gymbro

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.gymbro.ui.theme.GymBroTheme


import android.app.Dialog
import android.view.View
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.home.CalendarDrawer
import com.example.gymbro.home.HomeFragment
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.plan.Plan
import com.example.gymbro.profile.PersonalInfoForm
import com.example.gymbro.repository.SessionRepository
import com.example.gymbro.repository.SyncManager
import com.example.gymbro.session.ActiveSession
import com.example.gymbro.session.GymSessionFragment
import com.example.gymbro.session.GymSetup
import com.example.gymbro.session.RunMath
import com.example.gymbro.session.RunPlan
import com.example.gymbro.session.RunSessionFragment
import com.example.gymbro.session.RunSetup
import com.example.gymbro.session.RunTracker
import com.example.gymbro.session.RunTrackingService
import com.example.gymbro.session.SessionType
import com.example.gymbro.session.publishSession
import kotlinx.coroutines.launch
import java.util.UUID


// Home page. Its middle part shows the home (start training + last sessions) or, while a
// session is in progress, the session itself: it stays there when changing page and coming back.
// FragmentActivity (instead of ComponentActivity) is needed to host these parts (fragments).
class MainActivity : ThemedActivity() {

    private lateinit var bottomNav: BottomNavBar

    // Window asking the personal information after the registration (while it is open)
    private var personalInfoDialog: Dialog? = null

    // Calendar sliding from the left (calendar button of the home)
    private lateinit var calendar: CalendarDrawer

    // Permissions and goal asked before a run starts
    private val runSetup = RunSetup(this)

    // Day of the active workout plan asked before a gym session starts
    private val gymSetup = GymSetup(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nobody logged in: the app starts from the welcome page (register / log in)
        if (!UserSession.isLoggedIn) {
            WelcomeActivity.open(this)
            finish()
            return
        }

        // link to the layout
        setContentView(R.layout.activity_main)


        /*
        setContent {
            GymBroTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        */


        // --------------------------------------------------------- NAVBAR

        bottomNav = BottomNavBar(this, BottomNavBar.Page.HOME)


        // --------------------------------------------------------- CALENDAR

        calendar = CalendarDrawer(this)
    }

    fun openCalendar() {
        calendar.open()
    }

    override fun onResume() {
        super.onResume()
        showCurrentPage()
        bottomNav.refresh()
        if (UserSession.askPersonalInfo) askPersonalInfo()
        // a run in progress is tracked again if the app was closed meanwhile (or after a new login)
        if (ActiveSession.type == SessionType.RUN) RunTrackingService.start(this)
        // LAST SESSIONS and calendar show the sessions saved on this phone (also offline): what
        // changed on this phone is sent, then the sessions of the server are downloaded
        SyncManager.syncInBackground()
    }

    override fun onPause() {
        super.onPause()
        bottomNav.stopGlow()
    }


    // Just registered: a window asks the personal information of the user (used by the coach for the
    // workout plans and for the calories of the runs). Once saved or skipped ("Later", back) it is
    // not asked again; they can be changed in the menu of the profile.
    private fun askPersonalInfo() {
        if (personalInfoDialog != null) return
        val dialog = createModal(this, R.layout.dialog_personal_info, this, onDismiss = { personalInfoDialog = null })
        fun done() {
            UserSession.askPersonalInfo = false
            dialog.dismiss()
        }
        PersonalInfoForm(dialog.window!!.decorView, lifecycleScope) {
            Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
            done()
        }
        dialog.findViewById<View>(R.id.personalInfoLaterButton).setOnClickListener { done() }
        // back button or tap outside: like "Later" (closed by a recreation of the page: asked again)
        dialog.setOnCancelListener { UserSession.askPersonalInfo = false }
        personalInfoDialog = dialog
        dialog.show()
    }


    // --------------------------------------------------------- SESSION

    // Gym: first the day of the active workout plan (GymSetup), which then calls startGym
    fun prepareGym() {
        gymSetup.begin()
    }

    // plan = null: session without plan (the exercises are added during the session)
    fun startGym(plan: Plan?, dayIndex: Int) {
        ActiveSession.startGym(plan, dayIndex)
        onSessionChanged()
    }

    // Run: first the permissions and the goal (RunSetup), which then calls startRun
    fun prepareRun() {
        runSetup.begin()
    }

    // The timer starts now; the GPS positions are received by RunTrackingService
    fun startRun(goalKm: Double?, plan: RunPlan?, weightKg: Double) {
        RunTracker.start(goalKm, plan, weightKg)
        ActiveSession.start(SessionType.RUN)
        RunTrackingService.start(this)
        onSessionChanged()
    }

    // The session in progress as it would be saved now (shown in the window to stop it)
    fun finishedSession(): TrainingSession? {
        val type = ActiveSession.type ?: return null
        val run = if (type == SessionType.RUN) RunTracker.current else null
        val gym = type == SessionType.GYM
        return TrainingSession(
            // a new id for each session: the server saves it only once, also if sent again
            clientId = UUID.randomUUID().toString(),
            type = type,
            startTime = ActiveSession.startTime,
            durationMillis = ActiveSession.elapsedMillis(),
            // run: distance rounded to the nearest 0.1 km (the one used for the personal bests)
            distanceKm = run?.let { RunMath.roundKm(it.distanceKm) },
            kcal = run?.kcal,
            maxSpeedKmh = run?.let { it.maxSpeedMps * 3.6 },
            goalKm = if (run != null) RunTracker.goalKm else null,
            route = run?.route?.filter { it.size >= 2 },
            // gym: the exercises as they were done, and the day of the plan followed
            exercises = if (gym) ActiveSession.exercises else null,
            planId = if (gym) ActiveSession.planId else null,
            planName = if (gym) ActiveSession.planName else null,
            planDay = if (gym) ActiveSession.planDay else null,
            planDayName = if (gym) ActiveSession.planDayName else null
        )
    }

    // Saves the session on this phone (it appears at once in LAST SESSIONS and in the calendar) and
    // on the server (the same on every phone of the user), publishes it on the profile if requested,
    // and goes back to the home. Server not reachable: the session is sent later (it can be
    // published then: publishing needs the server).
    fun saveSession(session: TrainingSession, publish: Boolean) {
        if (session.type == SessionType.RUN) {
            RunTrackingService.stop(this)
            RunTracker.clear()
        }
        ActiveSession.clear()

        // Saved (and published) in background: database and network must not block the app.
        // applicationScope: the work completes even if this page is closed meanwhile.
        val app = gymBroApp
        app.applicationScope.launch {
            val saved = SessionRepository.save(session)
            if (saved == null) {
                Toast.makeText(app, R.string.session_saved_offline, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (publish) {
                try {
                    publishSession(app, saved)
                    Toast.makeText(app, R.string.session_published, Toast.LENGTH_SHORT).show()
                } catch (e: ApiException) {
                    // the session is saved anyway: it can be published later from LAST SESSIONS
                    Toast.makeText(app, e.userMessage(app), Toast.LENGTH_LONG).show()
                }
            }
        }
        onSessionChanged()
    }

    // Called when a session starts, is paused/resumed or stops
    fun onSessionChanged() {
        showCurrentPage()
        bottomNav.refresh()
    }

    // Shows home / gym session / run session, depending on the session in progress
    private fun showCurrentPage() {
        val page = when (ActiveSession.type) {
            null -> HomeFragment::class.java
            SessionType.GYM -> GymSessionFragment::class.java
            SessionType.RUN -> RunSessionFragment::class.java
        }
        val shown = supportFragmentManager.findFragmentById(R.id.page_container)
        if (shown?.javaClass == page) return

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.page_container, page, null)
            .commit()
    }
}

/*
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Ciao $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    GymBroTheme {
        Greeting("Android")
    }
}
*/
