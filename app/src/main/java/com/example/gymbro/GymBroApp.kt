package com.example.gymbro

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.example.gymbro.data.AppDatabase
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.UserSession
import com.example.gymbro.repository.PlanRepository
import com.example.gymbro.repository.ProfileRepository
import com.example.gymbro.repository.SessionRepository
import com.example.gymbro.repository.SyncManager
import com.example.gymbro.repository.UserRepository
import com.example.gymbro.session.ActiveSession
import com.example.gymbro.session.RunTracker
import com.example.gymbro.session.RunTrackingService
import com.example.gymbro.session.SwimTracker
import com.example.gymbro.session.mapsRenderer
import com.google.android.gms.maps.MapsInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
        // the data of the user saved on this phone, synchronized with the server
        SessionRepository.init(this)
        PlanRepository.init(this)
        ProfileRepository.init(this)
        UserRepository.init(this)
        // last: it may synchronize at once (if the phone is online)
        SyncManager.init(this)
    }
}

// Shortcut to reach the application object from activities and fragments
val Context.gymBroApp: GymBroApp
    get() = applicationContext as GymBroApp
