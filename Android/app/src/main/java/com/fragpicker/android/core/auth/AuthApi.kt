package com.fragpicker.android.core.auth

import com.fragpicker.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

data class UserProfile(val id: Long, val username: String, val businessZone: String)
class LoginSession(val accessToken: String, val refreshToken: String, val user: UserProfile) {
    override fun toString() = "LoginSession[tokens=REDACTED]"
}
class AuthApiFailure(val status: Int, val code: String) : IOException("Authentication request failed ($status)")

class AuthApi(private val baseUrl: String = BuildConfig.API_BASE_URL) {
    init {
        val uri = URI(baseUrl)
        require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null
            && uri.query == null && uri.fragment == null) { "Invalid backend URL" }
    }

    suspend fun login(username: String, password: String): LoginSession = session(request("POST", "/api/v1/auth/login",
        JSONObject().put("username", username).put("password", password)))

    suspend fun register(username: String, password: String): UserProfile = user(request("POST", "/api/v1/auth/register",
        JSONObject().put("username", username).put("password", password)))

    suspend fun refresh(refreshToken: String): LoginSession = session(request("POST", "/api/v1/auth/refresh",
        JSONObject().put("refreshToken", refreshToken)))

    suspend fun currentUser(accessToken: String): UserProfile = user(request("GET", "/api/v1/users/me", bearer = accessToken))

    private fun session(json: JSONObject): LoginSession {
        require(json.getString("tokenType") == "Bearer") { "Unsupported session type" }
        return LoginSession(json.getString("accessToken"), json.getString("refreshToken"), user(json.getJSONObject("user")))
    }

    private fun user(json: JSONObject) = UserProfile(json.getLong("id"), json.getString("username"), json.getString("businessZone"))

    private suspend fun request(method: String, path: String, body: JSONObject? = null, bearer: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = URI(baseUrl.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("Accept", "application/json")
                if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val result = StringBuilder()
                    val buffer = CharArray(2048)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (result.length + count > 65_536) throw IOException("Backend response exceeds limit")
                        result.append(buffer, 0, count)
                    }
                    result.toString()
                }.orEmpty()
                if (status !in 200..299) {
                    val code = try { JSONObject(text).optString("code", "HTTP_ERROR") } catch (_: Exception) { "HTTP_ERROR" }
                    throw AuthApiFailure(status, code)
                }
                JSONObject(text)
            } finally { connection.disconnect() }
        }
}
