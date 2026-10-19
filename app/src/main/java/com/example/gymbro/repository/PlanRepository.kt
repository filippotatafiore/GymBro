package com.example.gymbro.repository

import androidx.room.withTransaction
import com.example.gymbro.data.AppDatabase
import com.example.gymbro.data.PlanEntity
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.PlanRequest
import com.example.gymbro.network.UserSession
import com.example.gymbro.plan.Plan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

// The workout plans of the logged user: the single place where the pages read and change them
// (Repository pattern). The pages show the plans saved on this phone (Room, see PlanEntity), so
// they can be seen, followed in the gym sessions and changed by hand also offline.
// - a change by hand is saved on this phone at once and sent to the server; offline it waits and
//   is sent at the next sync: the version changed offline wins over the one on the server
// - a plan deleted offline is hidden at once and deleted on the server at the next sync
// - what needs the server is done only online: the coach (new plans, changes in the chat), public
//   or private, copies of the plans of other users. Their result is saved here too (store).
interface PlanRepository {

    // A change saved on this phone; synced = also on the server (false: offline, sent later)
    class Saved(val plan: Plan, val synced: Boolean)

    // The plans of the logged user saved on this phone, the last changed first, updated at every change
    fun plans(): Flow<List<Plan>>

    // The same, read once
    suspend fun all(): List<Plan>

    // A plan saved on this phone (null: not downloaded yet, or deleted)
    suspend fun plan(id: Long): Plan?

    // Sends the changes made on this phone (deleted, changed plans), then downloads all the plans
    // of the server (e.g. changed by the coach or on another phone). Throws ApiException (e.g. server
    // not reachable): the plans on this phone stay.
    suspend fun sync()

    // Like sync, but without errors: returns the error (e.g. offline), or null if synced
    suspend fun trySync(): ApiException?

    // A change by hand (exercises, days, name): saved on this phone, then on the server. Offline
    // (synced = false) it is sent at the next sync. The saving completes also if the page is closed.
    // Throws ApiException for the other errors (e.g. 404: the plan does not exist anymore).
    suspend fun update(plan: Plan): Saved

    // Deletes a plan: at once on this phone, on the server now or (offline) at the next sync.
    // Returns false if it was deleted only on this phone for now.
    suspend fun delete(id: Long): Boolean

    // Public (everyone sees it on the profile) or private: only online, like publishing a post.
    // The changes made offline are sent first. Throws ApiException.
    suspend fun setPublic(id: Long, public: Boolean): Plan

    // A new plan written by the AI coach (only online). Throws ApiException.
    suspend fun create(request: PlanRequest): Plan

    // A copy among the plans of the logged user of a public plan of another user, or of a plan
    // received with Messages (the copy already added, if any). Only online. Throws ApiException.
    suspend fun copyPublic(ownerId: Long, planId: Long): Plan
    suspend fun copyShared(shareId: Long): Plan

    // A plan as answered by the server (written or changed by the coach, copied from another user)
    suspend fun store(plan: Plan)

    // Account deleted
    suspend fun deleteAll(userId: Long)
}

// scope: of the app, the changes are saved also if the page is closed meanwhile
class PlanRepositoryImpl(
    private val database: AppDatabase,
    private val scope: CoroutineScope,
    private val notices: Notices
) : PlanRepository {

    private val dao get() = database.planDao()
    private val mutex = Mutex()

    override fun plans(): Flow<List<Plan>> {
        val userId = UserSession.userId ?: return flowOf(emptyList())
        return dao.observe(userId).map { rows -> rows.map { it.toPlan() } }
    }

    override suspend fun all(): List<Plan> {
        val userId = UserSession.userId ?: return emptyList()
        return dao.getAll(userId).map { it.toPlan() }
    }

    override suspend fun plan(id: Long): Plan? = dao.get(id)?.toPlan()

    override suspend fun sync() {
        val userId = UserSession.userId ?: return
        val token = UserSession.token ?: return
        mutex.withLock {
            for (row in dao.getDeleted(userId)) {
                try {
                    ApiClient.deletePlan(token, row.id)
                } catch (e: ApiException) {
                    if (e.code != 404) throw e
                }
                dao.delete(row.id)
            }
            for (row in dao.getChanged(userId)) {
                try {
                    keepIfNotChangedAgain(row, ApiClient.updatePlan(token, row.toPlan()))
                } catch (e: ApiException) {
                    when (e.code) {
                        // deleted on the server meanwhile (e.g. from another phone)
                        404 -> dao.delete(row.id)
                        // refused by the server: the version of the server is downloaded below
                        400 -> dao.save(row.copy(changed = false))
                        else -> throw e
                    }
                }
            }
            val downloaded = ApiClient.getPlans(token)
            database.withTransaction {
                val waiting = dao.getEveryRow(userId).filter { it.changed || it.deleted }.map { it.id }.toSet()
                dao.deleteSynced(userId)
                downloaded.filter { it.id !in waiting }.forEach { dao.save(PlanEntity.of(userId, it)) }
            }
        }
    }

    override suspend fun trySync(): ApiException? =
        try {
            sync()
            null
        } catch (e: ApiException) {
            e
        }

    override suspend fun update(plan: Plan): PlanRepository.Saved {
        val userId = UserSession.userId ?: throw ApiException(401, "Login required")
        val token = UserSession.token ?: throw ApiException(401, "Login required")
        val row = PlanEntity.of(userId, plan.copy(updatedAt = Instant.now()), changed = true)
        dao.save(row)
        return scope.async {
            mutex.withLock {
                try {
                    val saved = ApiClient.updatePlan(token, row.toPlan())
                    keepIfNotChangedAgain(row, saved)
                    PlanRepository.Saved(saved, synced = true)
                } catch (e: ApiException) {
                    when (e.code) {
                        0 -> {
                            notices.savedOffline()
                            PlanRepository.Saved(row.toPlan(), synced = false)
                        }
                        404 -> {
                            dao.delete(row.id)
                            throw e
                        }
                        else -> {
                            // refused by the server: the version of the server comes back
                            dao.save(row.copy(changed = false))
                            throw e
                        }
                    }
                }
            }
        }.await()
    }

    override suspend fun delete(id: Long): Boolean {
        val token = UserSession.token ?: return false
        dao.markDeleted(id)
        return scope.async {
            mutex.withLock {
                try {
                    ApiClient.deletePlan(token, id)
                    dao.delete(id)
                    true
                } catch (e: ApiException) {
                    if (e.code == 404) dao.delete(id) else notices.savedOffline()
                    e.code == 404
                }
            }
        }.await()
    }

    override suspend fun setPublic(id: Long, public: Boolean): Plan {
        val token = UserSession.token ?: throw ApiException(401, "Login required")
        sync()
        return ApiClient.setPlanPublic(token, id, public).also { store(it) }
    }

    // Also on this phone: it can be seen, followed and changed offline
    override suspend fun create(request: PlanRequest): Plan {
        val token = UserSession.token ?: throw ApiException(401, "Login required")
        return ApiClient.createPlan(token, request).also { store(it) }
    }

    override suspend fun copyPublic(ownerId: Long, planId: Long): Plan {
        val token = UserSession.token ?: throw ApiException(401, "Login required")
        return ApiClient.copyPublicPlan(token, ownerId, planId).also { store(it) }
    }

    override suspend fun copyShared(shareId: Long): Plan {
        val token = UserSession.token ?: throw ApiException(401, "Login required")
        return ApiClient.saveSharedPlan(token, shareId).also { store(it) }
    }

    override suspend fun store(plan: Plan) {
        val userId = UserSession.userId ?: return
        dao.save(PlanEntity.of(userId, plan))
    }

    override suspend fun deleteAll(userId: Long) {
        dao.deleteAll(userId)
    }

    // The plan saved by the server replaces the one sent, unless it was changed again on this phone
    // meanwhile (that change will be sent too)
    private suspend fun keepIfNotChangedAgain(sent: PlanEntity, saved: Plan) {
        val now = dao.get(sent.id) ?: return
        if (now.updatedAt == sent.updatedAt) dao.save(PlanEntity.of(sent.userId, saved))
    }
}
