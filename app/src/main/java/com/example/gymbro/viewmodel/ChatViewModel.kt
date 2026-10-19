package com.example.gymbro.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.gymbro.data.ChatMessage
import com.example.gymbro.repository.ChatRepository
import com.example.gymbro.repository.UserRepository
import com.example.gymbro.session.ActiveSession
import com.example.gymbro.session.SessionStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Chat with the AI coach (ChatActivity): the general chat (planId = null) or the chat about a
// workout plan. The messages are saved on this phone, the answers come from the server.
class ChatViewModel(
    planId: Long?,
    private val users: UserRepository,
    private val chats: ChatRepository
) : ViewModel() {

    // The messages, and what the coach is doing (writing, or the answer did not arrive)
    data class State(val messages: List<ChatMessage>, val status: ChatRepository.Status)

    val isLoggedIn: Boolean
        get() = users.isLoggedIn

    private val conversation = ChatRepository.Conversation(users.user?.id ?: 0L, planId)

    val isPlanChat: Boolean
        get() = conversation.planId != null

    // null until the messages are read from the database; then updated every time a message is
    // saved or the coach starts/stops writing
    val state: StateFlow<State?> =
        combine(chats.messages(conversation), chats.status(conversation)) { messages, status -> State(messages, status) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The coach is writing: no other message can be sent meanwhile
    val isWaiting: Boolean
        get() = chats.isWaiting(conversation)

    init {
        // the last message has no answer (e.g. the app was closed while the coach was writing):
        // it can be asked again
        if (isLoggedIn) {
            viewModelScope.launch {
                if (chats.hasUnansweredMessage(conversation)) chats.answerMissing(conversation)
            }
        }
    }

    // The training session in progress (bottom strip)
    fun sessionStatus(): SessionStatus = ActiveSession.status

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || isWaiting) return
        chats.send(conversation, message)
    }

    fun retry() {
        chats.retry(conversation)
    }

    // New chat: possible after a message, or while the coach is writing
    val canStartNewChat: Boolean
        get() = state.value?.messages?.isNotEmpty() == true || isWaiting

    // New chat: the conversation is deleted
    fun newChat() {
        chats.clear(conversation)
    }

    companion object {
        fun factory(planId: Long?) = viewModelFactory {
            initializer { ChatViewModel(planId, container.users, container.chats) }
        }
    }
}
