package com.example.gymbro.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.Post
import com.example.gymbro.network.UserProfile
import com.example.gymbro.plan.Plan
import com.example.gymbro.profile.PersonalBests
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

// Profile of another user (UserProfileActivity): counts, picture, personal bests, public workout
// plans and posts, and the follow / unfollow button
class UserProfileViewModel(
    val userId: Long,
    username: String,
    private val users: UserRepository,
    private val social: SocialRepository
) : ViewModel() {

    // Posts of the user and their personal bests
    data class Posts(val posts: List<Post>, val personalBests: List<Post>)

    // Each part is null until downloaded; picture: null = the default one; followBusy: the follow
    // button waits for the server
    data class State(
        val username: String,
        val profile: UserProfile? = null,
        val pictureLoaded: Boolean = false,
        val picture: Bitmap? = null,
        val posts: Posts? = null,
        val plans: List<Plan>? = null,
        val followBusy: Boolean = false
    )

    sealed interface Event {
        data object UserNotFound : Event      // the page closes
        data class Error(val error: ApiException) : Event
    }

    private val _state = MutableStateFlow(State(username))
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    val isLoggedIn: Boolean
        get() = users.isLoggedIn

    // Your own profile: the Profile page, where it can be changed
    val isMe: Boolean
        get() = userId == users.user?.id

    // Profile, picture, posts and public plans of the user, from the server (every time the page
    // is shown)
    fun load() {
        viewModelScope.launch {
            try {
                showProfile(social.profile(userId))
                val picture = if (_state.value.profile?.user?.hasPicture == true) social.picture(userId) else null
                _state.update { it.copy(pictureLoaded = true, picture = picture) }

                val posts = social.posts(userId)
                _state.update { it.copy(posts = Posts(posts, PersonalBests.from(posts))) }

                val plans = social.publicPlans(userId)
                _state.update { it.copy(plans = plans) }
            } catch (e: ApiException) {
                _events.send(if (e.code == 404) Event.UserNotFound else Event.Error(e))
            }
        }
    }

    private fun showProfile(profile: UserProfile) {
        _state.update { it.copy(username = profile.user.username, profile = profile, followBusy = false) }
    }

    // Follows the user, or stops following them
    fun changeFollow() {
        val following = _state.value.profile?.user?.isFollowing ?: return
        if (_state.value.followBusy) return
        _state.update { it.copy(followBusy = true) }
        viewModelScope.launch {
            try {
                showProfile(if (following) social.unfollow(userId) else social.follow(userId))
            } catch (e: ApiException) {
                _state.update { it.copy(followBusy = false) }
                _events.send(Event.Error(e))
            }
        }
    }

    companion object {
        fun factory(userId: Long, username: String) = viewModelFactory {
            initializer { UserProfileViewModel(userId, username, container.users, container.social) }
        }
    }
}
