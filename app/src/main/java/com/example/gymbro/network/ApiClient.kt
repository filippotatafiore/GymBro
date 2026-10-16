package com.example.gymbro.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.gymbro.Accent
import com.example.gymbro.AppTheme
import com.example.gymbro.R
import com.example.gymbro.data.ChatMessage
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.plan.Plan
import com.example.gymbro.plan.PlanGoal
import com.example.gymbro.plan.PlanLevel
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
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit

// `json` = how the value is written in the requests/answers of the REST API
enum class Sex(val json: String) { MALE("male"), FEMALE("female"), OTHER("other") }

// A user of the app. Body data (null = not set): see the "Personal information" menu of the profile.
// activePlanId: the workout plan followed in the gym sessions (null = none). accent, theme: the look
// of the app chosen by the user ("Customize account").
data class User(
    val id: Long,
    val username: String,
    val email: String,
    val hasPicture: Boolean,
    val heightCm: Double? = null,
    val weightKg: Double? = null,   // used to estimate the calories of the runs
    val birthYear: Int? = null,
    val sex: Sex? = null,
    val activePlanId: Long? = null,
    val accent: Accent = Accent.DEFAULT,
    val theme: AppTheme = AppTheme.DEFAULT
)

// A walking route computed by the server. provider: the service used (e.g. "OpenRouteService");
// attribution: the credits that the service requires to show with its results (null if none)
class WalkingRoute(val distanceMeters: Double, val path: List<GeoPoint>, val provider: String, val attribution: String?)

// An answer of the AI coach. model: the AI model that wrote it (e.g. "openai/gpt-oss-120b");
// provider: the service of the model (e.g. "Groq"); plan: in the chat about a plan, the plan
// changed by the coach (already saved on the server), null if not changed
class CoachReply(val text: String, val model: String, val provider: String, val plan: Plan? = null)

// What the user asks when the coach creates a workout plan. preferences: e.g. "no squat, more free
// weights"; language: of the phone (e.g. "Italian"), the plan is written in it
class PlanRequest(
    val goal: PlanGoal,
    val level: PlanLevel,
    val daysPerWeek: Int,
    val minutesPerSession: Int,
    val preferences: String,
    val language: String
)

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

    // When the coach writes a plan the answer is long: the server can take up to 75 s
    private const val PLAN_TIMEOUT_MS = 90_000

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

    // Username, email and/or password (null = unchanged); the current password is always needed.
    // After a new password the other devices are logged out. Returns the updated user.
    suspend fun updateAccount(
        token: String, currentPassword: String, username: String?, email: String?, newPassword: String?
    ): User {
        val body = JSONObject()
            .put("current_password", currentPassword)
            .putOpt("username", username)
            .putOpt("email", email)
            .putOpt("new_password", newPassword)
        return parseUser(JSONObject(request("PUT", "/api/me/account", token = token, json = body).decodeToString()))
    }

    // Deletes the account and all its data from the server (the password confirms it)
    suspend fun deleteAccount(token: String, password: String) {
        request("POST", "/api/me/delete-account", token = token, json = JSONObject().put("password", password))
    }

    // The look of the app (favorite color and theme), saved in the account; returns the updated user
    suspend fun setAppearance(token: String, accent: Accent, theme: AppTheme): User {
        val body = JSONObject().put("accent", accent.key).put("theme", theme.key)
        return parseUser(JSONObject(request("PUT", "/api/me/appearance", token = token, json = body).decodeToString()))
    }

    // The workout plan followed in the gym sessions (null = none); returns the updated user
    suspend fun setActivePlan(token: String, planId: Long?): User {
        val body = JSONObject().put("plan_id", planId ?: JSONObject.NULL)
        return parseUser(JSONObject(request("PUT", "/api/me/active-plan", token = token, json = body).decodeToString()))
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
    // the model knows what was said before. planId: chat about that workout plan, which the coach
    // sees (as it is now on the server) and can change.
    suspend fun askCoach(token: String, conversation: List<ChatMessage>, planId: Long? = null): CoachReply {
        val messages = JSONArray(conversation.map { message ->
            JSONObject().put("role", message.role.json).put("content", message.text)
        })
        val body = JSONObject().put("messages", messages).putOpt("plan_id", planId)
        // the server may try more than one model: it can take longer than the other requests
        // (even more when the coach writes a plan)
        val answer = request("POST", "/api/chat", token = token, json = body,
            readTimeoutMs = if (planId == null) 60_000 else PLAN_TIMEOUT_MS).decodeToString()
        return parse(answer) { json ->
            CoachReply(
                json.getString("reply"), json.optString("model"), json.optString("provider"),
                plan = if (json.isNull("plan")) null else Plan.fromJson(json.getJSONObject("plan"))
            )
        }
    }


    // --------------------------------------------------------- WORKOUT PLANS

    // Plans of the logged user, the last changed first
    suspend fun getPlans(token: String): List<Plan> {
        val answer = request("GET", "/api/me/plans", token = token).decodeToString()
        return try {
            val array = JSONArray(answer)
            (0 until array.length()).map { Plan.fromJson(array.getJSONObject(it)) }
        } catch (e: JSONException) {
            throw ApiException(500, "Invalid answer of the server")
        }
    }

    suspend fun getPlan(token: String, planId: Long): Plan =
        parse(request("GET", "/api/me/plans/$planId", token = token).decodeToString()) { Plan.fromJson(it) }

    // A new plan written by the AI coach from the body data of the profile and the request
    // (it can take some seconds)
    suspend fun createPlan(token: String, plan: PlanRequest): Plan {
        val body = JSONObject()
            .put("goal", plan.goal.json)
            .put("level", plan.level.json)
            .put("days_per_week", plan.daysPerWeek)
            .put("minutes_per_session", plan.minutesPerSession)
            .put("preferences", plan.preferences)
            .put("language", plan.language)
        val answer = request("POST", "/api/me/plans", token = token, json = body, readTimeoutMs = PLAN_TIMEOUT_MS)
        return parse(answer.decodeToString()) { Plan.fromJson(it) }
    }

    // Saves the changes made by the user (name, days, exercises); returns the saved plan
    suspend fun updatePlan(token: String, plan: Plan): Plan =
        parse(request("PUT", "/api/me/plans/${plan.id}", token = token, json = plan.toJson()).decodeToString()) {
            Plan.fromJson(it)
        }

    suspend fun deletePlan(token: String, planId: Long) {
        request("DELETE", "/api/me/plans/$planId", token = token)
    }

    suspend fun uploadPicture(token: String, jpeg: ByteArray) {
        request("PUT", "/api/me/picture", token = token, jpeg = jpeg)
    }

    // null if the user has no profile picture. sampleSize: 1 = the whole picture (512 px), 4 = a
    // quarter of its side (enough for the small pictures of the lists)
    suspend fun getPicture(userId: Long, sampleSize: Int = 1): Bitmap? {
        val bytes = getPictureBytes(userId) ?: return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
    }

    // The profile picture as JPEG (e.g. to save it on the phone), null if the user has none
    suspend fun getPictureBytes(userId: Long): ByteArray? =
        try {
            request("GET", "/api/users/$userId/picture")
        } catch (e: ApiException) {
            if (e.code == 404) null else throw e
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

    // A finished gym, run or swim session (run: distance, statistics, steps and route followed;
    // swim: pool length, laps and goal). The session is marked as published on the server.
    // gymTitle: e.g. "Gym · Day 1 · Push"
    suspend fun publishSession(token: String, session: TrainingSession, gymTitle: String? = null): Post {
        val body = JSONObject()
            .put("session_id", session.id)
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
                .putOpt("steps", session.steps)
            SessionType.GYM -> body
                .put("kind", PostKind.GYM.json)
                .putOpt("title", gymTitle)
                .put("exercises", JSONArray(session.exercises.orEmpty().map { it.toJson() }))
            SessionType.SWIM -> body
                .put("kind", PostKind.SWIM.json)
                .put("pool_length_m", session.poolLengthM ?: 0.0)
                .put("laps", session.laps ?: 0)
                .putOpt("goal_laps", session.goalLaps)
        }
        return publish(token, body)
    }

    suspend fun deletePost(token: String, postId: Long) {
        request("DELETE", "/api/me/posts/$postId", token = token)
    }

    private suspend fun publish(token: String, body: JSONObject): Post =
        parsePost(JSONObject(request("POST", "/api/me/posts", token = token, json = body).decodeToString()))


    // --------------------------------------------------------- SOCIAL

    // Profile of a user with how many posts, followers and followed users (and whether the logged
    // user and that user follow each other)
    suspend fun getProfile(token: String, userId: Long): UserProfile =
        parse(request("GET", "/api/users/$userId", token = token).decodeToString()) { parseProfile(it) }

    // Users whose username contains the text (the logged user excluded)
    suspend fun searchUsers(token: String, text: String): List<SocialUser> =
        parseArray(request("GET", "/api/users/search?q=" + URLEncoder.encode(text, "UTF-8"), token = token)) {
            parseSocialUser(it)
        }

    // Follows / stops following the user; returns their profile with the new counts
    suspend fun follow(token: String, userId: Long): UserProfile =
        parse(request("POST", "/api/users/$userId/follow", token = token).decodeToString()) { parseProfile(it) }

    suspend fun unfollow(token: String, userId: Long): UserProfile =
        parse(request("DELETE", "/api/users/$userId/follow", token = token).decodeToString()) { parseProfile(it) }

    // The users that follow the user / followed by the user, in alphabetical order
    suspend fun getFollowers(token: String, userId: Long): List<SocialUser> =
        parseArray(request("GET", "/api/users/$userId/followers", token = token)) { parseSocialUser(it) }

    suspend fun getFollowing(token: String, userId: Long): List<SocialUser> =
        parseArray(request("GET", "/api/users/$userId/following", token = token)) { parseSocialUser(it) }

    // The posts of the users followed, newest first, a page at a time (before: the next of the
    // previous page, null for the first one)
    suspend fun getFeed(token: String, before: String?): FeedPage {
        val path = "/api/feed" + (before?.let { "?before=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        return parse(request("GET", path, token = token).decodeToString()) { json ->
            val posts = json.getJSONArray("posts")
            FeedPage(
                posts = (0 until posts.length()).map { index ->
                    val item = posts.getJSONObject(index)
                    FeedPost(parsePost(item), parseSocialUser(item.getJSONObject("user")), item.optBoolean("personal_best"))
                },
                next = json.stringOrNull("next")
            )
        }
    }

    // The public workout plans of a user, and one of them
    suspend fun getPublicPlans(userId: Long): List<Plan> =
        parseArray(request("GET", "/api/users/$userId/plans")) { Plan.fromJson(it) }

    suspend fun getPublicPlan(userId: Long, planId: Long): Plan =
        parse(request("GET", "/api/users/$userId/plans/$planId").decodeToString()) { Plan.fromJson(it) }

    // Adds a copy of a public plan of another user to the plans of the logged user; returns the copy
    suspend fun copyPublicPlan(token: String, userId: Long, planId: Long): Plan =
        parse(request("POST", "/api/users/$userId/plans/$planId/copy", token = token).decodeToString()) {
            Plan.fromJson(it)
        }

    // public: everyone can see the plan on the profile of the user; returns the plan
    suspend fun setPlanPublic(token: String, planId: Long, public: Boolean): Plan {
        val body = JSONObject().put("public", public)
        return parse(request("PUT", "/api/me/plans/$planId/public", token = token, json = body).decodeToString()) {
            Plan.fromJson(it)
        }
    }

    // Messages: the users the logged user can share plans with, the last shared first
    suspend fun getConversations(token: String): List<Conversation> =
        parseArray(request("GET", "/api/me/conversations", token = token)) { json ->
            Conversation(
                user = parseSocialUser(json.getJSONObject("user")),
                canShare = json.getBoolean("can_share"),
                unread = json.optInt("unread"),
                last = if (json.isNull("last")) null else json.getJSONObject("last").let { last ->
                    LastShare(last.getString("plan_name"), last.getBoolean("from_me"), Instant.parse(last.getString("created_at")))
                }
            )
        }

    // The plans shared with a user
    suspend fun getConversation(token: String, userId: Long): ConversationDetails =
        parse(request("GET", "/api/me/conversations/$userId", token = token).decodeToString()) { json ->
            val shares = json.getJSONArray("shares")
            ConversationDetails(
                user = parseSocialUser(json.getJSONObject("user")),
                canShare = json.getBoolean("can_share"),
                shares = (0 until shares.length()).map { parseSharedPlan(shares.getJSONObject(it)) }
            )
        }

    // Sends a copy of a plan of the logged user to the user (they must follow each other)
    suspend fun sharePlan(token: String, userId: Long, planId: Long): SharedPlan {
        val body = JSONObject().put("plan_id", planId)
        return parse(request("POST", "/api/me/conversations/$userId", token = token, json = body).decodeToString()) {
            parseSharedPlan(it)
        }
    }

    // The plans received from the user are seen
    suspend fun markConversationRead(token: String, userId: Long) {
        request("POST", "/api/me/conversations/$userId/read", token = token)
    }

    suspend fun getSharedPlan(token: String, shareId: Long): SharedPlan =
        parse(request("GET", "/api/me/shares/$shareId", token = token).decodeToString()) { parseSharedPlan(it) }

    // The receiver adds the plan received to their plans (the plan added before, if already added)
    suspend fun saveSharedPlan(token: String, shareId: Long): Plan =
        parse(request("POST", "/api/me/shares/$shareId/save", token = token).decodeToString()) { Plan.fromJson(it) }

    private fun parseSocialUser(json: JSONObject) = SocialUser(
        id = json.getLong("id"),
        username = json.getString("username"),
        hasPicture = json.getBoolean("has_picture"),
        isFollowing = json.optBoolean("is_following"),
        followsYou = json.optBoolean("follows_you")
    )

    private fun parseProfile(json: JSONObject) = UserProfile(
        user = parseSocialUser(json),
        postsCount = json.getInt("posts_count"),
        followersCount = json.getInt("followers_count"),
        followingCount = json.getInt("following_count")
    )

    private fun parseSharedPlan(json: JSONObject) = SharedPlan(
        id = json.getLong("id"),
        fromMe = json.getBoolean("from_me"),
        plan = Plan.fromJson(json.getJSONObject("plan")),
        read = json.optBoolean("read"),
        savedPlanId = if (json.isNull("saved_plan_id")) null else json.getLong("saved_plan_id"),
        createdAt = Instant.parse(json.getString("created_at")),
        user = if (json.isNull("user")) null else parseSocialUser(json.getJSONObject("user"))
    )


    // --------------------------------------------------------- FINISHED SESSIONS (LAST SESSIONS, calendar)

    // The finished sessions of the logged user, newest first
    suspend fun getSessions(token: String): List<TrainingSession> {
        val answer = request("GET", "/api/me/sessions", token = token).decodeToString()
        return try {
            val array = JSONArray(answer)
            (0 until array.length()).map { parseSession(array.getJSONObject(it)) }
        } catch (e: JSONException) {
            throw ApiException(500, "Invalid answer of the server")
        }
    }

    // Saves a finished session on the server; returns it with its id. The server saves the same
    // clientId only once, so it can be sent again after an error.
    suspend fun saveSession(token: String, session: TrainingSession): TrainingSession {
        val body = JSONObject()
            .put("client_id", session.clientId)
            .put("type", session.type.name.lowercase())
            .put("start_ms", session.startTime)
            .put("duration_ms", session.durationMillis)
            .putOpt("published_post_id", session.publishedPostId)
        when (session.type) {
            SessionType.RUN -> body
                .putOpt("distance_km", session.distanceKm)
                .putOpt("kcal", session.kcal)
                .putOpt("max_speed_kmh", session.maxSpeedKmh)
                .putOpt("goal_km", session.goalKm)
                .putOpt("route", session.route?.toJson())
                .putOpt("steps", session.steps)
            SessionType.GYM -> body
                .put("exercises", JSONArray(session.exercises.orEmpty().map { it.toJson() }))
                .putOpt("plan_id", session.planId)
                .putOpt("plan_name", session.planName)
                .putOpt("plan_day", session.planDay)
                .putOpt("plan_day_name", session.planDayName)
            // the distance is calculated by the server: laps x pool length
            SessionType.SWIM -> body
                .putOpt("pool_length_m", session.poolLengthM)
                .putOpt("laps", session.laps)
                .putOpt("goal_laps", session.goalLaps)
        }
        return parse(request("POST", "/api/me/sessions", token = token, json = body).decodeToString()) { parseSession(it) }
    }

    // Removes a session from LAST SESSIONS (its post, if published, stays on the profile)
    suspend fun deleteSession(token: String, sessionId: Long) {
        request("DELETE", "/api/me/sessions/$sessionId", token = token)
    }

    private fun parseSession(json: JSONObject) = TrainingSession(
        id = json.getLong("id"),
        clientId = json.getString("client_id"),
        type = SessionType.entries.firstOrNull { it.name.lowercase() == json.getString("type") } ?: SessionType.GYM,
        startTime = json.getLong("start_ms"),
        durationMillis = json.getLong("duration_ms"),
        distanceKm = json.doubleOrNull("distance_km"),
        kcal = json.doubleOrNull("kcal"),
        maxSpeedKmh = json.doubleOrNull("max_speed_kmh"),
        goalKm = json.doubleOrNull("goal_km"),
        route = if (json.isNull("route")) null else routeFromJson(json.getJSONArray("route")),
        exercises = if (json.isNull("exercises")) null else json.getJSONArray("exercises").let { array ->
            (0 until array.length()).map { Exercise.fromJson(array.getJSONObject(it)) }
        },
        publishedPostId = if (json.isNull("published_post_id")) null else json.getLong("published_post_id"),
        planId = if (json.isNull("plan_id")) null else json.getLong("plan_id"),
        planName = json.stringOrNull("plan_name"),
        planDay = if (json.isNull("plan_day")) null else json.getInt("plan_day"),
        planDayName = json.stringOrNull("plan_day_name"),
        steps = json.intOrNull("steps"),
        poolLengthM = json.doubleOrNull("pool_length_m"),
        laps = json.intOrNull("laps"),
        goalLaps = json.intOrNull("goal_laps")
    )


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

    // A JSON object answered by the server, read by [read]
    private fun <T> parse(answer: String, read: (JSONObject) -> T): T =
        try {
            read(JSONObject(answer))
        } catch (e: JSONException) {
            throw ApiException(500, "Invalid answer of the server")
        }

    // A JSON array answered by the server, each object read by [read]
    private fun <T> parseArray(answer: ByteArray, read: (JSONObject) -> T): List<T> =
        try {
            val array = JSONArray(answer.decodeToString())
            (0 until array.length()).map { read(array.getJSONObject(it)) }
        } catch (e: JSONException) {
            throw ApiException(500, "Invalid answer of the server")
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
        sex = json.stringOrNull("sex")?.let { value -> Sex.entries.firstOrNull { it.json == value } },
        activePlanId = if (json.isNull("active_plan_id")) null else json.getLong("active_plan_id"),
        accent = Accent.of(json.stringOrNull("accent")),
        theme = AppTheme.of(json.stringOrNull("theme"))
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
        createdAt = Instant.parse(json.getString("created_at")),
        steps = json.intOrNull("steps"),
        poolLengthM = json.doubleOrNull("pool_length_m"),
        laps = json.intOrNull("laps"),
        goalLaps = json.intOrNull("goal_laps")
    )

    // null if the value is missing or null in the JSON
    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    private fun JSONObject.doubleOrNull(key: String): Double? = if (isNull(key)) null else getDouble(key)

    private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
}
