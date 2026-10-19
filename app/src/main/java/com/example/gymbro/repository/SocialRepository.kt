package com.example.gymbro.repository

import android.graphics.Bitmap
import android.util.LruCache
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.FeedPage
import com.example.gymbro.network.Post
import com.example.gymbro.network.SocialUser
import com.example.gymbro.network.UserProfile
import com.example.gymbro.network.UserSession
import com.example.gymbro.plan.Plan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

// The other users (social page): search, profiles, follows, feed, their posts, public workout
// plans and pictures (Repository pattern). Everything comes from the server: only online.
// The functions throw ApiException (e.g. 404: the user does not exist anymore).
interface SocialRepository {

    // Increased at every follow / unfollow of the logged user: the pages that depend on the
    // follows (feed, lists of users) load their data again when they are shown
    val followsVersion: Int

    // Users by username (upper/lower case ignored)
    suspend fun search(text: String): List<SocialUser>

    // Profile of a user, with how many posts, followers and followed users
    suspend fun profile(userId: Long): UserProfile

    // Follows the user, or stops following them: their profile with the new counts
    suspend fun follow(userId: Long): UserProfile
    suspend fun unfollow(userId: Long): UserProfile

    // Followers and followed users of a user, in alphabetical order
    suspend fun followers(userId: Long): List<SocialUser>
    suspend fun following(userId: Long): List<SocialUser>

    // A page of the feed (posts of the users followed): the newest (before = null), or the one
    // older than [before] (FeedPage.next of the previous page)
    suspend fun feed(before: String?): FeedPage

    // The posts and the public workout plans of a user
    suspend fun posts(userId: Long): List<Post>
    suspend fun publicPlans(userId: Long): List<Plan>
    suspend fun publicPlan(userId: Long, planId: Long): Plan

    // The profile picture of a user (null: none)
    suspend fun picture(userId: Long): Bitmap?

    // The small picture of a user in the lists (a quarter of the side): downloaded once and kept
    // in memory while the app is open; null: none, or not downloaded (e.g. offline).
    // To call from the main thread.
    fun cachedSmallPicture(userId: Long): Bitmap?
    suspend fun smallPicture(userId: Long): Bitmap?

    // The user changed their picture: the old one is not shown anymore
    fun forgetPicture(userId: Long)
}

// scope: of the app, a picture is downloaded also if the page is closed (ready for the next page)
class SocialRepositoryImpl(private val scope: CoroutineScope) : SocialRepository {

    override var followsVersion = 0
        private set

    private val smallPictures = LruCache<Long, Bitmap>(120)
    // downloads in progress: two rows of the same user wait for the same download
    private val downloads = mutableMapOf<Long, Deferred<Bitmap?>>()

    private val token: String
        get() = UserSession.token ?: throw ApiException(401, "Login required")

    override suspend fun search(text: String) = ApiClient.searchUsers(token, text)

    override suspend fun profile(userId: Long) = ApiClient.getProfile(token, userId)

    override suspend fun follow(userId: Long) = ApiClient.follow(token, userId).also { followsVersion++ }

    override suspend fun unfollow(userId: Long) = ApiClient.unfollow(token, userId).also { followsVersion++ }

    override suspend fun followers(userId: Long) = ApiClient.getFollowers(token, userId)

    override suspend fun following(userId: Long) = ApiClient.getFollowing(token, userId)

    override suspend fun feed(before: String?) = ApiClient.getFeed(token, before)

    override suspend fun posts(userId: Long) = ApiClient.getPosts(userId)

    override suspend fun publicPlans(userId: Long) = ApiClient.getPublicPlans(userId)

    override suspend fun publicPlan(userId: Long, planId: Long) = ApiClient.getPublicPlan(userId, planId)

    override suspend fun picture(userId: Long) = ApiClient.getPicture(userId)

    override fun cachedSmallPicture(userId: Long): Bitmap? = smallPictures.get(userId)

    override suspend fun smallPicture(userId: Long): Bitmap? {
        smallPictures.get(userId)?.let { return it }
        val download = downloads.getOrPut(userId) {
            scope.async {
                try {
                    ApiClient.getPicture(userId, SMALL_SAMPLE_SIZE)?.also { smallPictures.put(userId, it) }
                } catch (e: ApiException) {
                    null
                } finally {
                    downloads.remove(userId)
                }
            }
        }
        return download.await()
    }

    override fun forgetPicture(userId: Long) {
        smallPictures.remove(userId)
    }

    companion object {
        // a quarter of the side (128 px)
        private const val SMALL_SAMPLE_SIZE = 4
    }
}
