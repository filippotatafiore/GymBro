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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Conversation with the AI coach (chat page). The messages are saved in the local database
// (chat_messages table); the answer is asked to the server in background (applicationScope), so it
// arrives and is saved also if the chat page is closed meanwhile.
object CoachChat {

    // What the coach is doing for the user with this id
    sealed interface Status {
        data object Idle : Status                                       // waiting for a message
        data class Waiting(val userId: Long) : Status                   // writing the answer
        data class Failed(val userId: Long, val message: String) : Status  // the answer did not arrive
    }

    // Newest messages sent to the server: the server gives to the model those that fit its limits
    private const val MAX_SENT_MESSAGES = 30

    private val mutableStatus = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    private var request: Job? = null

    val isWaiting: Boolean
        get() = mutableStatus.value is Status.Waiting

    // Saves the message of the logged user and asks the answer of the coach
    fun send(context: Context, text: String) {
        val userId = UserSession.userId ?: return
        val token = UserSession.token ?: return
        if (isWaiting) return
        val app = context.gymBroApp

        mutableStatus.value = Status.Waiting(userId)
        request = app.applicationScope.launch {
            app.database.chatMessageDao().insert(ChatMessage(userId = userId, role = ChatRole.USER, text = text))
            askCoach(app, userId, token)
        }
    }

    // Asks again the answer to the last message (after an error)
    fun retry(context: Context) {
        val userId = UserSession.userId ?: return
        val token = UserSession.token ?: return
        if (isWaiting) return

        val app = context.gymBroApp
        mutableStatus.value = Status.Waiting(userId)
        request = app.applicationScope.launch { askCoach(app, userId, token) }
    }

    // The last message of the user has no answer, and no answer is coming (e.g. the app was closed
    // while the coach was writing): shown as an error, so that it can be asked again
    fun answerMissing(userId: Long, message: String) {
        if (mutableStatus.value == Status.Idle) mutableStatus.value = Status.Failed(userId, message)
    }

    // New chat: deletes the conversation of the logged user (an answer still arriving is discarded)
    fun clear(context: Context) {
        val userId = UserSession.userId ?: return
        val app = context.gymBroApp
        request?.cancel()
        mutableStatus.value = Status.Idle
        app.applicationScope.launch { app.database.chatMessageDao().deleteAll(userId) }
    }

    private suspend fun askCoach(app: GymBroApp, userId: Long, token: String) {
        val dao = app.database.chatMessageDao()
        try {
            val conversation = dao.getLast(userId, MAX_SENT_MESSAGES).reversed()
            val reply = ApiClient.askCoach(token, conversation)
            dao.insert(ChatMessage(userId = userId, role = ChatRole.ASSISTANT, text = reply.text, model = reply.model))
            mutableStatus.value = Status.Idle
        } catch (e: ApiException) {
            mutableStatus.value = Status.Failed(userId, e.userMessage(app))
        }
    }
}
