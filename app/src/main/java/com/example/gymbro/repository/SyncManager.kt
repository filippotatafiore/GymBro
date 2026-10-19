package com.example.gymbro.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

// Synchronization between this phone and the server: sends everything changed on this phone
// (also offline: workout plans, personal information, look, active plan, finished and deleted
// sessions), then downloads what changed on the server. It runs:
// - as soon as the phone is connected to a network (e.g. back online), while the app is open
// - when the app starts and when the home page is shown
interface SyncManager {

    // In background: the synchronization completes also if the page is closed meanwhile
    fun syncInBackground()

    // false if the server is not reachable: the changes stay on this phone, sent next time.
    // A synchronization already running is not started twice.
    suspend fun sync(): Boolean
}

// scope: of the app
class SyncManagerImpl(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sessions: SessionRepository,
    private val plans: PlanRepository,
    private val users: UserRepository
) : SyncManager {

    private val mutex = Mutex()

    // Synchronizes every time the phone is connected to a network (called also at once if the
    // phone is online now, e.g. app started)
    fun start() {
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        delay(NETWORK_READY_DELAY_MS)
                        sync()
                    }
                }
            }
        )
    }

    override fun syncInBackground() {
        scope.launch { sync() }
    }

    override suspend fun sync(): Boolean {
        if (!UserSession.isLoggedIn || !mutex.tryLock()) return false
        try {
            // sessions first: the home shows if the server is not reachable (refreshFailed)
            val steps = listOf<suspend () -> Unit>({ sessions.sync() }, { plans.sync() }, { users.push() })
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

    companion object {
        // Time for the new connection to be ready before the first request
        private const val NETWORK_READY_DELAY_MS = 1_500L
    }
}
