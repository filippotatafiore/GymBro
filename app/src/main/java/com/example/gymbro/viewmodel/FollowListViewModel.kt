package com.example.gymbro.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.gymbro.R
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.SocialUser
import com.example.gymbro.repository.SocialRepository
import com.example.gymbro.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Followers and followed users of a user (FollowListActivity): the two lists, the one chosen
// shown, with the users matching the search
class FollowListViewModel(
    private val userId: Long,
    tab: Tab,
    private val users: UserRepository,
    private val social: SocialRepository
) : ViewModel() {

    enum class Tab { FOLLOWERS, FOLLOWING }

    // The list chosen, once downloaded: the users matching the search and how many users the two
    // lists have
    data class Shown(val users: List<SocialUser>, val followersCount: Int, val followingCount: Int)

    // shown: null until the lists are downloaded; empty: the text under the list (null: hidden);
    // loading: the first download is not finished yet
    data class State(
        val tab: Tab,
        val shown: Shown? = null,
        val empty: EmptyText? = null,
        val loading: Boolean = true
    )

    private val _state = MutableStateFlow(State(tab))
    val state: StateFlow<State> = _state.asStateFlow()

    // The two lists, once downloaded; the follows of the logged user they were downloaded with
    // (the lists are downloaded again when they change)
    private val lists = mutableMapOf<Tab, List<SocialUser>>()
    private var followsVersion = -1
    private var query = ""

    val isLoggedIn: Boolean
        get() = users.isLoggedIn

    // The logged user ("You" in the lists)
    val myId: Long?
        get() = users.user?.id

    // Every time the page is shown: e.g. back from a profile where the user was followed, the
    // lists change
    fun onPageShown() {
        if (followsVersion != social.followsVersion) load()
    }

    fun showTab(tab: Tab) {
        _state.update { it.copy(tab = tab) }
        showList()
    }

    fun search(text: String) {
        query = text.trim().removePrefix("@")
        showList()
    }

    // Both the lists, from the server (in background)
    private fun load() {
        val version = social.followsVersion
        viewModelScope.launch {
            try {
                lists[Tab.FOLLOWERS] = social.followers(userId)
                lists[Tab.FOLLOWING] = social.following(userId)
                followsVersion = version
                showList()
            } catch (e: ApiException) {
                _state.update { it.copy(empty = if (lists.isEmpty()) EmptyText.Error(e) else null) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    // The list chosen, with the users matching the search (upper/lower case ignored)
    private fun showList() {
        val tab = _state.value.tab
        val users = lists[tab] ?: return
        val found = if (query.isEmpty()) users else users.filter { it.username.contains(query, ignoreCase = true) }
        val empty = when {
            query.isNotEmpty() -> R.string.no_users_found
            tab == Tab.FOLLOWERS -> R.string.no_followers
            else -> R.string.no_following
        }
        val shown = Shown(found, lists[Tab.FOLLOWERS]?.size ?: 0, lists[Tab.FOLLOWING]?.size ?: 0)
        _state.update { it.copy(shown = shown, empty = if (found.isEmpty()) EmptyText.Text(empty) else null) }
    }

    companion object {
        fun factory(userId: Long, tab: Tab) = viewModelFactory {
            initializer { FollowListViewModel(userId, tab, container.users, container.social) }
        }
    }
}
