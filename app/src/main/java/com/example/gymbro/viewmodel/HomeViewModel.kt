package com.example.gymbro.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.gymbro.R
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.network.ApiException
import com.example.gymbro.repository.Notices
import com.example.gymbro.repository.ProfileRepository
import com.example.gymbro.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// The finished sessions of the home page (MainActivity): LAST SESSIONS (HomeFragment), to publish
// or delete, and the calendar (CalendarDrawer). The sessions saved on this phone, also offline,
// synchronized with the server by MainViewModel every time the page is shown.
class HomeViewModel(
    private val sessions: SessionRepository,
    private val profiles: ProfileRepository,
    private val notices: Notices,
    private val appScope: CoroutineScope
) : ViewModel() {

    // refreshFailed: the last download failed (shown if there are no sessions)
    data class State(val sessions: List<TrainingSession>, val refreshFailed: Boolean)

    // null until read from the database; then updated every time a session is saved, published,
    // deleted or downloaded
    val state: StateFlow<State?> =
        combine(sessions.sessions(), sessions.refreshFailed) { list, failed -> State(list, failed) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The calendar: the sessions of every day (null until read from the database)
    val sessionsByDay: StateFlow<Map<LocalDate, List<TrainingSession>>?> =
        sessions.sessions()
            .map { list -> list.groupBy { Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault()).toLocalDate() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Sessions being published right now (clientId: avoids publishing twice with a double tap)
    private val publishing = mutableSetOf<String>()

    // Publishes the session on the profile (its button becomes a check mark). Only online: a session
    // finished offline is sent to the server first.
    fun publish(session: TrainingSession) {
        if (!publishing.add(session.clientId)) return
        viewModelScope.launch {
            try {
                profiles.publishSession(session)
                notices.show(R.string.session_published)
            } catch (e: ApiException) {
                notices.showError(e)
            } finally {
                publishing.remove(session.clientId)
            }
        }
    }

    // Deletes the session from LAST SESSIONS: at once on this phone, on the server now or (offline)
    // when it is reachable again (in background: it completes also if the page is closed)
    fun delete(session: TrainingSession) {
        appScope.launch {
            try {
                sessions.delete(session)
            } catch (e: ApiException) {
                notices.showError(e)
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { HomeViewModel(container.sessions, container.profiles, container.notices, container.appScope) }
        }
    }
}
