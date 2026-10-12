package com.example.gymbro.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.gymbro.R
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.session.Exercise
import com.example.gymbro.session.SessionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit

data class User(val id: Long, val username: String, val email: String, val hasPicture: Boolean)

data class AuthResult(val token: String, val user: User)

// Error of a request to the server. code = HTTP status (e.g. 401), or 0 if the server was not reachable.
class ApiException(val code: Int, message: String) : Exception(message)

// Message to show to the user for this error
fun ApiException.userMessage(context: Context): String =
    if (code == 0) context.getString(R.string.error_server_unreachable) else message.orEmpty()


// Calls to the GymBro REST API (server/ folder of the project). The functions are "suspend":
// the network operations run in background (Dispatchers.IO), never blocking the app.
object ApiClient {

    // Address of the server: API_BASE_URL in local.properties (see app/build.gradle.kts)
    private lateinit var baseUrl: String

    fun init(context: Context) {
        baseUrl = context.getString(R.string.api_base_url).trimEnd('/')
    }

    suspend fun register(username: String, email: String, password: String): AuthResult {
        val body = JSONObject()
            .put("username", username)
            .put("email", email)
            .put("password", password)
        return parseAuth(request("POST", "/api/register", json = body))
    }

    suspend fun login(email: String, password: String): AuthResult {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
        return parseAuth(request("POST", "/api/login", json = body))
    }

    suspend fun logout(token: String) {
        request("POST", "/api/logout", token = token)
    }

    suspend fun getMe(token: String): User =
        parseUser(JSONObject(request("GET", "/api/me", token = token).decodeToString()))

    suspend fun uploadPicture(token: String, jpeg: ByteArray) {
        request("PUT", "/api/me/picture", token = token, jpeg = jpeg)
    }

    // null if the user has no profile picture
    suspend fun getPicture(userId: Long): Bitmap? {
        val bytes = try {
            request("GET", "/api/users/$userId/picture")
        } catch (e: ApiException) {
            if (e.code == 404) return null else throw e
        }
        return withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    }

    // Posts of a user, newest first
    suspend fun getPosts(userId: Long): List<Post> {
        val array = JSONArray(request("GET", "/api/users/$userId/posts").decodeToString())
        return (0 until array.length()).map { parsePost(array.getJSONObject(it)) }
    }

    // A result added manually, e.g. Deadlift - weight - 150 (kg) - higher is better
    suspend fun publishManual(
        token: String,
        title: String,
        metric: Metric,
        better: Better,
        amount: Double,     // seconds (time) or kg (weight)
        notes: String
    ): Post {
        val body = JSONObject()
            .put("kind", PostKind.MANUAL.json)
            .put("title", title)
            .put("metric", metric.json)
            .put("better", better.json)
            .put("amount", amount)
            .put("notes", notes)
        return publish(token, body)
    }

    // A finished gym or run session
    suspend fun publishSession(token: String, session: TrainingSession): Post {
        val body = JSONObject()
            .put("duration_s", session.durationMillis / 1000)
            .put("session_start", Instant.ofEpochMilli(session.startTime).truncatedTo(ChronoUnit.SECONDS).toString())
        when (session.type) {
            SessionType.RUN -> body
                .put("kind", PostKind.RUN.json)
                .put("distance_km", session.distanceKm ?: 0.0)
            SessionType.GYM -> body
                .put("kind", PostKind.GYM.json)
                .put("exercises", JSONArray(session.exercises.orEmpty().map { it.toJson() }))
        }
        return publish(token, body)
    }

    suspend fun deletePost(token: String, postId: Long) {
        request("DELETE", "/api/me/posts/$postId", token = token)
    }

    private suspend fun publish(token: String, body: JSONObject): Post =
        parsePost(JSONObject(request("POST", "/api/me/posts", token = token, json = body).decodeToString()))


    // --------------------------------------------------------- HTTP

    // Sends a request and returns the body of the answer; throws ApiException in case of error
    private suspend fun request(
        method: String,
        path: String,
        token: String? = null,
        json: JSONObject? = null,
        jpeg: ByteArray? = null
    ): ByteArray = withContext(Dispatchers.IO) {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }

            val body = json?.toString()?.toByteArray() ?: jpeg
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", if (json != null) "application/json" else "image/jpeg")
                connection.outputStream.use { it.write(body) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw ApiException(code, parseErrorMessage(response, code))
            response
        } catch (e: IOException) {
            throw ApiException(0, e.message ?: "Server not reachable")
        } finally {
            connection.disconnect()
        }
    }

    // The server answers errors as {"error": "message"}
    private fun parseErrorMessage(response: ByteArray, code: Int): String =
        try {
            JSONObject(response.decodeToString()).getString("error")
        } catch (e: JSONException) {
            "Server error ($code)"
        }

    private fun parseAuth(response: ByteArray): AuthResult {
        val json = JSONObject(response.decodeToString())
        return AuthResult(json.getString("token"), parseUser(json.getJSONObject("user")))
    }

    private fun parseUser(json: JSONObject) = User(
        id = json.getLong("id"),
        username = json.getString("username"),
        email = json.optString("email"),
        hasPicture = json.getBoolean("has_picture")
    )

    private fun parsePost(json: JSONObject) = Post(
        id = json.getLong("id"),
        kind = PostKind.entries.first { it.json == json.getString("kind") },
        title = json.getString("title"),
        metric = json.stringOrNull("metric")?.let { value -> Metric.entries.first { it.json == value } },
        better = json.stringOrNull("better")?.let { value -> Better.entries.first { it.json == value } },
        amount = if (json.isNull("amount")) null else json.getDouble("amount"),
        notes = json.stringOrNull("notes"),
        durationSeconds = if (json.isNull("duration_s")) null else json.getLong("duration_s"),
        distanceKm = if (json.isNull("distance_km")) null else json.getDouble("distance_km"),
        exercises = if (json.isNull("exercises")) null else json.getJSONArray("exercises").let { array ->
            (0 until array.length()).map { Exercise.fromJson(array.getJSONObject(it)) }
        },
        sessionStart = json.stringOrNull("session_start")?.let { Instant.parse(it) },
        createdAt = Instant.parse(json.getString("created_at"))
    )

    // null if the value is missing or null in the JSON
    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
}
