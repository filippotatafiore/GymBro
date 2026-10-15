package com.example.gymbro.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

// Database operations on the sessions waiting to be sent to the server (Room writes the SQL code)
@Dao
interface PendingSessionDao {

    // Returns the id of the new row
    @Insert
    suspend fun insert(session: PendingSession): Long

    // Sessions of one user waiting to be sent, oldest first
    @Query("SELECT * FROM training_sessions WHERE userId = :userId ORDER BY startTime")
    suspend fun getAll(userId: Long): List<PendingSession>

    @Query("DELETE FROM training_sessions WHERE id = :id")
    suspend fun delete(id: Long)

    // Account deleted: also the sessions not sent yet
    @Query("DELETE FROM training_sessions WHERE userId = :userId")
    suspend fun deleteAll(userId: Long)
}
