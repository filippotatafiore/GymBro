package com.example.gymbro.repository

import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.Conversation
import com.example.gymbro.network.ConversationDetails
import com.example.gymbro.network.SharedPlan
import com.example.gymbro.network.UserSession

// Messages: the workout plans shared between the logged user and the users they follow each
// other with (Repository pattern). Everything comes from the server: only online. The functions
// throw ApiException (e.g. 404: the user or the plan does not exist anymore).
interface MessageRepository {

    // The users of Messages, the last shared first
    suspend fun conversations(): List<Conversation>

    // The plans shared with a user, oldest first
    suspend fun conversation(userId: Long): ConversationDetails

    // Sends to the user a copy of one of the plans of the logged user, as it is now
    suspend fun share(userId: Long, planId: Long): SharedPlan

    // The plans received from the user are seen now
    suspend fun markRead(userId: Long)

    // A plan shared (sent or received), with the other user
    suspend fun sharedPlan(shareId: Long): SharedPlan
}

class MessageRepositoryImpl : MessageRepository {

    private val token: String
        get() = UserSession.token ?: throw ApiException(401, "Login required")

    override suspend fun conversations() = ApiClient.getConversations(token)

    override suspend fun conversation(userId: Long) = ApiClient.getConversation(token, userId)

    override suspend fun share(userId: Long, planId: Long) = ApiClient.sharePlan(token, userId, planId)

    override suspend fun markRead(userId: Long) = ApiClient.markConversationRead(token, userId)

    override suspend fun sharedPlan(shareId: Long) = ApiClient.getSharedPlan(token, shareId)
}
