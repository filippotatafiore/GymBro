package com.example.gymbro.session

import android.content.Context
import com.example.gymbro.R
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.Post
import com.example.gymbro.network.UserSession
import com.example.gymbro.repository.ProfileRepository
import com.example.gymbro.repository.SessionRepository

// Publishes a finished session on the profile of the logged user (only online). A session finished
// offline is sent to the server first. The server marks the session as published, so that it is not
// published twice. Throws ApiException (e.g. server not reachable).
suspend fun publishSession(context: Context, session: TrainingSession): Post {
    val token = UserSession.token ?: throw ApiException(401, "Login required")
    val saved = SessionRepository.sent(session)
    // gym session of a plan: e.g. "Gym · Day 1 · Push"
    val gymTitle = saved.planDayName?.let { context.getString(R.string.gym_day_title, it) }
    val post = ApiClient.publishSession(token, saved, gymTitle)
    SessionRepository.markPublished(saved, post.id)
    ProfileRepository.addPost(post)
    return post
}
