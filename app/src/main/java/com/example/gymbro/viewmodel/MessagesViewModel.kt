package com.example.gymbro.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.gymbro.R
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.Conversation
import com.example.gymbro.repository.MessageRepository
import com.example.gymbro.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Messages (MessagesActivity): the users the plans can be shared with, or were shared with, the
// last shared first, with a search by username
class MessagesViewModel(
    private val users: UserRepository,
    private val messages: MessageRepository
) : ViewModel() {

    // conversations: those matching the search; empty: the text under the list (null: hidden);
    // loading: the first download is not finished yet
    data class State(
        val conversations: List<Conversation> = emptyList(),
        val empty: EmptyText? = null,
        val loading: Boolean = true
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // All the conversations downloaded, and the text searched
    private var conversations = emptyList<Conversation>()
    private var query = ""

    val isLoggedIn: Boolean
        get() = users.isLoggedIn

    // Every time the page is shown (e.g. back from a conversation: the plans received are seen now)
    fun load() {
        viewModelScope.launch {
            try {
                conversations = messages.conversations()
                showConversations()
            } catch (e: ApiException) {
                if (conversations.isEmpty()) _state.update { it.copy(empty = EmptyText.Error(e)) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun search(text: String) {
        query = text.trim().removePrefix("@")
        showConversations()
    }

    // The users matching the search (upper/lower case ignored)
    private fun showConversations() {
        val found = if (query.isEmpty()) conversations
            else conversations.filter { it.user.username.contains(query, ignoreCase = true) }
        val empty = if (query.isEmpty()) R.string.messages_empty else R.string.no_users_found
        _state.update { it.copy(conversations = found, empty = if (found.isEmpty()) EmptyText.Text(empty) else null) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { MessagesViewModel(container.users, container.messages) }
        }
    }
}
