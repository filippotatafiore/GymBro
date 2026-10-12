package com.example.gymbro.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Database operations on the finished sessions (Room writes the SQL code behind them)
@Dao
interface TrainingSessionDao {

    @Insert
    suspend fun insert(session: TrainingSession)

    // Newest first. The Flow emits the list again every time the table changes.
    @Query("SELECT * FROM training_sessions ORDER BY startTime DESC")
    fun getAll(): Flow<List<TrainingSession>>
}
