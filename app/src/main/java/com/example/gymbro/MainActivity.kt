package com.example.gymbro

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
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.home.CalendarDrawer
import com.example.gymbro.home.HomeFragment
import com.example.gymbro.network.userMessage
import com.example.gymbro.plan.Plan
import com.example.gymbro.profile.PersonalInfoForm
import com.example.gymbro.session.GymSessionFragment
import com.example.gymbro.session.GymSetup
import com.example.gymbro.session.RunPlan
import com.example.gymbro.session.RunSessionFragment
import com.example.gymbro.session.RunSetup
import com.example.gymbro.session.RunTrackingService
import com.example.gymbro.session.SessionStatus
import com.example.gymbro.session.SessionType
import com.example.gymbro.session.SwimSessionFragment
import com.example.gymbro.session.SwimSetup
import com.example.gymbro.viewmodel.HomeViewModel
import com.example.gymbro.viewmodel.MainViewModel
import kotlinx.coroutines.launch


// Home page. Its middle part shows the home (start training + last sessions) or, while a
// session is in progress, the session itself: it stays there when changing page and coming back.
// FragmentActivity (instead of ComponentActivity) is needed to host these parts (fragments).
// The session in progress: MainViewModel; the finished sessions (last sessions, calendar):
// HomeViewModel, shared with HomeFragment.
class MainActivity : ThemedActivity() {

    val viewModel: MainViewModel by viewModels { MainViewModel.Factory }
    private val homeViewModel: HomeViewModel by viewModels { HomeViewModel.Factory }

    private lateinit var bottomNav: BottomNavBar

    // Window asking the personal information after the registration (while it is open)
    private var personalInfoDialog: Dialog? = null

    // Calendar sliding from the left (calendar button of the home)
    private lateinit var calendar: CalendarDrawer

    // Permissions and goal asked before a run starts
    private val runSetup = RunSetup(this)

    // Day of the active workout plan asked before a gym session starts
    private val gymSetup = GymSetup(this)

    // Microphone permission, pool length and goal asked before a swim starts
    private val swimSetup = SwimSetup(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nobody logged in: the app starts from the welcome page (register / log in)
        if (!viewModel.isLoggedIn) {
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

        calendar = CalendarDrawer(this, homeViewModel)


        // --------------------------------------------------------- SESSION

        // Every time the page is shown, and when a session starts, is paused/resumed or stops:
        // home / gym / run / swim session, and the home icon of the bottom strip
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.session.collect { session ->
                    showCurrentPage(session)
                    bottomNav.refresh(session)
                }
            }
        }
        onEvents(viewModel.events) { event ->
            when (event) {
                is MainViewModel.Event.ChooseGymDay -> gymSetup.chooseDay(event.plan, event.last)
                is MainViewModel.Event.PlanNotLoaded -> Toast.makeText(
                    this, getString(R.string.plan_not_loaded, event.error.userMessage(this)), Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun openCalendar() {
        calendar.open()
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.askPersonalInfo) askPersonalInfo()
        // a run in progress is tracked again if the app was closed meanwhile (or after a new login)
        if (viewModel.session.value.type == SessionType.RUN) RunTrackingService.start(this)
        viewModel.onPageShown()
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
            viewModel.personalInfoAsked()
            dialog.dismiss()
        }
        PersonalInfoForm(dialog.window!!.decorView, viewModel.user) { heightCm, weightKg, birthYear, sex ->
            viewModel.saveBodyData(heightCm, weightKg, birthYear, sex)
            Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
            done()
        }
        dialog.findViewById<View>(R.id.personalInfoLaterButton).setOnClickListener { done() }
        // back button or tap outside: like "Later" (closed by a recreation of the page: asked again)
        dialog.setOnCancelListener { viewModel.personalInfoAsked() }
        personalInfoDialog = dialog
        dialog.show()
    }


    // --------------------------------------------------------- SESSION

    // Gym: first the day of the active workout plan (MainViewModel, GymSetup), then startGym
    fun prepareGym() {
        viewModel.prepareGym()
    }

    // plan = null: session without plan (the exercises are added during the session)
    fun startGym(plan: Plan?, dayIndex: Int) {
        viewModel.startGym(plan, dayIndex)
    }

    // Run: first the permissions and the goal (RunSetup), which then calls startRun
    fun prepareRun() {
        runSetup.begin()
    }

    // The timer starts now; the GPS positions and the steps are received by RunTrackingService
    fun startRun(goalKm: Double?, plan: RunPlan?, weightKg: Double) {
        viewModel.startRun(goalKm, plan, weightKg, countSteps = RunTrackingService.canCountSteps(this))
        RunTrackingService.start(this)
    }

    // Swim: first the microphone permission, the pool length and the goal (SwimSetup), which then
    // calls startSwim
    fun prepareSwim() {
        swimSetup.begin()
    }

    // The timer starts now; the laps are added on the swim page (buttons or voice)
    fun startSwim(poolLengthM: Double, goalLaps: Int?) {
        viewModel.startSwim(poolLengthM, goalLaps)
    }

    // Saves the session (see MainViewModel.saveSession) and goes back to the home. Run: the GPS
    // stops first.
    fun saveSession(session: TrainingSession, publish: Boolean) {
        if (session.type == SessionType.RUN) RunTrackingService.stop(this)
        viewModel.saveSession(session, publish)
    }

    // Shows home / gym session / run session / swim session, depending on the session in progress
    private fun showCurrentPage(session: SessionStatus) {
        val page = when (session.type) {
            null -> HomeFragment::class.java
            SessionType.GYM -> GymSessionFragment::class.java
            SessionType.RUN -> RunSessionFragment::class.java
            SessionType.SWIM -> SwimSessionFragment::class.java
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
