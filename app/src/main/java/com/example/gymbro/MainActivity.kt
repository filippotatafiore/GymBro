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


import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.home.HomeFragment
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.session.ActiveSession
import com.example.gymbro.session.GymSessionFragment
import com.example.gymbro.session.RunDistance
import com.example.gymbro.session.RunSessionFragment
import com.example.gymbro.session.SessionType
import com.example.gymbro.session.WorkoutPlan
import com.example.gymbro.session.publishSession
import kotlinx.coroutines.launch


// Home page. Its middle part shows the home (start training + last sessions) or, while a
// session is in progress, the session itself: it stays there when changing page and coming back.
// FragmentActivity (instead of ComponentActivity) is needed to host these parts (fragments).
class MainActivity : FragmentActivity() {

    private lateinit var bottomNav: BottomNavBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()

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
    }

    override fun onResume() {
        super.onResume()
        showCurrentPage()
        bottomNav.refresh()
    }

    override fun onPause() {
        super.onPause()
        bottomNav.stopGlow()
    }


    // --------------------------------------------------------- SESSION

    fun startSession(type: SessionType) {
        ActiveSession.start(type)
        onSessionChanged()
    }

    // Saves the session in the database (it appears in LAST SESSIONS of this user), publishes it on
    // the profile if requested, and goes back to the home
    fun stopAndSaveSession(publish: Boolean) {
        val userId = UserSession.userId ?: return
        val type = ActiveSession.type ?: return
        val session = TrainingSession(
            userId = userId,
            type = type,
            startTime = ActiveSession.startTime,
            durationMillis = ActiveSession.elapsedMillis(),
            distanceKm = if (type == SessionType.RUN) RunDistance.placeholderKm() else null,
            exercises = if (type == SessionType.GYM) WorkoutPlan.exercises else null
        )
        ActiveSession.clear()

        // Saved (and published) in background: database and network must not block the app.
        // applicationScope: the work completes even if this page is closed meanwhile.
        val app = gymBroApp
        app.applicationScope.launch {
            val id = app.database.trainingSessionDao().insert(session)
            if (publish) {
                try {
                    publishSession(app, session.copy(id = id))
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
