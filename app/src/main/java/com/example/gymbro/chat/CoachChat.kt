package com.example.gymbro.chat

import android.content.Context
import com.example.gymbro.GymBroApp
import com.example.gymbro.data.ChatMessage
import com.example.gymbro.data.ChatRole
import com.example.gymbro.gymBroApp
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Conversations with the AI coach (chat page). The messages are saved in the local database
// (chat_messages table); the answers are asked to the server in background (applicationScope), so
// they arrive and are saved also if the chat page is closed meanwhile.
object CoachChat {

    // A conversation: the general chat of a user (planId = null) or their chat about a workout plan,
    // where the coach sees the plan and can change it
    data class Conversation(val userId: Long, val planId: Long?)

    // What the coach is doing in a conversation
    sealed interface Status {
        data object Idle : Status                           // waiting for a message
        data object Waiting : Status                        // writing the answer
        data class Failed(val message: String) : Status     // the answer did not arrive
    }

    // Newest messages sent to the server: the server gives to the model those that fit its limits
    private const val MAX_SENT_MESSAGES = 30

    private val statuses = MutableStateFlow<Map<Conversation, Status>>(emptyMap())
    private val requests = mutableMapOf<Conversation, Job>()

    fun status(conversation: Conversation): Flow<Status> =
        statuses.map { it[conversation] ?: Status.Idle }.distinctUntilChanged()

    fun isWaiting(conversation: Conversation): Boolean = statuses.value[conversation] == Status.Waiting

    private fun setStatus(conversation: Conversation, status: Status) {
        statuses.update { it + (conversation to status) }
    }

    // Saves the message of the user and asks the answer of the coach
    fun send(context: Context, conversation: Conversation, text: String) {
        val token = UserSession.token ?: return
        if (isWaiting(conversation)) return
        val app = context.gymBroApp

        setStatus(conversation, Status.Waiting)
        requests[conversation] = app.applicationScope.launch {
            app.database.chatMessageDao().insert(
                ChatMessage(userId = conversation.userId, planId = conversation.planId, role = ChatRole.USER, text = text)
            )
            askCoach(app, conversation, token)
        }
    }

    // Asks again the answer to the last message (after an error)
    fun retry(context: Context, conversation: Conversation) {
        val token = UserSession.token ?: return
        if (isWaiting(conversation)) return
        val app = context.gymBroApp

        setStatus(conversation, Status.Waiting)
        requests[conversation] = app.applicationScope.launch { askCoach(app, conversation, token) }
    }

    // The last message of the user has no answer, and no answer is coming (e.g. the app was closed
    // while the coach was writing): shown as an error, so that it can be asked again
    fun answerMissing(conversation: Conversation, message: String) {
        if ((statuses.value[conversation] ?: Status.Idle) == Status.Idle) setStatus(conversation, Status.Failed(message))
    }

    // New chat (or plan deleted): deletes the conversation (an answer still arriving is discarded)
    fun clear(context: Context, conversation: Conversation) {
        val app = context.gymBroApp
        requests.remove(conversation)?.cancel()
        setStatus(conversation, Status.Idle)
        app.applicationScope.launch { app.database.chatMessageDao().deleteAll(conversation.userId, conversation.planId) }
    }

    private suspend fun askCoach(app: GymBroApp, conversation: Conversation, token: String) {
        val dao = app.database.chatMessageDao()
        try {
            val history = dao.getLast(conversation.userId, conversation.planId, MAX_SENT_MESSAGES).reversed()
            val reply = ApiClient.askCoach(token, history, conversation.planId)
            dao.insert(
                ChatMessage(
                    userId = conversation.userId, planId = conversation.planId, role = ChatRole.ASSISTANT,
                    text = reply.text, model = reply.model, planUpdated = reply.plan != null
                )
            )
            setStatus(conversation, Status.Idle)
        } catch (e: ApiException) {
            setStatus(conversation, Status.Failed(e.userMessage(app)))
        }
    }
}
