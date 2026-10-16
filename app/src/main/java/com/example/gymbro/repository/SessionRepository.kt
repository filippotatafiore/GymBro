package com.example.gymbro.repository

import androidx.room.withTransaction
import com.example.gymbro.GymBroApp
import com.example.gymbro.data.SessionEntity
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.UserSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// The finished sessions of the logged user (LAST SESSIONS and calendar of the home): the single
// place where the pages read and change them (Repository pattern). The pages always show the
// sessions saved on this phone (Room, see SessionEntity), so they can be seen also offline; the
// server keeps them for all the phones of the user.
// - a finished session is saved on this phone first, then sent to the server; offline it waits
//   and is sent at the next sync
// - a session deleted offline is hidden at once and deleted on the server at the next sync
// - sync: sends what changed on this phone, then downloads the sessions of the server (e.g. those
//   finished on another phone)
object SessionRepository {

    private lateinit var app: GymBroApp
    private val dao get() = app.database.sessionDao()

    // The last download failed (e.g. no connection): the home shows it if there are no sessions
    private val failed = MutableStateFlow(false)
    val refreshFailed: StateFlow<Boolean> = failed.asStateFlow()

    // One sync at a time: the same session is never sent twice in parallel
    private val mutex = Mutex()

    fun init(app: GymBroApp) {
        this.app = app
    }

    // The sessions of the logged user saved on this phone, newest first, updated at every change
    fun sessions(): Flow<List<TrainingSession>> {
        val userId = UserSession.userId ?: return flowOf(emptyList())
        return dao.observe(userId).map { rows -> rows.map { it.toSession() } }
    }

    // The same, read once
    suspend fun all(): List<TrainingSession> {
        val userId = UserSession.userId ?: return emptyList()
        return dao.getAll(userId).map { it.toSession() }
    }

    // Sends the sessions finished or deleted on this phone, then downloads all the sessions of the
    // server. Throws ApiException (e.g. server not reachable): the sessions on this phone stay.
    suspend fun sync() {
        val userId = UserSession.userId ?: return
        val token = UserSession.token ?: return
        mutex.withLock {
            try {
                for (row in dao.getUnsent(userId)) {
                    try {
                        send(token, row)
                    } catch (e: ApiException) {
                        // refused by the server (deleted, see send): the others are sent
                        if (e.code != 400) throw e
                    }
                }
                for (row in dao.getDeleted(userId)) deleteOnServer(token, row)
                val downloaded = ApiClient.getSessions(token)
                replaceCopies(userId, downloaded)
                failed.value = false
            } catch (e: ApiException) {
                failed.value = true
                throw e
            }
        }
    }

    // Saves a finished session of the logged user on this phone (it appears at once in LAST
    // SESSIONS), then sends it. Returns it as saved on the server, or null if the server is not
    // reachable (it is sent at the next sync).
    suspend fun save(session: TrainingSession): TrainingSession? {
        val userId = UserSession.userId ?: return null
        val token = UserSession.token ?: return null
        val localId = dao.insert(SessionEntity.of(userId, session))
        return mutex.withLock {
            val row = dao.getEveryRow(userId).firstOrNull { it.id == localId } ?: return@withLock null
            try {
                send(token, row)
            } catch (e: ApiException) {
                null
            }
        }
    }

    // The session as saved on the server: sent now if it was finished offline (before publishing
    // it). Throws ApiException if the server is not reachable.
    suspend fun sent(session: TrainingSession): TrainingSession {
        if (session.id != 0L) return session
        val userId = UserSession.userId ?: throw ApiException(401, "Login required")
        val token = UserSession.token ?: throw ApiException(401, "Login required")
        return mutex.withLock {
            val row = dao.get(userId, session.clientId) ?: throw ApiException(404, "Session not found")
            if (row.serverId != null) row.toSession() else send(token, row)
        }
    }

    // Removes a session from LAST SESSIONS and from the calendar: at once on this phone, on the
    // server now or (offline) at the next sync. Throws ApiException for the other errors.
    suspend fun delete(session: TrainingSession) {
        val userId = UserSession.userId ?: return
        val token = UserSession.token ?: return
        mutex.withLock {
            // never sent: only on this phone
            if (session.id == 0L) {
                dao.delete(userId, session.clientId)
                return
            }
            dao.setDeleted(userId, session.clientId, true)
            val row = dao.get(userId, session.clientId) ?: return
            try {
                deleteOnServer(token, row)
            } catch (e: ApiException) {
                // offline: deleted at the next sync; another error: the session is shown again
                if (e.code == 0) OfflineNotice.show(app) else {
                    dao.setDeleted(userId, session.clientId, false)
                    throw e
                }
            }
        }
    }

    // After publishing a session / deleting its post (the server knows it already)
    suspend fun markPublished(session: TrainingSession, postId: Long) {
        val userId = UserSession.userId ?: return
        dao.setPublished(userId, session.id, postId)
    }

    suspend fun markUnpublished(postId: Long) {
        val userId = UserSession.userId ?: return
        dao.clearPublished(userId, postId)
    }

    // Logout: the sessions stay on this phone for when the user logs in again
    fun clear() {
        failed.value = false
    }

    // Account deleted
    suspend fun deleteAll(userId: Long) {
        dao.deleteAll(userId)
    }

    // Sends a session saved only on this phone; once saved on the server it gets its id there.
    // Refused by the server (invalid data): deleted, sending it again would always fail.
    private suspend fun send(token: String, row: SessionEntity): TrainingSession {
        try {
            val saved = ApiClient.saveSession(token, row.toSession())
            dao.setSent(row.id, saved.id, saved.publishedPostId ?: row.publishedPostId)
            return saved.copy(localId = row.id)
        } catch (e: ApiException) {
            if (e.code == 400) dao.delete(row.id)
            throw e
        }
    }

    private suspend fun deleteOnServer(token: String, row: SessionEntity) {
        try {
            ApiClient.deleteSession(token, row.serverId ?: return)
        } catch (e: ApiException) {
            // already deleted (e.g. from another phone)
            if (e.code != 404) throw e
        }
        dao.delete(row.id)
    }

    // The copies of the sessions of the server are replaced by those just downloaded (also the
    // sessions finished or deleted on another phone). The sessions waiting on this phone stay.
    private suspend fun replaceCopies(userId: Long, downloaded: List<TrainingSession>) {
        app.database.withTransaction {
            val rows = dao.getEveryRow(userId)
            val deletedHere = rows.filter { it.deleted }.mapNotNull { it.serverId }.toSet()
            val unsent = rows.filter { it.serverId == null && !it.deleted }.associateBy { it.clientId }
            dao.deleteSynced(userId)
            for (session in downloaded) {
                if (session.id in deletedHere) continue
                // sent before, but the answer of the server was lost: the same session
                unsent[session.clientId]?.let { dao.delete(it.id) }
                dao.insert(SessionEntity.of(userId, session, serverId = session.id))
            }
        }
    }
}
