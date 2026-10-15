package com.example.gymbro.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.gymbro.R
import com.example.gymbro.data.ChatMessage
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.session.Exercise
import com.example.gymbro.session.GeoPoint
import com.example.gymbro.session.PolylineCodec
import com.example.gymbro.session.SessionType
import com.example.gymbro.session.routeFromJson
import com.example.gymbro.session.toJson
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

// `json` = how the value is written in the requests/answers of the REST API
enum class Sex(val json: String) { MALE("male"), FEMALE("female"), OTHER("other") }

// A user of the app. Body data (null = not set): see the "Modify account" menu of the profile.
data class User(
    val id: Long,
    val username: String,
    val email: String,
    val hasPicture: Boolean,
    val heightCm: Double? = null,
    val weightKg: Double? = null,   // used to estimate the calories of the runs
    val birthYear: Int? = null,
    val sex: Sex? = null
)

// A walking route computed by the server. provider: the service used (e.g. "OpenRouteService");
// attribution: the credits that the service requires to show with its results (null if none)
class WalkingRoute(val distanceMeters: Double, val path: List<GeoPoint>, val provider: String, val attribution: String?)

// An answer of the AI coach. model: the AI model that wrote it (e.g. "openai/gpt-oss-120b");
// provider: the service of the model (e.g. "Groq")
class CoachReply(val text: String, val model: String, val provider: String)

data class AuthResult(val token: String, val user: User)

// Error of a request to the server. code = HTTP status (e.g. 401), or 0 if the server was not reachable.
class ApiException(val code: Int, message: String) : Exception(message)

// Message to show to the user for this error
fun ApiException.userMessage(context: Context): String =
    if (code == 0) context.getString(R.string.error_server_unreachable) else message.orEmpty()


// Calls to the GymBro REST API (server/ folder of the project, online on Vercel with the data in
// Firebase Cloud Firestore). The functions are "suspend":
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

    // Body data of the logged user (null = not set); returns the updated user
    suspend fun updateProfile(token: String, heightCm: Double?, weightKg: Double?, birthYear: Int?, sex: Sex?): User {
        val body = JSONObject()
            .put("height_cm", heightCm ?: JSONObject.NULL)
            .put("weight_kg", weightKg ?: JSONObject.NULL)
            .put("birth_year", birthYear ?: JSONObject.NULL)
            .put("sex", sex?.json ?: JSONObject.NULL)
        return parseUser(JSONObject(request("PUT", "/api/me/profile", token = token, json = body).decodeToString()))
    }

    // Walking route origin -> stop (optional) -> destination, computed by the server with
    // OpenRouteService or the Google Routes API
    suspend fun walkingRoute(token: String, origin: GeoPoint, stop: GeoPoint?, destination: GeoPoint): WalkingRoute {
        fun point(p: GeoPoint) = JSONObject().put("lat", p.lat).put("lng", p.lng)
        val body = JSONObject()
            .put("origin", point(origin))
            .put("stop", stop?.let { point(it) } ?: JSONObject.NULL)
            .put("destination", point(destination))
        // the server may try more than one routing service: it can take longer than the other requests
        val answer = request("POST", "/api/routes/walking", token = token, json = body, readTimeoutMs = 30_000)
            .decodeToString()
        return try {
            val json = JSONObject(answer)
            WalkingRoute(
                distanceMeters = json.getDouble("distance_m"),
                path = PolylineCodec.decode(json.getString("polyline")),
                provider = json.optString("provider", "Google Maps"),
                attribution = json.stringOrNull("attribution")
            )
        } catch (e: JSONException) {
            throw ApiException(500, "Invalid answer of the server")
        }
    }

    // Answer of the AI coach to the conversation (oldest message first, the last one written by the
    // user). The server asks it to an AI model (Groq or Gemini): the whole conversation is sent, so
    // the model knows what was said before.
    suspend fun askCoach(token: String, conversation: List<ChatMessage>): CoachReply {
        val messages = JSONArray(conversation.map { message ->
            JSONObject().put("role", message.role.json).put("content", message.text)
        })
        // the server may try more than one model: it can take longer than the other requests
        val answer = request("POST", "/api/chat", token = token, json = JSONObject().put("messages", messages),
            readTimeoutMs = 60_000).decodeToString()
        return try {
            val json = JSONObject(answer)
            CoachReply(json.getString("reply"), json.optString("model"), json.optString("provider"))
        } catch (e: JSONException) {
            throw ApiException(500, "Invalid answer of the server")
        }
    }

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

    // A finished gym or run session (run: distance, statistics and route followed)
    suspend fun publishSession(token: String, session: TrainingSession): Post {
        val body = JSONObject()
            .put("duration_s", session.durationMillis / 1000)
            .put("session_start", Instant.ofEpochMilli(session.startTime).truncatedTo(ChronoUnit.SECONDS).toString())
        when (session.type) {
            SessionType.RUN -> body
                .put("kind", PostKind.RUN.json)
                .put("distance_km", session.distanceKm ?: 0.0)
                .putOpt("kcal", session.kcal)                   // putOpt: not sent if null
                .putOpt("max_speed_kmh", session.maxSpeedKmh)
                .putOpt("goal_km", session.goalKm)
                .putOpt("route", session.route?.toJson())
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
        jpeg: ByteArray? = null,
        readTimeoutMs: Int = 15_000
    ): ByteArray = withContext(Dispatchers.IO) {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = readTimeoutMs
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
        hasPicture = json.getBoolean("has_picture"),
        heightCm = json.doubleOrNull("height_cm"),
        weightKg = json.doubleOrNull("weight_kg"),
        birthYear = if (json.isNull("birth_year")) null else json.getInt("birth_year"),
        sex = json.stringOrNull("sex")?.let { value -> Sex.entries.firstOrNull { it.json == value } }
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
        distanceKm = json.doubleOrNull("distance_km"),
        kcal = json.doubleOrNull("kcal"),
        maxSpeedKmh = json.doubleOrNull("max_speed_kmh"),
        goalKm = json.doubleOrNull("goal_km"),
        route = if (json.isNull("route")) null else routeFromJson(json.getJSONArray("route")),
        exercises = if (json.isNull("exercises")) null else json.getJSONArray("exercises").let { array ->
            (0 until array.length()).map { Exercise.fromJson(array.getJSONObject(it)) }
        },
        sessionStart = json.stringOrNull("session_start")?.let { Instant.parse(it) },
        createdAt = Instant.parse(json.getString("created_at"))
    )

    // null if the value is missing or null in the JSON
    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    private fun JSONObject.doubleOrNull(key: String): Double? = if (isNull(key)) null else getDouble(key)
}
