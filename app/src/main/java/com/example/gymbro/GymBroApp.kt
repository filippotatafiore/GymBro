package com.example.gymbro

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.example.gymbro.data.AppDatabase
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.UserSession
import com.example.gymbro.session.ActiveSession
import com.example.gymbro.session.RunTracker
import com.example.gymbro.session.RunTrackingService
import com.example.gymbro.session.mapsRenderer
import com.google.android.gms.maps.MapsInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Created by Android when the app starts (see android:name in AndroidManifest.xml),
// before any activity: holds what is shared by the whole app
class GymBroApp : Application() {

    // Local database (Room) with the finished training sessions and the chat with the AI coach
    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "gymbro.db")
            // Converts the saved sessions to the new structure of the tables
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
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
        RunTrackingService.createNotificationChannels(this)
        // Google Maps: asks the latest renderer, and remembers the one available on this phone
        MapsInitializer.initialize(this, MapsInitializer.Renderer.LATEST) { renderer -> mapsRenderer = renderer }
        UserSession.init(this)
        ApiClient.init(this)
    }
}

// Shortcut to reach the application object from activities and fragments
val Context.gymBroApp: GymBroApp
    get() = applicationContext as GymBroApp
