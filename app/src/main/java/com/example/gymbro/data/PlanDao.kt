package com.example.gymbro.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Database operations on the workout plans saved on this phone (see PlanEntity)
@Dao
interface PlanDao {

    // Adds the plan, or replaces the one with the same id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(plan: PlanEntity)

    // The plans of a user, the last changed first. The Flow emits them again at every change.
    @Query("SELECT * FROM plans WHERE userId = :userId AND deleted = 0 ORDER BY updatedAt DESC, id DESC")
    fun observe(userId: Long): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plans WHERE userId = :userId AND deleted = 0 ORDER BY updatedAt DESC, id DESC")
    suspend fun getAll(userId: Long): List<PlanEntity>

    @Query("SELECT * FROM plans WHERE id = :id AND deleted = 0")
    suspend fun get(id: Long): PlanEntity?

    // All the rows of a user, also those deleted and waiting for the server
    @Query("SELECT * FROM plans WHERE userId = :userId")
    suspend fun getEveryRow(userId: Long): List<PlanEntity>

    // Changed on this phone: to send
    @Query("SELECT * FROM plans WHERE userId = :userId AND changed = 1 AND deleted = 0")
    suspend fun getChanged(userId: Long): List<PlanEntity>

    // Deleted on this phone: to delete on the server
    @Query("SELECT * FROM plans WHERE userId = :userId AND deleted = 1")
    suspend fun getDeleted(userId: Long): List<PlanEntity>

    @Query("UPDATE plans SET deleted = 1 WHERE id = :id")
    suspend fun markDeleted(id: Long)

    @Query("DELETE FROM plans WHERE id = :id")
    suspend fun delete(id: Long)

    // The copies of the plans of the server (replaced by those downloaded again)
    @Query("DELETE FROM plans WHERE userId = :userId AND changed = 0 AND deleted = 0")
    suspend fun deleteSynced(userId: Long)

    // Account deleted
    @Query("DELETE FROM plans WHERE userId = :userId")
    suspend fun deleteAll(userId: Long)
}
