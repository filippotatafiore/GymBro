package com.example.gymbro.session

import android.content.Context
import com.example.gymbro.data.PendingSession
import com.example.gymbro.data.PendingSessionDao
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.gymBroApp
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// The finished sessions of the logged user (LAST SESSIONS and calendar of the home), saved on the
// server: the same ones on every phone. A finished session is first saved in the local database
// (PendingSession) and then sent to the server; if the server is not reachable it waits there and
// is sent at the next refresh. The pages show [sessions], updated after every change.
object TrainingSessions {

    private val state = MutableStateFlow<List<TrainingSession>>(emptyList())
    val sessions: StateFlow<List<TrainingSession>> = state.asStateFlow()

    // The last download failed (e.g. no connection): the home shows it if the list is empty
    private val failed = MutableStateFlow(false)
    val refreshFailed: StateFlow<Boolean> = failed.asStateFlow()

    // The user of the sessions in the list (another user logs in: the list starts empty again)
    private var listUserId: Long? = null

    // One sending at a time: the same waiting session is never sent twice in parallel
    private val mutex = Mutex()

    // Sends the sessions waiting on this phone, then downloads all the sessions of the logged user.
    // Throws ApiException (e.g. server not reachable): the list stays as it was.
    suspend fun refresh(context: Context) {
        val userId = UserSession.userId ?: return
        val token = UserSession.token ?: return
        useListOf(userId)
        val dao = context.gymBroApp.database.pendingSessionDao()
        mutex.withLock {
            for (pending in dao.getAll(userId)) {
                // not reachable: the others would fail too, they will be sent next time
                send(dao, token, pending.id, pending.toSession()) ?: break
            }
            val downloaded = try {
                ApiClient.getSessions(token)
            } catch (e: ApiException) {
                failed.value = true
                throw e
            }
            failed.value = false
            if (listUserId == userId) state.value = downloaded
        }
    }

    // Saves a finished session of the logged user. Returns it as saved on the server, or null if
    // the server was not reachable (it waits on this phone and is sent later).
    suspend fun save(context: Context, session: TrainingSession): TrainingSession? {
        val userId = UserSession.userId ?: return null
        val token = UserSession.token ?: return null
        useListOf(userId)
        val dao = context.gymBroApp.database.pendingSessionDao()
        val localId = dao.insert(PendingSession.of(userId, session))
        return mutex.withLock { send(dao, token, localId, session) }
    }

    // Removes a session from LAST SESSIONS and from the calendar (throws ApiException)
    suspend fun delete(session: TrainingSession) {
        val token = UserSession.token ?: return
        ApiClient.deleteSession(token, session.id)
        state.update { list -> list.filter { it.id != session.id } }
    }

    // After publishing a session / deleting its post (the server knows it already)
    fun markPublished(sessionId: Long, postId: Long) {
        state.update { list -> list.map { if (it.id == sessionId) it.copy(publishedPostId = postId) else it } }
    }

    fun markUnpublished(postId: Long) {
        state.update { list -> list.map { if (it.publishedPostId == postId) it.copy(publishedPostId = null) else it } }
    }

    // Logout or account deleted: the list is emptied
    fun clear() {
        listUserId = null
        state.value = emptyList()
        failed.value = false
    }

    private fun useListOf(userId: Long) {
        if (listUserId != userId) {
            listUserId = userId
            state.value = emptyList()
        }
    }

    // Sends a waiting session; once saved on the server it is deleted from this phone and added to
    // the list. null if the server is not reachable (it keeps waiting).
    private suspend fun send(dao: PendingSessionDao, token: String, localId: Long, session: TrainingSession): TrainingSession? =
        try {
            val saved = ApiClient.saveSession(token, session)
            dao.delete(localId)
            state.update { list -> (list.filter { it.id != saved.id } + saved).sortedByDescending { it.startTime } }
            saved
        } catch (e: ApiException) {
            // refused by the server (invalid data): sending it again would always fail
            if (e.code == 400) dao.delete(localId)
            null
        }
}

// The most recent gym session that followed the plan with this id (null if none): it tells which
// day of the plan was done last. Plain Kotlin: tested in GymSessionsTest.kt
fun lastSessionOfPlan(sessions: List<TrainingSession>, planId: Long): TrainingSession? =
    sessions.filter { it.type == SessionType.GYM && it.planId == planId && it.planDay != null }
        .maxByOrNull { it.startTime }
