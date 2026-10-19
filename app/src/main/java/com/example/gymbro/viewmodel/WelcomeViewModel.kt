package com.example.gymbro.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.gymbro.network.ApiException
import com.example.gymbro.repository.AccountRepository
import com.example.gymbro.repository.UserRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

// Welcome page (WelcomeActivity): registration (with an optional profile picture) and login
class WelcomeViewModel(
    private val users: UserRepository,
    private val accounts: AccountRepository
) : ViewModel() {

    sealed interface Event {
        data object LoggedIn : Event                                    // into the app
        data class Failed(val error: ApiException) : Event              // shown in the window
        data class PictureNotUploaded(val error: ApiException) : Event  // the account exists anyway
    }

    // The server is answering: the confirm button of the window waits
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    // The profile picture chosen in the registration window (uploaded right after the account is
    // created); null when the window is closed
    var registerPicture: ByteArray? = null

    val isLoggedIn: Boolean
        get() = users.isLoggedIn

    fun register(username: String, email: String, password: String) {
        logIn {
            accounts.register(username, email, password)
            registerPicture?.let { picture ->
                try {
                    accounts.uploadPicture(picture)
                } catch (e: ApiException) {
                    // the picture can be changed from the profile
                    _events.send(Event.PictureNotUploaded(e))
                }
            }
        }
    }

    fun login(email: String, password: String) {
        logIn { accounts.login(email, password) }
    }

    // Logs in with [action]: then into the app, or the error in the window
    private fun logIn(action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                action()
                _events.send(Event.LoggedIn)
            } catch (e: ApiException) {
                _events.send(Event.Failed(e))
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { WelcomeViewModel(container.users, container.accounts) }
        }
    }
}
