package com.example.gymbro.repository

import androidx.room.withTransaction
import com.example.gymbro.GymBroApp
import com.example.gymbro.data.PostEntity
import com.example.gymbro.data.ProfileEntity
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.Post
import com.example.gymbro.network.User
import com.example.gymbro.network.UserSession
import com.example.gymbro.social.Avatars
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// The profile of the logged user: posts (and so the personal bests), followers and followed
// users, profile picture (Repository pattern). The profile page shows what is saved on this phone
// (Room, see PostEntity and ProfileEntity), so it can be seen also offline; refresh downloads it
// again from the server. Publishing, deleting posts and changing the picture need the server.
object ProfileRepository {

    private lateinit var app: GymBroApp
    private val postDao get() = app.database.postDao()
    private val profileDao get() = app.database.profileDao()

    fun init(app: GymBroApp) {
        this.app = app
    }

    // The posts of the logged user saved on this phone, newest first, updated at every change
    fun posts(): Flow<List<Post>> {
        val userId = UserSession.userId ?: return flowOf(emptyList())
        return postDao.observe(userId).map { rows -> rows.map { it.toPost() } }
    }

    // Followers, followed users and picture saved on this phone (null: never downloaded)
    fun profile(): Flow<ProfileEntity?> {
        val userId = UserSession.userId ?: return flowOf(null)
        return profileDao.observe(userId)
    }

    // Sends the changes made offline to the user (see UserRepository), then downloads the whole
    // profile and saves it on this phone. Throws ApiException (e.g. server not reachable).
    suspend fun refresh(): User {
        val token = UserSession.token ?: throw ApiException(401, "Login required")
        val user = UserRepository.refresh()
        val counts = ApiClient.getProfile(token, user.id)
        val picture = if (user.hasPicture) ApiClient.getPictureBytes(user.id) else null
        val posts = ApiClient.getPosts(user.id)
        app.database.withTransaction {
            postDao.deleteAll(user.id)
            postDao.save(posts.map { PostEntity.of(user.id, it) })
            profileDao.save(ProfileEntity(user.id, counts.followersCount, counts.followingCount, picture))
        }
        return user
    }

    // A post just published: shown at once in the profile
    suspend fun addPost(post: Post) {
        val userId = UserSession.userId ?: return
        postDao.save(listOf(PostEntity.of(userId, post)))
    }

    // Deletes a post (only online): on the server, then on this phone. Throws ApiException.
    suspend fun deletePost(post: Post) {
        val token = UserSession.token ?: return
        ApiClient.deletePost(token, post.id)
        postDao.delete(post.id)
        // if the post was a session of LAST SESSIONS, that session can be published again
        // (the server knows it already)
        SessionRepository.markUnpublished(post.id)
    }

    // A new profile picture (JPEG), uploaded to the server (only online). Throws ApiException.
    suspend fun changePicture(jpeg: ByteArray) {
        val userId = UserSession.userId ?: return
        val token = UserSession.token ?: return
        ApiClient.uploadPicture(token, jpeg)
        UserSession.user?.let { UserSession.updateUser(it.copy(hasPicture = true)) }
        val saved = profileDao.get(userId)
        profileDao.save(ProfileEntity(userId, saved?.followersCount ?: 0, saved?.followingCount ?: 0, jpeg))
        // the small picture of the lists (social page) is downloaded again
        Avatars.forget(userId)
    }

    // Account deleted
    suspend fun deleteAll(userId: Long) {
        postDao.deleteAll(userId)
        profileDao.delete(userId)
    }
}
