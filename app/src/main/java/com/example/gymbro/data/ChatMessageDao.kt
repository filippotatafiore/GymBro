package com.example.gymbro.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Database operations on the messages of the chats with the AI coach.
// planId: the chat about that workout plan, null = the general chat ("IS" also matches null).
@Dao
interface ChatMessageDao {

    @Insert
    suspend fun insert(message: ChatMessage): Long

    // One conversation of a user, oldest message first. The Flow emits it again every time it changes.
    @Query("SELECT * FROM chat_messages WHERE userId = :userId AND planId IS :planId ORDER BY id")
    fun getAll(userId: Long, planId: Long?): Flow<List<ChatMessage>>

    // The newest messages of one conversation, newest first
    @Query("SELECT * FROM chat_messages WHERE userId = :userId AND planId IS :planId ORDER BY id DESC LIMIT :count")
    suspend fun getLast(userId: Long, planId: Long?, count: Int): List<ChatMessage>

    // New chat (or plan deleted): deletes the conversation
    @Query("DELETE FROM chat_messages WHERE userId = :userId AND planId IS :planId")
    suspend fun deleteAll(userId: Long, planId: Long?)
}
