package com.example.gymbro.repository

import com.example.gymbro.data.AppDatabase
import com.example.gymbro.data.ChatMessage
import com.example.gymbro.data.ChatRole
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Conversations with the AI coach (chat page) (Repository pattern). The messages are saved in the
// local database (chat_messages table); the answers are asked to the server in background (scope
// of the app), so they arrive and are saved also if the chat page is closed meanwhile.
interface ChatRepository {

    // A conversation: the general chat of a user (planId = null) or their chat about a workout plan,
    // where the coach sees the plan and can change it
    data class Conversation(val userId: Long, val planId: Long?)

    // What the coach is doing in a conversation
    sealed interface Status {
        data object Idle : Status                               // waiting for a message
        data object Waiting : Status                            // writing the answer
        data class Failed(val error: ApiException?) : Status    // the answer did not arrive (null: no
                                                                // answer, e.g. the app was closed meanwhile)
    }

    // The messages of the conversation, oldest first, updated at every message
    fun messages(conversation: Conversation): Flow<List<ChatMessage>>

    fun status(conversation: Conversation): Flow<Status>

    fun isWaiting(conversation: Conversation): Boolean

    // Saves the message of the user and asks the answer of the coach
    fun send(conversation: Conversation, text: String)

    // Asks again the answer to the last message (after an error)
    fun retry(conversation: Conversation)

    // The last message is of the user: it has no answer
    suspend fun hasUnansweredMessage(conversation: Conversation): Boolean

    // The last message of the user has no answer, and no answer is coming (e.g. the app was closed
    // while the coach was writing): shown as an error, so that it can be asked again
    fun answerMissing(conversation: Conversation)

    // New chat (or plan deleted): deletes the conversation (an answer still arriving is discarded)
    fun clear(conversation: Conversation)

    // Account deleted: all the conversations of the user
    suspend fun deleteAll(userId: Long)
}

class ChatRepositoryImpl(
    private val database: AppDatabase,
    private val scope: CoroutineScope,
    private val plans: PlanRepository
) : ChatRepository {

    private val dao get() = database.chatMessageDao()

    private val statuses = MutableStateFlow<Map<ChatRepository.Conversation, ChatRepository.Status>>(emptyMap())
    private val requests = mutableMapOf<ChatRepository.Conversation, Job>()

    override fun messages(conversation: ChatRepository.Conversation): Flow<List<ChatMessage>> =
        dao.getAll(conversation.userId, conversation.planId)

    override fun status(conversation: ChatRepository.Conversation): Flow<ChatRepository.Status> =
        statuses.map { it[conversation] ?: ChatRepository.Status.Idle }.distinctUntilChanged()

    override fun isWaiting(conversation: ChatRepository.Conversation): Boolean =
        statuses.value[conversation] == ChatRepository.Status.Waiting

    private fun setStatus(conversation: ChatRepository.Conversation, status: ChatRepository.Status) {
        statuses.update { it + (conversation to status) }
    }

    override fun send(conversation: ChatRepository.Conversation, text: String) {
        val token = UserSession.token ?: return
        if (isWaiting(conversation)) return

        setStatus(conversation, ChatRepository.Status.Waiting)
        requests[conversation] = scope.launch {
            dao.insert(ChatMessage(userId = conversation.userId, planId = conversation.planId, role = ChatRole.USER, text = text))
            askCoach(conversation, token)
        }
    }

    override fun retry(conversation: ChatRepository.Conversation) {
        val token = UserSession.token ?: return
        if (isWaiting(conversation)) return

        setStatus(conversation, ChatRepository.Status.Waiting)
        requests[conversation] = scope.launch { askCoach(conversation, token) }
    }

    override suspend fun hasUnansweredMessage(conversation: ChatRepository.Conversation): Boolean =
        dao.getLast(conversation.userId, conversation.planId, 1).firstOrNull()?.role == ChatRole.USER

    override fun answerMissing(conversation: ChatRepository.Conversation) {
        if ((statuses.value[conversation] ?: ChatRepository.Status.Idle) == ChatRepository.Status.Idle) {
            setStatus(conversation, ChatRepository.Status.Failed(null))
        }
    }

    override fun clear(conversation: ChatRepository.Conversation) {
        requests.remove(conversation)?.cancel()
        setStatus(conversation, ChatRepository.Status.Idle)
        scope.launch { dao.deleteAll(conversation.userId, conversation.planId) }
    }

    override suspend fun deleteAll(userId: Long) {
        dao.deleteAllOfUser(userId)
    }

    private suspend fun askCoach(conversation: ChatRepository.Conversation, token: String) {
        try {
            // chat about a plan: the changes made offline are sent first, so that the coach sees them
            if (conversation.planId != null) plans.trySync()
            val history = dao.getLast(conversation.userId, conversation.planId, MAX_SENT_MESSAGES).reversed()
            val reply = ApiClient.askCoach(token, history, conversation.planId)
            // the plan changed by the coach: also on this phone
            reply.plan?.let { plans.store(it) }
            dao.insert(
                ChatMessage(
                    userId = conversation.userId, planId = conversation.planId, role = ChatRole.ASSISTANT,
                    text = reply.text, model = reply.model, planUpdated = reply.plan != null
                )
            )
            setStatus(conversation, ChatRepository.Status.Idle)
        } catch (e: ApiException) {
            setStatus(conversation, ChatRepository.Status.Failed(e))
        }
    }

    companion object {
        // Newest messages sent to the server: the server gives to the model those that fit its limits
        private const val MAX_SENT_MESSAGES = 30
    }
}
