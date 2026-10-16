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
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.SocialUser
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.social.Follows
import com.example.gymbro.social.UserAdapter
import kotlinx.coroutines.launch

// Followers and followed users of a user (tap the numbers of a profile): the two lists, chosen at
// the top, in alphabetical order, with a search on the list shown (by username, upper/lower case
// ignored). Tap a user to open their profile.
class FollowListActivity : ThemedActivity() {

    enum class Tab { FOLLOWERS, FOLLOWING }

    private var userId = 0L
    private var shown = Tab.FOLLOWERS
    // the two lists, once downloaded; the follows of the logged user they were downloaded with
    // (see Follows: the lists are downloaded again when they change)
    private val lists = mutableMapOf<Tab, List<SocialUser>>()
    private var followsVersion = -1

    private lateinit var search: EditText
    private lateinit var emptyText: TextView
    private lateinit var loading: ProgressBar
    private lateinit var adapter: UserAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nobody logged in (e.g. app restored after the logout): back to the welcome page
        if (!UserSession.isLoggedIn) {
            WelcomeActivity.open(this)
            finish()
            return
        }
        userId = intent.getLongExtra(EXTRA_USER_ID, 0L)
        shown = Tab.valueOf(intent.getStringExtra(EXTRA_TAB) ?: Tab.FOLLOWERS.name)

        // link to the layout
        setContentView(R.layout.activity_follow_list)

        findViewById<View>(R.id.followListBackButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.followListTitle).text = intent.getStringExtra(EXTRA_USERNAME)
        search = findViewById(R.id.followSearchInput)
        emptyText = findViewById(R.id.followEmptyText)
        loading = findViewById(R.id.followLoading)

        adapter = UserAdapter(lifecycleScope) { user -> UserProfileActivity.open(this, user.id, user.username) }
        val list = findViewById<RecyclerView>(R.id.followList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        val group = findViewById<RadioGroup>(R.id.followListGroup)
        group.check(if (shown == Tab.FOLLOWERS) R.id.followersTab else R.id.followingTab)
        group.setOnCheckedChangeListener { _, checkedId ->
            shown = if (checkedId == R.id.followersTab) Tab.FOLLOWERS else Tab.FOLLOWING
            showList()
        }
        search.doAfterTextChanged { showList() }

        // no bottom strip: the page ends above the keyboard, or above the navigation bar of the phone
        keepAboveKeyboard(findViewById(R.id.followListRoot))
    }

    override fun onResume() {
        super.onResume()
        // e.g. back from a profile where the user was followed: the lists change
        if (followsVersion != Follows.version) load()
    }

    // Both the lists, from the server (in background)
    private fun load() {
        val token = UserSession.token ?: return
        val version = Follows.version
        lifecycleScope.launch {
            try {
                lists[Tab.FOLLOWERS] = ApiClient.getFollowers(token, userId)
                lists[Tab.FOLLOWING] = ApiClient.getFollowing(token, userId)
                followsVersion = version
                showList()
            } catch (e: ApiException) {
                emptyText.text = e.userMessage(this@FollowListActivity)
                emptyText.isVisible = lists.isEmpty()
            } finally {
                loading.isVisible = false
            }
        }
    }

    // The list chosen, with the users matching the search
    private fun showList() {
        val users = lists[shown] ?: return
        // e.g. "34 Followers", "56 Following"
        val followers = lists[Tab.FOLLOWERS]?.size ?: 0
        findViewById<RadioButton>(R.id.followersTab).text =
            resources.getQuantityString(R.plurals.count_followers, followers, followers)
        findViewById<RadioButton>(R.id.followingTab).text =
            getString(R.string.count_following, lists[Tab.FOLLOWING]?.size ?: 0)

        val text = search.text.toString().trim().removePrefix("@")
        val found = if (text.isEmpty()) users else users.filter { it.username.contains(text, ignoreCase = true) }
        adapter.submitList(found)
        emptyText.text = getString(
            when {
                text.isNotEmpty() -> R.string.no_users_found
                shown == Tab.FOLLOWERS -> R.string.no_followers
                else -> R.string.no_following
            }
        )
        emptyText.isVisible = found.isEmpty()
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
