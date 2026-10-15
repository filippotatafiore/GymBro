package com.example.gymbro.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// Who wrote a message of the chat. `json` = how the role is written in the requests to the REST API
enum class ChatRole(val json: String) { USER("user"), ASSISTANT("assistant") }

// A message of the chat with the AI coach: one row of the "chat_messages" table of the local
// database. Each user has their own conversations, saved only on this phone: the general chat
// (planId = null) and one chat for each workout plan.
@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,                               // the user of the conversation (id on the server)
    val role: ChatRole,
    val text: String,
    val model: String? = null,                      // coach answers only: the AI model that wrote it
    val createdAt: Long = System.currentTimeMillis(),  // milliseconds since 1970
    val planId: Long? = null,                       // chat about this workout plan (null = general chat)
    @ColumnInfo(defaultValue = "0")
    val planUpdated: Boolean = false                // coach answers only: the coach changed the plan
)
