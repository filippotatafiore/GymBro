package com.example.gymbro.repository

import android.net.ConnectivityManager
import android.net.Network
import com.example.gymbro.GymBroApp
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.UserSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

// Synchronization between this phone and the server: sends everything changed on this phone
// (also offline: workout plans, personal information, look, active plan, finished and deleted
// sessions), then downloads what changed on the server. It runs:
// - as soon as the phone is connected to a network (e.g. back online), while the app is open
// - when the app starts and when the home page is shown
object SyncManager {

    // Time for the new connection to be ready before the first request
    private const val NETWORK_READY_DELAY_MS = 1_500L

    private lateinit var app: GymBroApp
    private val mutex = Mutex()

    fun init(app: GymBroApp) {
        this.app = app
        // called also at once if the phone is online now (e.g. app started)
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    app.applicationScope.launch {
                        delay(NETWORK_READY_DELAY_MS)
                        sync()
                    }
                }
            }
        )
    }

    // In background: the synchronization completes also if the page is closed meanwhile
    fun syncInBackground() {
        app.applicationScope.launch { sync() }
    }

    // false if the server is not reachable: the changes stay on this phone, sent next time.
    // A synchronization already running is not started twice.
    suspend fun sync(): Boolean {
        if (!UserSession.isLoggedIn || !mutex.tryLock()) return false
        try {
            // sessions first: the home shows if the server is not reachable (refreshFailed)
            val steps = listOf<suspend () -> Unit>(
                { SessionRepository.sync() }, { PlanRepository.sync() }, { UserRepository.push() }
            )
            var synced = true
            for (step in steps) {
                try {
                    step()
                } catch (e: ApiException) {
                    synced = false
                    // not reachable: the other steps would fail too
                    if (e.code == 0) break
                }
            }
            return synced
        } finally {
            mutex.unlock()
        }
    }
}
