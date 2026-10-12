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


import androidx.fragment.app.FragmentActivity
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.home.HomeFragment
import com.example.gymbro.session.ActiveSession
import com.example.gymbro.session.GymSessionFragment
import com.example.gymbro.session.RunSessionFragment
import com.example.gymbro.session.SessionType
import kotlinx.coroutines.launch


// Home page. Its middle part shows the home (start training + last sessions) or, while a
// session is in progress, the session itself: it stays there when changing page and coming back.
// FragmentActivity (instead of ComponentActivity) is needed to host these parts (fragments).
class MainActivity : FragmentActivity() {

    private lateinit var bottomNav: BottomNavBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()

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

    // Saves the session in the database (it appears in LAST SESSIONS) and goes back to the home
    fun stopAndSaveSession() {
        val type = ActiveSession.type ?: return
        val session = TrainingSession(
            type = type,
            startTime = ActiveSession.startTime,
            durationMillis = ActiveSession.elapsedMillis()
        )
        ActiveSession.clear()

        // Saved in background: database operations must not run on the main thread
        val app = gymBroApp
        app.applicationScope.launch {
            app.database.trainingSessionDao().insert(session)
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
