package com.example.gymbro

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.UserProfile
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.plan.PlanAdapter
import com.example.gymbro.profile.PersonalBestAdapter
import com.example.gymbro.profile.PersonalBests
import com.example.gymbro.profile.PostAdapter
import com.example.gymbro.profile.ProfileHeader
import com.example.gymbro.social.Follows
import kotlinx.coroutines.launch

// Profile of another user, opened from the social page (feed, search, lists of followers): picture,
// how many posts, followers and followed users (tap them to see the lists), the button to follow the
// user or to stop following them, and "Share a plan" (Messages) when they follow each other. Then
// their personal bests, public workout plans (tap one to see it and add it to your plans) and posts.
// Nothing of the user can be changed here.
class UserProfileActivity : ThemedActivity() {

    private var userId = 0L
    private var username = ""
    private var profile: UserProfile? = null

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
        if (!UserSession.isLoggedIn) {
            WelcomeActivity.open(this)
            finish()
            return
        }
        userId = intent.getLongExtra(EXTRA_USER_ID, 0L)
        username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()

        // your own profile: the Profile page, where it can be changed
        if (userId == UserSession.userId) {
            startActivity(Intent(this, ProfileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            finish()
            return
        }

        // link to the layout
        setContentView(R.layout.activity_user_profile)

        findViewById<View>(R.id.userProfileBackButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.userProfileTitle).text = username
        val scroll = findViewById<NestedScrollView>(R.id.userProfileScroll)
        header = ProfileHeader(
            this,
            onPosts = { scroll.smoothScrollTo(0, findViewById<View>(R.id.userPostsTitle).top) },
            onFollowers = { FollowListActivity.open(this, userId, username, FollowListActivity.Tab.FOLLOWERS) },
            onFollowing = { FollowListActivity.open(this, userId, username, FollowListActivity.Tab.FOLLOWING) }
        )
        header.username.text = username
        header.subtitle.isVisible = false

        followButton = findViewById(R.id.followButton)
        shareButton = findViewById(R.id.shareWithUserButton)
        followButton.setOnClickListener { changeFollow() }
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
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    // Profile, picture, posts and public plans of the user, from the server (in background)
    private fun load() {
        val token = UserSession.token ?: return
        lifecycleScope.launch {
            try {
                showProfile(ApiClient.getProfile(token, userId))
                val picture = if (profile?.user?.hasPicture == true) ApiClient.getPicture(userId) else null
                if (picture != null) header.picture.setImageBitmap(picture) else header.picture.setImageResource(R.drawable.default_avatar)

                val posts = ApiClient.getPosts(userId)
                val bests = PersonalBests.from(posts)
                postAdapter.show(posts, bests.map { it.id }.toSet())
                bestAdapter.submitList(bests)
                postsEmpty.isVisible = posts.isEmpty()
                bestEmpty.isVisible = bests.isEmpty()

                val plans = ApiClient.getPublicPlans(userId)
                planAdapter.submitList(plans)
                plansEmpty.isVisible = plans.isEmpty()
            } catch (e: ApiException) {
                if (e.code == 404) {
                    Toast.makeText(this@UserProfileActivity, R.string.user_not_found, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this@UserProfileActivity, e.userMessage(this@UserProfileActivity), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Counts, "Follows you", the follow button and "Share a plan"
    private fun showProfile(profile: UserProfile) {
        this.profile = profile
        val user = profile.user
        username = user.username
        findViewById<TextView>(R.id.userProfileTitle).text = user.username
        header.username.text = user.username
        header.showCounts(profile)
        header.subtitle.setText(R.string.follows_you)
        header.subtitle.isVisible = user.followsYou

        // following: the button stops following (neutral); otherwise it follows (favorite color)
        followButton.isEnabled = true
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

    // Follows the user, or stops following them
    private fun changeFollow() {
        val token = UserSession.token ?: return
        val following = profile?.user?.isFollowing ?: return
        followButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val updated = if (following) ApiClient.unfollow(token, userId) else ApiClient.follow(token, userId)
                Follows.changed()
                showProfile(updated)
            } catch (e: ApiException) {
                followButton.isEnabled = true
                Toast.makeText(this@UserProfileActivity, e.userMessage(this@UserProfileActivity), Toast.LENGTH_LONG).show()
            }
        }
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
