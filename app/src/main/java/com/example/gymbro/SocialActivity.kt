package com.example.gymbro

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.FeedPost
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.social.FeedAdapter
import com.example.gymbro.social.Follows
import com.example.gymbro.social.UserAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Social page (third icon of the bottom strip):
// - the feed: the posts of the users followed, newest first, loaded a page at a time while
//   scrolling; the personal bests of their user are marked
// - the search of the users by username, at the top: the results cover the feed while there is a text
// - the button of Messages (MessagesActivity): workout plans shared with the users that follow each
//   other with the logged user, with a dot when some plans were not seen yet
class SocialActivity : ThemedActivity() {

    private lateinit var bottomNav: BottomNavBar
    private lateinit var searchInput: EditText
    private lateinit var searchResults: View
    private lateinit var searchEmptyText: TextView
    private lateinit var feedList: RecyclerView
    private lateinit var feedEmptyText: TextView
    private lateinit var feedLoading: ProgressBar
    private lateinit var unreadDot: View

    private lateinit var feedAdapter: FeedAdapter
    private lateinit var searchAdapter: UserAdapter

    // The posts shown and the next (older) page to load: null = no other posts
    private var feed = emptyList<FeedPost>()
    private var nextPage: String? = null
    private var loadingFeed = false
    // When the feed was loaded, and the follows at that time (see Follows): when the page is shown
    // again, the feed is loaded again only if they changed or after some minutes (the posts loaded
    // further down and the scroll position are kept)
    private var feedLoadedAt = 0L
    private var feedFollowsVersion = -1
    private var searchJob: Job? = null
    // the follows when the results of the search were asked: they show who follows whom
    private var searchFollowsVersion = -1

    // The back button closes the search first: enabled while there is a text in the search
    private val closeSearch = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            searchInput.text.clear()
            hideKeyboard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nobody logged in (e.g. app restored after the logout): back to the welcome page
        if (!UserSession.isLoggedIn) {
            WelcomeActivity.open(this)
            finish()
            return
        }

        // link to the layout
        setContentView(R.layout.activity_social)

        searchInput = findViewById(R.id.searchInput)
        searchResults = findViewById(R.id.searchResults)
        searchEmptyText = findViewById(R.id.searchEmptyText)
        feedList = findViewById(R.id.feedList)
        feedEmptyText = findViewById(R.id.feedEmptyText)
        feedLoading = findViewById(R.id.feedLoading)
        unreadDot = findViewById(R.id.messagesUnreadDot)

        findViewById<View>(R.id.messagesButton).setOnClickListener { MessagesActivity.open(this) }


        // --------------------------------------------------------- FEED

        feedAdapter = FeedAdapter(
            lifecycleScope,
            onUserClick = { user -> UserProfileActivity.open(this, user.id, user.username) },
            onPostClick = { post -> showPostDetails(this, this, post) }
        )
        val layoutManager = LinearLayoutManager(this)
        feedList.layoutManager = layoutManager
        feedList.adapter = feedAdapter
        // near the end of the posts shown: the next page
        feedList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && layoutManager.findLastVisibleItemPosition() >= feed.size - 4) loadMore()
            }
        })


        // --------------------------------------------------------- SEARCH

        searchAdapter = UserAdapter(lifecycleScope) { user ->
            // the keyboard does not open again when coming back
            hideKeyboard()
            UserProfileActivity.open(this, user.id, user.username)
        }
        val searchList = findViewById<RecyclerView>(R.id.searchList)
        searchList.layoutManager = LinearLayoutManager(this)
        searchList.adapter = searchAdapter

        // the results are asked when the user stops typing for a moment
        searchInput.doAfterTextChanged { text ->
            searchJob?.cancel()
            val query = text.toString().trim()
            searchResults.isVisible = query.isNotEmpty()
            closeSearch.isEnabled = query.isNotEmpty()
            if (query.isEmpty()) {
                searchAdapter.submitList(emptyList())
                searchEmptyText.isVisible = false
                return@doAfterTextChanged
            }
            searchJob = lifecycleScope.launch {
                delay(300)
                search(query)
            }
        }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) hideKeyboard()
            actionId == EditorInfo.IME_ACTION_SEARCH
        }
        // the back button closes the search first
        onBackPressedDispatcher.addCallback(this, closeSearch)


        // --------------------------------------------------------- NAVBAR

        bottomNav = BottomNavBar(this, BottomNavBar.Page.SOCIAL)
        keepAboveKeyboard(findViewById(R.id.socialRoot), findViewById(R.id.bottomNav))
    }

    override fun onResume() {
        super.onResume()
        bottomNav.refresh()
        // the posts published since the last time, and the plans received
        val old = System.currentTimeMillis() - feedLoadedAt > FEED_MAX_AGE_MS
        if (old || feedFollowsVersion != Follows.version) refreshFeed()
        refreshUnread()
        // e.g. back from a profile where the user was followed: "Following" in the results
        val query = searchInput.text.toString().trim()
        if (query.isNotEmpty() && searchFollowsVersion != Follows.version) {
            searchJob?.cancel()
            searchJob = lifecycleScope.launch { search(query) }
        }
    }

    override fun onPause() {
        super.onPause()
        bottomNav.stopGlow()
    }


    // --------------------------------------------------------- FEED

    // The first page of the feed (the newest posts)
    private fun refreshFeed() {
        val token = UserSession.token ?: return
        if (loadingFeed) return
        loadingFeed = true
        val followsVersion = Follows.version
        lifecycleScope.launch {
            try {
                val page = ApiClient.getFeed(token, null)
                showFeed(page.posts, page.next)
                feedLoadedAt = System.currentTimeMillis()
                feedFollowsVersion = followsVersion
            } catch (e: ApiException) {
                // the posts already shown stay
                if (feed.isEmpty()) {
                    feedEmptyText.text = e.userMessage(this@SocialActivity)
                    feedEmptyText.isVisible = true
                }
            } finally {
                loadingFeed = false
                feedLoading.isVisible = false
            }
        }
    }

    // The next (older) page of the feed, added under the posts shown
    private fun loadMore() {
        val token = UserSession.token ?: return
        val before = nextPage ?: return
        if (loadingFeed) return
        loadingFeed = true
        lifecycleScope.launch {
            try {
                val page = ApiClient.getFeed(token, before)
                showFeed(feed + page.posts, page.next)
            } catch (e: ApiException) {
                // tried again at the next scroll
            } finally {
                loadingFeed = false
            }
        }
    }

    private fun showFeed(posts: List<FeedPost>, next: String?) {
        feed = posts
        nextPage = next
        feedAdapter.submitList(posts)
        feedEmptyText.setText(R.string.feed_empty)
        feedEmptyText.isVisible = posts.isEmpty()
    }

    // The dot on the Messages button: some plans received were not seen yet
    private fun refreshUnread() {
        val token = UserSession.token ?: return
        lifecycleScope.launch {
            try {
                unreadDot.isVisible = ApiClient.getConversations(token).any { it.unread > 0 }
            } catch (e: ApiException) {
                // the dot stays as it is
            }
        }
    }


    // --------------------------------------------------------- SEARCH

    private suspend fun search(query: String) {
        val token = UserSession.token ?: return
        val followsVersion = Follows.version
        try {
            val users = ApiClient.searchUsers(token, query)
            searchFollowsVersion = followsVersion
            searchAdapter.submitList(users)
            searchEmptyText.setText(R.string.no_users_found)
            searchEmptyText.isVisible = users.isEmpty()
        } catch (e: ApiException) {
            searchAdapter.submitList(emptyList())
            searchEmptyText.text = e.userMessage(this)
            searchEmptyText.isVisible = true
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
    }

    companion object {
        private const val FEED_MAX_AGE_MS = 2 * 60_000L
    }
}
