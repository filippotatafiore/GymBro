package com.example.gymbro.network

import com.example.gymbro.plan.Plan
import java.time.Instant

// Data of the social page: users, their profiles, the feed, the workout plans shared between users

// A user in the lists of the social page (search, followers, following, messages).
// isFollowing: the logged user follows them; followsYou: they follow the logged user
data class SocialUser(
    val id: Long,
    val username: String,
    val hasPicture: Boolean,
    val isFollowing: Boolean = false,
    val followsYou: Boolean = false
) {
    // They follow each other: they can share workout plans (Messages)
    val isMutual: Boolean
        get() = isFollowing && followsYou
}

// Profile of a user, with how many posts, followers and followed users
data class UserProfile(
    val user: SocialUser,
    val postsCount: Int,
    val followersCount: Int,
    val followingCount: Int
)

// A post in the feed: its user, and whether it is one of the personal bests of that user
data class FeedPost(val post: Post, val user: SocialUser, val personalBest: Boolean)

// A page of the feed. next: to ask the following (older) page, null on the last one
class FeedPage(val posts: List<FeedPost>, val next: String?)

// The last workout plan shared between the logged user and another user (fromMe: sent by the
// logged user)
data class LastShare(val planName: String, val fromMe: Boolean, val createdAt: Instant)

// A user of Messages. canShare: they follow each other (plans can be shared); unread: plans
// received and not seen yet; last: the last plan shared (null = none yet)
data class Conversation(val user: SocialUser, val canShare: Boolean, val unread: Int, val last: LastShare?)

// A workout plan shared by the logged user (fromMe) or with them: the plan as it was when it was
// sent. savedPlanId: the copy added by the receiver to their plans (null = not added).
// user: the other user (only when the shared plan is asked alone)
data class SharedPlan(
    val id: Long,
    val fromMe: Boolean,
    val plan: Plan,
    val read: Boolean,
    val savedPlanId: Long?,
    val createdAt: Instant,
    val user: SocialUser? = null
)

// The plans shared between the logged user and another user, oldest first
class ConversationDetails(val user: SocialUser, val canShare: Boolean, val shares: List<SharedPlan>)
