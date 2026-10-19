package com.example.gymbro.repository

import android.content.Context
import androidx.room.withTransaction
import com.example.gymbro.R
import com.example.gymbro.data.AppDatabase
import com.example.gymbro.data.PostEntity
import com.example.gymbro.data.ProfileEntity
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.Better
import com.example.gymbro.network.Metric
import com.example.gymbro.network.Post
import com.example.gymbro.network.User
import com.example.gymbro.network.UserSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// The profile of the logged user: posts (and so the personal bests), followers and followed
// users, profile picture (Repository pattern). The profile page shows what is saved on this phone
// (Room, see PostEntity and ProfileEntity), so it can be seen also offline; refresh downloads it
// again from the server. Publishing, deleting posts and changing the picture need the server.
interface ProfileRepository {

    // The posts of the logged user saved on this phone, newest first, updated at every change
    fun posts(): Flow<List<Post>>

    // Followers, followed users and picture saved on this phone (null: never downloaded)
    fun profile(): Flow<ProfileEntity?>

    // Sends the changes made offline to the user (see UserRepository), then downloads the whole
    // profile and saves it on this phone. Throws ApiException (e.g. server not reachable).
    suspend fun refresh(): User

    // Publishes a result added manually (only online): shown at once in the profile.
    // Throws ApiException.
    suspend fun publishManual(title: String, metric: Metric, better: Better, amount: Double, notes: String): Post

    // Publishes a finished session (only online). A session finished offline is sent to the server
    // first. The server marks the session as published, so that it is not published twice.
    // Throws ApiException (e.g. server not reachable).
    suspend fun publishSession(session: TrainingSession): Post

    // Deletes a post (only online): on the server, then on this phone. Throws ApiException.
    suspend fun deletePost(post: Post)

    // A new profile picture (JPEG), uploaded to the server (only online). Throws ApiException.
    suspend fun changePicture(jpeg: ByteArray)

    // Account deleted
    suspend fun deleteAll(userId: Long)
}

// context: of the app, for the title of the gym sessions published
class ProfileRepositoryImpl(
    private val context: Context,
    private val database: AppDatabase,
    private val users: UserRepository,
    private val sessions: SessionRepository,
    private val social: SocialRepository
) : ProfileRepository {

    private val postDao get() = database.postDao()
    private val profileDao get() = database.profileDao()

    override fun posts(): Flow<List<Post>> {
        val userId = UserSession.userId ?: return flowOf(emptyList())
        return postDao.observe(userId).map { rows -> rows.map { it.toPost() } }
    }

    override fun profile(): Flow<ProfileEntity?> {
        val userId = UserSession.userId ?: return flowOf(null)
        return profileDao.observe(userId)
    }

    override suspend fun refresh(): User {
        val token = UserSession.token ?: throw ApiException(401, "Login required")
        val user = users.refresh()
        val counts = ApiClient.getProfile(token, user.id)
        val picture = if (user.hasPicture) ApiClient.getPictureBytes(user.id) else null
        val posts = ApiClient.getPosts(user.id)
        database.withTransaction {
            postDao.deleteAll(user.id)
            postDao.save(posts.map { PostEntity.of(user.id, it) })
            profileDao.save(ProfileEntity(user.id, counts.followersCount, counts.followingCount, picture))
        }
        return user
    }

    override suspend fun publishManual(title: String, metric: Metric, better: Better, amount: Double, notes: String): Post {
        val token = UserSession.token ?: throw ApiException(401, "Login required")
        return ApiClient.publishManual(token, title, metric, better, amount, notes).also { addPost(it) }
    }

    override suspend fun publishSession(session: TrainingSession): Post {
        val token = UserSession.token ?: throw ApiException(401, "Login required")
        val saved = sessions.sent(session)
        // gym session of a plan: e.g. "Gym · Day 1 · Push"
        val gymTitle = saved.planDayName?.let { context.getString(R.string.gym_day_title, it) }
        val post = ApiClient.publishSession(token, saved, gymTitle)
        sessions.markPublished(saved, post.id)
        addPost(post)
        return post
    }

    // A post just published: shown at once in the profile
    private suspend fun addPost(post: Post) {
        val userId = UserSession.userId ?: return
        postDao.save(listOf(PostEntity.of(userId, post)))
    }

    override suspend fun deletePost(post: Post) {
        val token = UserSession.token ?: return
        ApiClient.deletePost(token, post.id)
        postDao.delete(post.id)
        // if the post was a session of LAST SESSIONS, that session can be published again
        // (the server knows it already)
        sessions.markUnpublished(post.id)
    }

    override suspend fun changePicture(jpeg: ByteArray) {
        val userId = UserSession.userId ?: return
        val token = UserSession.token ?: return
        ApiClient.uploadPicture(token, jpeg)
        UserSession.user?.let { UserSession.updateUser(it.copy(hasPicture = true)) }
        val saved = profileDao.get(userId)
        profileDao.save(ProfileEntity(userId, saved?.followersCount ?: 0, saved?.followingCount ?: 0, jpeg))
        // the small picture of the lists (social page) is downloaded again
        social.forgetPicture(userId)
    }

    override suspend fun deleteAll(userId: Long) {
        postDao.deleteAll(userId)
        profileDao.delete(userId)
    }
}
