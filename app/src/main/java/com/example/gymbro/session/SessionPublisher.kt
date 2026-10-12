package com.example.gymbro.session

import android.content.Context
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.gymBroApp
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.Post
import com.example.gymbro.network.UserSession

// Publishes a finished session on the profile of the logged user, and remembers it in the local
// database, so that the same session is not published twice
suspend fun publishSession(context: Context, session: TrainingSession): Post {
    val token = UserSession.token ?: throw ApiException(401, "Login required")
    val post = ApiClient.publishSession(token, session)
    context.gymBroApp.database.trainingSessionDao().setPublished(session.id, post.id)
    return post
}
