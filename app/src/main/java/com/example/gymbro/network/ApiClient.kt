package com.example.gymbro.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.gymbro.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class User(val id: Long, val username: String, val email: String, val hasPicture: Boolean)

data class AuthResult(val token: String, val user: User)

// A result published by a user, e.g. title = "Deadlift", value = "150 kg"
data class PublishedResult(val id: Long, val title: String, val value: String, val createdAt: Instant)

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

    suspend fun getResults(userId: Long): List<PublishedResult> {
        val array = JSONArray(request("GET", "/api/users/$userId/results").decodeToString())
        return (0 until array.length()).map { parseResult(array.getJSONObject(it)) }
    }

    suspend fun publishResult(token: String, title: String, value: String): PublishedResult {
        val body = JSONObject()
            .put("title", title)
            .put("value", value)
        return parseResult(JSONObject(request("POST", "/api/me/results", token = token, json = body).decodeToString()))
    }


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

    private fun parseResult(json: JSONObject) = PublishedResult(
        id = json.getLong("id"),
        title = json.getString("title"),
        value = json.getString("value"),
        createdAt = Instant.parse(json.getString("created_at"))
    )
}
