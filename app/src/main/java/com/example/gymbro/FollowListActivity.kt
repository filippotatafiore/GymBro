package com.example.gymbro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.social.UserAdapter
import com.example.gymbro.viewmodel.FollowListViewModel
import com.example.gymbro.viewmodel.FollowListViewModel.Tab

// Followers and followed users of a user (tap the numbers of a profile): the two lists, chosen at
// the top, in alphabetical order, with a search on the list shown (by username, upper/lower case
// ignored). Tap a user to open their profile. Data and search: FollowListViewModel.
class FollowListActivity : ThemedActivity() {

    private val viewModel: FollowListViewModel by viewModels {
        FollowListViewModel.factory(
            intent.getLongExtra(EXTRA_USER_ID, 0L),
            Tab.valueOf(intent.getStringExtra(EXTRA_TAB) ?: Tab.FOLLOWERS.name)
        )
    }

    private lateinit var search: EditText
    private lateinit var emptyText: TextView
    private lateinit var loading: ProgressBar
    private lateinit var adapter: UserAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nobody logged in (e.g. app restored after the logout): back to the welcome page
        if (!viewModel.isLoggedIn) {
            WelcomeActivity.open(this)
            finish()
            return
        }

        // link to the layout
        setContentView(R.layout.activity_follow_list)

        findViewById<View>(R.id.followListBackButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.followListTitle).text = intent.getStringExtra(EXTRA_USERNAME)
        search = findViewById(R.id.followSearchInput)
        emptyText = findViewById(R.id.followEmptyText)
        loading = findViewById(R.id.followLoading)

        adapter = UserAdapter(lifecycleScope, viewModel.myId) { user -> UserProfileActivity.open(this, user.id, user.username) }
        val list = findViewById<RecyclerView>(R.id.followList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        val group = findViewById<RadioGroup>(R.id.followListGroup)
        group.check(if (viewModel.state.value.tab == Tab.FOLLOWERS) R.id.followersTab else R.id.followingTab)
        group.setOnCheckedChangeListener { _, checkedId ->
            viewModel.showTab(if (checkedId == R.id.followersTab) Tab.FOLLOWERS else Tab.FOLLOWING)
        }
        search.doAfterTextChanged { viewModel.search(it.toString()) }

        // no bottom strip: the page ends above the keyboard, or above the navigation bar of the phone
        keepAboveKeyboard(findViewById(R.id.followListRoot))

        observe(viewModel.state) { state -> show(state) }
    }

    override fun onResume() {
        super.onResume()
        // e.g. back from a profile where the user was followed: the lists change
        viewModel.onPageShown()
    }

    // The list chosen, with the users matching the search
    private fun show(state: FollowListViewModel.State) {
        state.shown?.let { shown ->
            // e.g. "34 Followers", "56 Following"
            findViewById<RadioButton>(R.id.followersTab).text =
                resources.getQuantityString(R.plurals.count_followers, shown.followersCount, shown.followersCount)
            findViewById<RadioButton>(R.id.followingTab).text = getString(R.string.count_following, shown.followingCount)
            adapter.submitList(shown.users)
        }
        emptyText.showEmptyText(state.empty)
        if (!state.loading) loading.isVisible = false
    }

    companion object {
        private const val EXTRA_USER_ID = "user_id"
        private const val EXTRA_USERNAME = "username"
        private const val EXTRA_TAB = "tab"

        fun open(context: Context, userId: Long, username: String, tab: Tab) {
            context.startActivity(
                Intent(context, FollowListActivity::class.java)
                    .putExtra(EXTRA_USER_ID, userId)
                    .putExtra(EXTRA_USERNAME, username)
                    .putExtra(EXTRA_TAB, tab.name)
            )
        }
    }
}
