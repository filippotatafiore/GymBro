package com.example.gymbro

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.room.Room
import com.example.gymbro.data.AppDatabase
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.repository.Notices
import com.example.gymbro.session.ActiveSession
import com.example.gymbro.session.RunTracker
import com.example.gymbro.session.RunTrackingService
import com.example.gymbro.session.SwimTracker
import com.example.gymbro.session.mapsRenderer
import com.google.android.gms.maps.MapsInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Created by Android when the app starts (see android:name in AndroidManifest.xml),
// before any activity: holds what is shared by the whole app
class GymBroApp : Application() {

    // Local database (Room): finished sessions, workout plans, posts and profile of the user (also
    // to see and change them offline, see the repository package), chats with the AI coach
    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "gymbro.db")
            // Converts the saved data to the new structure of the tables
            .addMigrations(
                AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8
            )
            // While developing: for older structures without a migration, the local database is
            // recreated empty instead of being converted
            .fallbackToDestructiveMigration()
            .build()
    }

    // Coroutines that must complete even if the page that started them is closed (e.g. saving a session)
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // The repositories: the data of the user saved on this phone, synchronized with the server
    // (see AppContainer: given to the ViewModels of the pages)
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        ActiveSession.init(this)
        RunTracker.init(this)
        SwimTracker.init(this)
        RunTrackingService.createNotificationChannels(this)
        // Google Maps: asks the latest renderer, and remembers the one available on this phone
        MapsInitializer.initialize(this, MapsInitializer.Renderer.LATEST) { renderer -> mapsRenderer = renderer }
        // before any page: the light / dark theme and the favorite color of the last user
        Appearance.init(this)
        UserSession.init(this)
        ApiClient.init(this)
        container = AppContainer(this)
        showNotices(container.notices)
        // last: it may synchronize at once (if the phone is online)
        container.start()
    }

    // The messages of the work done in background (e.g. a session saved offline), whatever page
    // is open: as toasts
    private fun showNotices(notices: Notices) {
        applicationScope.launch {
            notices.shown.collect { notice ->
                val error = notice.error?.userMessage(this@GymBroApp)
                val text = when {
                    notice.text != null && error != null -> getString(notice.text, error)
                    notice.text != null -> getString(notice.text)
                    else -> error.orEmpty()
                }
                Toast.makeText(this@GymBroApp, text, if (notice.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// Shortcut to reach the application object from activities and fragments
val Context.gymBroApp: GymBroApp
    get() = applicationContext as GymBroApp
