package com.example.gymbro.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Database operations on the posts of the logged user saved on this phone (see PostEntity)
@Dao
interface PostDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(posts: List<PostEntity>)

    // The posts of a user, newest first (as the server sorts them). The Flow emits them again at
    // every change.
    @Query("SELECT * FROM posts WHERE userId = :userId ORDER BY createdAt DESC, id DESC")
    fun observe(userId: Long): Flow<List<PostEntity>>

    @Query("DELETE FROM posts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM posts WHERE userId = :userId")
    suspend fun deleteAll(userId: Long)
}
