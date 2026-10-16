package com.example.gymbro.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Database operations on the finished sessions saved on this phone (Room writes the SQL code).
// clientId identifies a session also before it has an id on the server.
@Dao
interface SessionDao {

    // Returns the id of the new row
    @Insert
    suspend fun insert(session: SessionEntity): Long

    // The sessions of a user to show, newest first. The Flow emits them again at every change.
    @Query("SELECT * FROM training_sessions WHERE userId = :userId AND deleted = 0 ORDER BY startTime DESC")
    fun observe(userId: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM training_sessions WHERE userId = :userId AND deleted = 0 ORDER BY startTime DESC")
    suspend fun getAll(userId: Long): List<SessionEntity>

    // All the rows of a user, also those deleted and waiting for the server
    @Query("SELECT * FROM training_sessions WHERE userId = :userId")
    suspend fun getEveryRow(userId: Long): List<SessionEntity>

    @Query("SELECT * FROM training_sessions WHERE userId = :userId AND clientId = :clientId LIMIT 1")
    suspend fun get(userId: Long, clientId: String): SessionEntity?

    // Saved only on this phone: to send, oldest first
    @Query("SELECT * FROM training_sessions WHERE userId = :userId AND serverId IS NULL AND deleted = 0 ORDER BY startTime")
    suspend fun getUnsent(userId: Long): List<SessionEntity>

    // Deleted on this phone: to delete on the server
    @Query("SELECT * FROM training_sessions WHERE userId = :userId AND deleted = 1")
    suspend fun getDeleted(userId: Long): List<SessionEntity>

    // The session was saved on the server: its id there, and whether it was published
    @Query("UPDATE training_sessions SET serverId = :serverId, publishedPostId = :publishedPostId WHERE id = :id")
    suspend fun setSent(id: Long, serverId: Long, publishedPostId: Long?)

    @Query("UPDATE training_sessions SET deleted = :deleted WHERE userId = :userId AND clientId = :clientId")
    suspend fun setDeleted(userId: Long, clientId: String, deleted: Boolean)

    @Query("UPDATE training_sessions SET publishedPostId = :postId WHERE userId = :userId AND serverId = :serverId")
    suspend fun setPublished(userId: Long, serverId: Long, postId: Long)

    // Post deleted: its session can be published again
    @Query("UPDATE training_sessions SET publishedPostId = NULL WHERE userId = :userId AND publishedPostId = :postId")
    suspend fun clearPublished(userId: Long, postId: Long)

    @Query("DELETE FROM training_sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM training_sessions WHERE userId = :userId AND clientId = :clientId")
    suspend fun delete(userId: Long, clientId: String)

    // The copies of the sessions of the server (replaced by those downloaded again)
    @Query("DELETE FROM training_sessions WHERE userId = :userId AND serverId IS NOT NULL AND deleted = 0")
    suspend fun deleteSynced(userId: Long)

    // Account deleted: all the sessions of the user
    @Query("DELETE FROM training_sessions WHERE userId = :userId")
    suspend fun deleteAll(userId: Long)
}
