package com.example.gymbro

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.social.FeedAdapter
import com.example.gymbro.social.UserAdapter
import com.example.gymbro.viewmodel.SocialViewModel

// Social page (third icon of the bottom strip):
// - the feed: the posts of the users followed, newest first, loaded a page at a time while
//   scrolling; the personal bests of their user are marked
// - the search of the users by username, at the top: the results cover the feed while there is a text
// - the button of Messages (MessagesActivity): workout plans shared with the users that follow each
//   other with the logged user, with a dot when some plans were not seen yet
// Data, search and pages of the feed: SocialViewModel.
class SocialActivity : ThemedActivity() {

    private val viewModel: SocialViewModel by viewModels { SocialViewModel.Factory }

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

    // How many posts are shown (near the end of them, the next page is loaded)
    private var feedSize = 0

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
        if (!viewModel.isLoggedIn) {
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
                if (dy > 0 && layoutManager.findLastVisibleItemPosition() >= feedSize - 4) viewModel.loadMore()
            }
        })


        // --------------------------------------------------------- SEARCH

        searchAdapter = UserAdapter(lifecycleScope, viewModel.myId) { user ->
            // the keyboard does not open again when coming back
            hideKeyboard()
            UserProfileActivity.open(this, user.id, user.username)
        }
        val searchList = findViewById<RecyclerView>(R.id.searchList)
        searchList.layoutManager = LinearLayoutManager(this)
        searchList.adapter = searchAdapter

        // the results cover the feed while there is a text
        searchInput.doAfterTextChanged { text ->
            val query = text.toString().trim()
            searchResults.isVisible = query.isNotEmpty()
            closeSearch.isEnabled = query.isNotEmpty()
            viewModel.search(query)
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

        observe(viewModel.state) { state -> show(state) }
    }

    override fun onResume() {
        super.onResume()
        bottomNav.refresh(viewModel.sessionStatus())
        viewModel.onPageShown()
    }

    override fun onPause() {
        super.onPause()
        bottomNav.stopGlow()
    }

    private fun show(state: SocialViewModel.State) {
        state.feed?.let { feed ->
            feedSize = feed.size
            feedAdapter.submitList(feed)
        }
        feedEmptyText.showEmptyText(state.feedEmpty)
        if (!state.feedLoading) feedLoading.isVisible = false
        state.unread?.let { unreadDot.isVisible = it }

        searchAdapter.submitList(state.search.users)
        searchEmptyText.showEmptyText(state.search.empty)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
    }
}
