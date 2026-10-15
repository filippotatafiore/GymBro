package com.example.gymbro.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Database operations on the messages of the chat with the AI coach
@Dao
interface ChatMessageDao {

    @Insert
    suspend fun insert(message: ChatMessage): Long

    // Conversation of one user, oldest message first. The Flow emits it again every time it changes.
    @Query("SELECT * FROM chat_messages WHERE userId = :userId ORDER BY id")
    fun getAll(userId: Long): Flow<List<ChatMessage>>

    // The newest messages of one user, newest first
    @Query("SELECT * FROM chat_messages WHERE userId = :userId ORDER BY id DESC LIMIT :count")
    suspend fun getLast(userId: Long, count: Int): List<ChatMessage>

    // New chat: deletes the conversation of the user
    @Query("DELETE FROM chat_messages WHERE userId = :userId")
    suspend fun deleteAll(userId: Long)
}
