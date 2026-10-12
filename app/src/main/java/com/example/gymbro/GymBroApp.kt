package com.example.gymbro

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.example.gymbro.data.AppDatabase
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.UserSession
import com.example.gymbro.session.ActiveSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Created by Android when the app starts (see android:name in AndroidManifest.xml),
// before any activity: holds what is shared by the whole app
class GymBroApp : Application() {

    // Local database (Room) with the finished training sessions
    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "gymbro.db")
            // While developing: when the structure of the tables changes (new version in
            // AppDatabase), the local database is recreated empty instead of being converted
            .fallbackToDestructiveMigration()
            .build()
    }

    // Coroutines that must complete even if the page that started them is closed (e.g. saving a session)
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        ActiveSession.init(this)
        UserSession.init(this)
        ApiClient.init(this)
    }
}

// Shortcut to reach the application object from activities and fragments
val Context.gymBroApp: GymBroApp
    get() = applicationContext as GymBroApp
