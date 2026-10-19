package com.example.gymbro

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.network.UserProfile
import com.example.gymbro.network.userMessage
import com.example.gymbro.plan.PlanAdapter
import com.example.gymbro.profile.PersonalBestAdapter
import com.example.gymbro.profile.PostAdapter
import com.example.gymbro.profile.ProfileHeader
import com.example.gymbro.viewmodel.FollowListViewModel
import com.example.gymbro.viewmodel.UserProfileViewModel

// Profile of another user, opened from the social page (feed, search, lists of followers): picture,
// how many posts, followers and followed users (tap them to see the lists), the button to follow the
// user or to stop following them, and "Share a plan" (Messages) when they follow each other. Then
// their personal bests, public workout plans (tap one to see it and add it to your plans) and posts.
// Nothing of the user can be changed here. Data and follow: UserProfileViewModel.
class UserProfileActivity : ThemedActivity() {

    private val viewModel: UserProfileViewModel by viewModels {
        UserProfileViewModel.factory(intent.getLongExtra(EXTRA_USER_ID, 0L), intent.getStringExtra(EXTRA_USERNAME).orEmpty())
    }

    private val userId: Long
        get() = viewModel.userId
    private val username: String
        get() = viewModel.state.value.username

    // The profile shown (null: not downloaded yet)
    private var shownProfile: UserProfile? = null

    private lateinit var header: ProfileHeader
    private lateinit var followButton: Button
    private lateinit var shareButton: Button
    private lateinit var bestEmpty: TextView
    private lateinit var plansEmpty: TextView
    private lateinit var postsEmpty: TextView

    private val postAdapter = PostAdapter(onClick = { post -> showPostDetails(this, this, post) })
    private val bestAdapter = PersonalBestAdapter(onClick = { post -> showPostDetails(this, this, post) })
    private val planAdapter = PlanAdapter(showPublic = false) { plan -> PlanActivity.openPublic(this, userId, username, plan.id) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nobody logged in (e.g. app restored after the logout): back to the welcome page
        if (!viewModel.isLoggedIn) {
            WelcomeActivity.open(this)
            finish()
            return
        }

        // your own profile: the Profile page, where it can be changed
        if (viewModel.isMe) {
            startActivity(Intent(this, ProfileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            finish()
            return
        }

        // link to the layout
        setContentView(R.layout.activity_user_profile)

        findViewById<View>(R.id.userProfileBackButton).setOnClickListener { finish() }
        val scroll = findViewById<NestedScrollView>(R.id.userProfileScroll)
        header = ProfileHeader(
            this,
            onPosts = { scroll.smoothScrollTo(0, findViewById<View>(R.id.userPostsTitle).top) },
            onFollowers = { FollowListActivity.open(this, userId, username, FollowListViewModel.Tab.FOLLOWERS) },
            onFollowing = { FollowListActivity.open(this, userId, username, FollowListViewModel.Tab.FOLLOWING) }
        )
        header.subtitle.isVisible = false

        followButton = findViewById(R.id.followButton)
        shareButton = findViewById(R.id.shareWithUserButton)
        followButton.setOnClickListener { viewModel.changeFollow() }
        shareButton.setOnClickListener { ConversationActivity.open(this, userId, username) }

        bestEmpty = findViewById(R.id.userBestEmpty)
        plansEmpty = findViewById(R.id.userPlansEmpty)
        postsEmpty = findViewById(R.id.userPostsEmpty)
        findViewById<RecyclerView>(R.id.userBestList).apply {
            layoutManager = LinearLayoutManager(this@UserProfileActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = bestAdapter
        }
        findViewById<RecyclerView>(R.id.userPlanList).apply {
            layoutManager = LinearLayoutManager(this@UserProfileActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = planAdapter
        }
        findViewById<RecyclerView>(R.id.userPostList).apply {
            layoutManager = LinearLayoutManager(this@UserProfileActivity)
            adapter = postAdapter
        }

        // no bottom strip: the page ends above the navigation bar of the phone
        keepAboveKeyboard(findViewById(R.id.userProfileRoot))

        observe(viewModel.state) { state -> show(state) }
        onEvents(viewModel.events) { event ->
            when (event) {
                UserProfileViewModel.Event.UserNotFound -> {
                    Toast.makeText(this, R.string.user_not_found, Toast.LENGTH_LONG).show()
                    finish()
                }
                is UserProfileViewModel.Event.Error ->
                    Toast.makeText(this, event.error.userMessage(this), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
    }

    private fun show(state: UserProfileViewModel.State) {
        findViewById<TextView>(R.id.userProfileTitle).text = state.username
        header.username.text = state.username
        state.profile?.let { if (it !== shownProfile) showProfile(it) }
        followButton.isEnabled = !state.followBusy

        if (state.pictureLoaded) {
            if (state.picture != null) header.picture.setImageBitmap(state.picture) else header.picture.setImageResource(R.drawable.default_avatar)
        }
        state.posts?.let { posts ->
            postAdapter.show(posts.posts, posts.personalBests.map { it.id }.toSet())
            bestAdapter.submitList(posts.personalBests)
            postsEmpty.isVisible = posts.posts.isEmpty()
            bestEmpty.isVisible = posts.personalBests.isEmpty()
        }
        state.plans?.let { plans ->
            planAdapter.submitList(plans)
            plansEmpty.isVisible = plans.isEmpty()
        }
    }

    // Counts, "Follows you", the follow button and "Share a plan"
    private fun showProfile(profile: UserProfile) {
        shownProfile = profile
        val user = profile.user
        header.showCounts(profile)
        header.subtitle.setText(R.string.follows_you)
        header.subtitle.isVisible = user.followsYou

        // following: the button stops following (neutral); otherwise it follows (favorite color)
        followButton.setText(
            when {
                user.isFollowing -> R.string.unfollow
                user.followsYou -> R.string.follow_back
                else -> R.string.follow
            }
        )
        followButton.setBackgroundResource(
            if (user.isFollowing) R.drawable.button_secondary_background else R.drawable.button_primary_background
        )
        followButton.setTextColor(
            if (user.isFollowing) ColorStateList.valueOf(getColor(R.color.textPrimary))
            else ColorStateList.valueOf(themeColor(R.attr.onAccentColor))
        )
        shareButton.isVisible = user.isMutual
    }

    companion object {
        private const val EXTRA_USER_ID = "user_id"
        private const val EXTRA_USERNAME = "username"

        fun open(context: Context, userId: Long, username: String) {
            context.startActivity(
                Intent(context, UserProfileActivity::class.java)
                    .putExtra(EXTRA_USER_ID, userId)
                    .putExtra(EXTRA_USERNAME, username)
            )
        }
    }
}
