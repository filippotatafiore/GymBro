package com.example.gymbro.profile

import android.app.Activity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.example.gymbro.network.UserProfile
import com.example.gymbro.R

// Top of the profile pages (res/layout/profile_header.xml): picture, how many posts, followers and
// followed users, username and the line under it. Tap the numbers: onPosts (e.g. scroll to the
// posts), onFollowers / onFollowing (the lists of users).
class ProfileHeader(
    activity: Activity,
    onPosts: () -> Unit,
    onFollowers: () -> Unit,
    onFollowing: () -> Unit
) {
    val picture: ImageView = activity.findViewById(R.id.profilePicture)
    val username: TextView = activity.findViewById(R.id.profileUsername)
    val subtitle: TextView = activity.findViewById(R.id.profileSubtitle)
    private val postsCount: TextView = activity.findViewById(R.id.postsCount)
    private val followersCount: TextView = activity.findViewById(R.id.followersCount)
    private val followingCount: TextView = activity.findViewById(R.id.followingCount)

    init {
        activity.findViewById<View>(R.id.postsCountButton).setOnClickListener { onPosts() }
        activity.findViewById<View>(R.id.followersCountButton).setOnClickListener { onFollowers() }
        activity.findViewById<View>(R.id.followingCountButton).setOnClickListener { onFollowing() }
    }

    fun showCounts(profile: UserProfile) {
        showPostsCount(profile.postsCount)
        followersCount.text = profile.followersCount.toString()
        followingCount.text = profile.followingCount.toString()
    }

    // e.g. after publishing or deleting a post
    fun showPostsCount(count: Int) {
        postsCount.text = count.toString()
    }
}
