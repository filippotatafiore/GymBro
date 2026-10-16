package com.example.gymbro.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Database operations on the profile of the logged user saved on this phone (see ProfileEntity)
@Dao
interface ProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(profile: ProfileEntity)

    // null until the profile is downloaded the first time. The Flow emits it again at every change.
    @Query("SELECT * FROM profiles WHERE userId = :userId")
    fun observe(userId: Long): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE userId = :userId")
    suspend fun get(userId: Long): ProfileEntity?

    @Query("DELETE FROM profiles WHERE userId = :userId")
    suspend fun delete(userId: Long)
}
