package com.example.gymbro.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.gymbro.R
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.FeedPost
import com.example.gymbro.network.SocialUser
import com.example.gymbro.repository.MessageRepository
import com.example.gymbro.repository.SocialRepository
import com.example.gymbro.repository.UserRepository
import com.example.gymbro.session.ActiveSession
import com.example.gymbro.session.SessionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Social page (SocialActivity): the feed (a page of posts at a time), the search of the users and
// the dot of Messages
class SocialViewModel(
    private val users: UserRepository,
    private val social: SocialRepository,
    private val messages: MessageRepository
) : ViewModel() {

    // The results of the search: users found, or the text when there are none (null: hidden)
    data class Search(val users: List<SocialUser> = emptyList(), val empty: EmptyText? = null)

    // feed: the posts shown (null: not downloaded yet); feedEmpty: the text over the feed (null:
    // hidden); feedLoading: the first download is not finished yet; unread: some plans received
    // were not seen yet (null: not known yet)
    data class State(
        val feed: List<FeedPost>? = null,
        val feedEmpty: EmptyText? = null,
        val feedLoading: Boolean = true,
        val search: Search = Search(),
        val unread: Boolean? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // The next (older) page of the feed to load: null = no other posts
    private var nextPage: String? = null
    private var loadingFeed = false
    // When the feed was loaded, and the follows at that time (see SocialRepository.followsVersion):
    // when the page is shown again, the feed is loaded again only if they changed or after some
    // minutes (the posts loaded further down and the scroll position are kept)
    private var feedLoadedAt = 0L
    private var feedFollowsVersion = -1
    private var searchJob: Job? = null
    private var query = ""
    // the follows when the results of the search were asked: they show who follows whom
    private var searchFollowsVersion = -1

    val isLoggedIn: Boolean
        get() = users.isLoggedIn

    // The logged user ("You" in the results)
    val myId: Long?
        get() = users.user?.id

    // The training session in progress (bottom strip)
    fun sessionStatus(): SessionStatus = ActiveSession.status

    // Every time the page is shown: the posts published since the last time, and the plans received
    fun onPageShown() {
        val old = System.currentTimeMillis() - feedLoadedAt > FEED_MAX_AGE_MS
        if (old || feedFollowsVersion != social.followsVersion) refreshFeed()
        refreshUnread()
        // e.g. back from a profile where the user was followed: "Following" in the results
        if (query.isNotEmpty() && searchFollowsVersion != social.followsVersion) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch { search() }
        }
    }


    // --------------------------------------------------------- FEED

    // The first page of the feed (the newest posts)
    private fun refreshFeed() {
        if (loadingFeed) return
        loadingFeed = true
        val followsVersion = social.followsVersion
        viewModelScope.launch {
            try {
                val page = social.feed(null)
                showFeed(page.posts, page.next)
                feedLoadedAt = System.currentTimeMillis()
                feedFollowsVersion = followsVersion
            } catch (e: ApiException) {
                // the posts already shown stay
                if (_state.value.feed.isNullOrEmpty()) _state.update { it.copy(feedEmpty = EmptyText.Error(e)) }
            } finally {
                loadingFeed = false
                _state.update { it.copy(feedLoading = false) }
            }
        }
    }

    // Near the end of the posts shown: the next (older) page, added under them
    fun loadMore() {
        val before = nextPage ?: return
        if (loadingFeed) return
        loadingFeed = true
        viewModelScope.launch {
            try {
                val page = social.feed(before)
                showFeed(_state.value.feed.orEmpty() + page.posts, page.next)
            } catch (e: ApiException) {
                // tried again at the next scroll
            } finally {
                loadingFeed = false
            }
        }
    }

    private fun showFeed(posts: List<FeedPost>, next: String?) {
        nextPage = next
        _state.update { it.copy(feed = posts, feedEmpty = if (posts.isEmpty()) EmptyText.Text(R.string.feed_empty) else null) }
    }

    // The dot on the Messages button: some plans received were not seen yet
    private fun refreshUnread() {
        viewModelScope.launch {
            try {
                val unread = messages.conversations().any { it.unread > 0 }
                _state.update { it.copy(unread = unread) }
            } catch (e: ApiException) {
                // the dot stays as it is
            }
        }
    }


    // --------------------------------------------------------- SEARCH

    // The text of the search: the results are asked when the user stops typing for a moment
    fun search(text: String) {
        searchJob?.cancel()
        query = text.trim()
        if (query.isEmpty()) {
            _state.update { it.copy(search = Search()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DELAY_MS)
            search()
        }
    }

    private suspend fun search() {
        val followsVersion = social.followsVersion
        try {
            val found = social.search(query)
            searchFollowsVersion = followsVersion
            _state.update {
                it.copy(search = Search(found, if (found.isEmpty()) EmptyText.Text(R.string.no_users_found) else null))
            }
        } catch (e: ApiException) {
            _state.update { it.copy(search = Search(emptyList(), EmptyText.Error(e))) }
        }
    }

    companion object {
        private const val FEED_MAX_AGE_MS = 2 * 60_000L
        private const val SEARCH_DELAY_MS = 300L

        val Factory = viewModelFactory {
            initializer { SocialViewModel(container.users, container.social, container.messages) }
        }
    }
}
