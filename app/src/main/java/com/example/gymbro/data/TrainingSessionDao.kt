package com.example.gymbro.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Database operations on the finished sessions (Room writes the SQL code behind them)
@Dao
interface TrainingSessionDao {

    // Returns the id of the new session
    @Insert
    suspend fun insert(session: TrainingSession): Long

    // Sessions of one user, newest first. The Flow emits the list again every time the table changes.
    @Query("SELECT * FROM training_sessions WHERE userId = :userId ORDER BY startTime DESC")
    fun getAll(userId: Long): Flow<List<TrainingSession>>

    // Sessions of one user that are not published on the profile yet, newest first
    @Query("SELECT * FROM training_sessions WHERE userId = :userId AND publishedPostId IS NULL ORDER BY startTime DESC")
    suspend fun getUnpublished(userId: Long): List<TrainingSession>

    @Query("UPDATE training_sessions SET publishedPostId = :postId WHERE id = :sessionId")
    suspend fun setPublished(sessionId: Long, postId: Long)

    // The post was deleted from the profile: the session can be published again
    @Query("UPDATE training_sessions SET publishedPostId = NULL WHERE publishedPostId = :postId")
    suspend fun clearPublished(postId: Long)

    @Query("DELETE FROM training_sessions WHERE id = :sessionId")
    suspend fun delete(sessionId: Long)
}
