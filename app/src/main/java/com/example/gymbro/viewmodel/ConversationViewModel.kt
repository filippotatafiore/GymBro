package com.example.gymbro.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.ConversationDetails
import com.example.gymbro.plan.Plan
import com.example.gymbro.repository.MessageRepository
import com.example.gymbro.repository.PlanRepository
import com.example.gymbro.repository.UserRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The workout plans shared with one user (ConversationActivity), and the sharing of another plan
class ConversationViewModel(
    private val userId: Long,
    username: String,
    private val users: UserRepository,
    private val messages: MessageRepository,
    private val plans: PlanRepository
) : ViewModel() {

    // details: null until downloaded; error: why they could not be downloaded; loading: the first
    // download is not finished yet; shareEnabled: the "Share a workout plan" button (null: as it is);
    // sharing: a plan is being sent (the window with the plans waits)
    data class State(
        val username: String,
        val details: ConversationDetails? = null,
        val error: ApiException? = null,
        val loading: Boolean = true,
        val shareEnabled: Boolean? = null,
        val sharing: Boolean = false
    )

    sealed interface Event {
        data object UserNotFound : Event                            // the page closes
        data class ChoosePlan(val plans: List<Plan>) : Event        // the window with the plans
        data class Shared(val username: String) : Event             // the window closes
        data class ShareFailed(val error: ApiException) : Event     // shown in the window
        data class Error(val error: ApiException) : Event
    }

    private val _state = MutableStateFlow(State(username))
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    val isLoggedIn: Boolean
        get() = users.isLoggedIn

    // Every time the page is shown: e.g. back from a plan received and added to your plans
    fun load() {
        viewModelScope.launch {
            try {
                val details = messages.conversation(userId)
                _state.update {
                    it.copy(username = details.user.username, details = details, shareEnabled = details.canShare)
                }
                // the plans received are seen now
                if (details.shares.any { !it.fromMe && !it.read }) markRead()
            } catch (e: ApiException) {
                if (e.code == 404) _events.send(Event.UserNotFound)
                else if (_state.value.details == null) _state.update { it.copy(error = e) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private fun markRead() {
        viewModelScope.launch {
            try {
                messages.markRead(userId)
            } catch (e: ApiException) {
                // they stay "not seen" until the next time
            }
        }
    }

    // The plans of the user, to choose the one to share (only online: the changes made offline are
    // sent first, so the other user receives the plan as it is now)
    fun choosePlanToShare() {
        _state.update { it.copy(shareEnabled = false) }
        viewModelScope.launch {
            try {
                plans.sync()
                _events.send(Event.ChoosePlan(plans.all()))
            } catch (e: ApiException) {
                _events.send(Event.Error(e))
            } finally {
                _state.update { it.copy(shareEnabled = it.details?.canShare == true) }
            }
        }
    }

    // The user receives a copy of the plan as it is now
    fun share(planId: Long) {
        if (_state.value.sharing) return
        _state.update { it.copy(sharing = true) }
        viewModelScope.launch {
            try {
                messages.share(userId, planId)
                _events.send(Event.Shared(_state.value.username))
                load()
            } catch (e: ApiException) {
                _events.send(Event.ShareFailed(e))
            } finally {
                _state.update { it.copy(sharing = false) }
            }
        }
    }

    companion object {
        fun factory(userId: Long, username: String) = viewModelFactory {
            initializer { ConversationViewModel(userId, username, container.users, container.messages, container.plans) }
        }
    }
}
