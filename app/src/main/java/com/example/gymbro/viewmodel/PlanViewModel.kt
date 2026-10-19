package com.example.gymbro.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.SharedPlan
import com.example.gymbro.plan.Plan
import com.example.gymbro.repository.ChatRepository
import com.example.gymbro.repository.MessageRepository
import com.example.gymbro.repository.PlanRepository
import com.example.gymbro.repository.SocialRepository
import com.example.gymbro.repository.UserRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Page of a gym workout plan (PlanActivity):
// - OWN: a plan of the logged user: the version saved on this phone at once, then the one of the
//   server (see PlanRepository); the changes by hand are saved at once on this phone and on the
//   server, also offline (sent when it is reachable again)
// - PUBLIC: a public plan of another user; SHARED: a plan sent with Messages (by the logged user or
//   to them): only to see, with "Add to my plans" (a copy among the plans of the logged user)
class PlanViewModel(
    private val mode: Mode,
    private val planId: Long,
    private val ownerId: Long,
    ownerName: String,
    private val shareId: Long,
    private val users: UserRepository,
    private val plans: PlanRepository,
    private val social: SocialRepository,
    private val messages: MessageRepository,
    private val chats: ChatRepository
) : ViewModel() {

    enum class Mode { OWN, PUBLIC, SHARED }

    // plan: null until read; shared: the plan shared (SHARED); ownerName: who made (PUBLIC) or
    // shared (SHARED) it; isActive: the active plan of the user (followed by the gym sessions);
    // loading: the plan was not shown yet
    data class State(
        val plan: Plan? = null,
        val shared: SharedPlan? = null,
        val ownerName: String,
        val isActive: Boolean = false,
        val loading: Boolean = true,
        val adding: Boolean = false
    )

    sealed interface Event {
        data object NotFound : Event                                    // the page closes
        data class Error(val error: ApiException) : Event
        data object Saved : Event                                       // e.g. the open window closes
        data class SaveFailed(val error: ApiException) : Event          // e.g. shown in the open window
        data class VisibilityNotChanged(val error: ApiException) : Event
        data object NowActive : Event
        data class Added(val planId: Long, val alreadyAdded: Boolean) : Event   // its page opens
        data object Deleted : Event                                     // the page closes
    }

    private val _state = MutableStateFlow(State(ownerName = ownerName))
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    // A change is being saved: the other changes wait (they would start from an old plan)
    var isSaving = false
        private set

    val isLoggedIn: Boolean
        get() = users.isLoggedIn

    val isOwn: Boolean
        get() = mode == Mode.OWN

    // Reads the plan (every time the page is shown: e.g. changed by the coach in the chat): your
    // plans from this phone and then from the server, the others only from the server
    fun load() {
        viewModelScope.launch {
            try {
                when (mode) {
                    Mode.OWN -> loadOwnPlan()
                    Mode.PUBLIC -> show(social.publicPlan(ownerId, planId))
                    Mode.SHARED -> {
                        val shared = messages.sharedPlan(shareId)
                        _state.update { it.copy(shared = shared, ownerName = shared.user?.username.orEmpty()) }
                        show(shared.plan)
                    }
                }
            } catch (e: ApiException) {
                _events.send(if (e.code == 404) Event.NotFound else Event.Error(e))
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    // One of your plans: the version saved on this phone at once (also offline), then the one of the
    // server (with the changes made offline sent first). Throws ApiException (404: deleted).
    private suspend fun loadOwnPlan() {
        plans.plan(planId)?.let {
            show(it)
            _state.update { state -> state.copy(loading = false) }
        }
        val problem = plans.trySync()
        val synced = plans.plan(planId)
        when {
            synced != null -> if (synced != _state.value.plan) show(synced)
            // not on this phone and the server is not reachable
            problem != null -> throw problem
            // deleted (e.g. on another phone)
            else -> throw ApiException(404, "Plan not found")
        }
    }

    private fun show(plan: Plan) {
        _state.update { it.copy(plan = plan, isActive = mode == Mode.OWN && users.user?.activePlanId == plan.id) }
    }

    // Private / public: like publishing, only online (saved on the server right away)
    fun setPublic(public: Boolean) {
        val plan = _state.value.plan ?: return
        if (isSaving) return
        isSaving = true
        viewModelScope.launch {
            try {
                show(plans.setPublic(plan.id, public))
            } catch (e: ApiException) {
                // the choice shown goes back to the saved one
                _events.send(Event.VisibilityNotChanged(e))
            } finally {
                isSaving = false
            }
        }
    }

    // Saves a change on this phone and on the server (offline: on the server later, see
    // PlanRepository), then shows the saved plan
    fun save(changed: Plan) {
        if (isSaving) return
        isSaving = true
        viewModelScope.launch {
            try {
                show(plans.update(changed).plan)
                _events.send(Event.Saved)
            } catch (e: ApiException) {
                _events.send(Event.SaveFailed(e))
            } finally {
                isSaving = false
            }
        }
    }

    // The gym sessions will follow this plan (the server remembers it for the account): at once on
    // this phone, on the server now or (offline) when it is reachable again
    fun setActive() {
        val plan = _state.value.plan ?: return
        users.setActivePlan(plan.id)
        _events.trySend(Event.NowActive)
        show(plan)
    }

    // A copy of the plan among your plans (the plan received and already added: that one), then
    // its page opens: there it can be changed and followed
    fun addToMyPlans() {
        if (isSaving) return
        isSaving = true
        _state.update { it.copy(adding = true) }
        viewModelScope.launch {
            try {
                val alreadyAdded = _state.value.shared?.savedPlanId != null
                val copy = when (mode) {
                    Mode.SHARED -> plans.copyShared(shareId)
                    else -> plans.copyPublic(ownerId, planId)
                }
                _events.send(Event.Added(copy.id, alreadyAdded))
            } catch (e: ApiException) {
                _events.send(Event.Error(e))
            } finally {
                isSaving = false
                _state.update { it.copy(adding = false) }
            }
        }
    }

    // Deletes the plan (on this phone at once, on the server now or, offline, when it is reachable
    // again, see PlanRepository) and the chat about it on the phone
    fun delete() {
        val userId = users.user?.id ?: return
        viewModelScope.launch {
            plans.delete(planId)
            chats.clear(ChatRepository.Conversation(userId, planId))
            // it was the active plan: the server has no active plan when the plan is deleted
            users.forgetActivePlan(planId)
            _events.send(Event.Deleted)
        }
    }

    companion object {
        fun factory(mode: Mode, planId: Long, ownerId: Long, ownerName: String, shareId: Long) = viewModelFactory {
            initializer {
                val app = container
                PlanViewModel(mode, planId, ownerId, ownerName, shareId, app.users, app.plans, app.social, app.messages, app.chats)
            }
        }
    }
}
