package com.example.gymbro.session

import android.content.Context
import com.example.gymbro.R
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.Post
import com.example.gymbro.network.UserSession

// Publishes a finished session (already saved on the server) on the profile of the logged user.
// The server marks the session as published, so that it is not published twice.
suspend fun publishSession(context: Context, session: TrainingSession): Post {
    val token = UserSession.token ?: throw ApiException(401, "Login required")
    // gym session of a plan: e.g. "Gym · Day 1 · Push"
    val gymTitle = session.planDayName?.let { context.getString(R.string.gym_day_title, it) }
    val post = ApiClient.publishSession(token, session, gymTitle)
    TrainingSessions.markPublished(session.id, post.id)
    return post
}
