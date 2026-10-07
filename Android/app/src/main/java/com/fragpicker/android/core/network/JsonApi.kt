package com.fragpicker.android.core.network

import com.fragpicker.android.BuildConfig
import com.fragpicker.android.core.auth.AuthApiFailure
import com.fragpicker.android.core.auth.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class JsonApi(val auth: AuthRepository, val userId: Long, val baseUrl: String = BuildConfig.API_BASE_URL) {
    init {
        val uri = URI(baseUrl)
        require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
    }

    suspend fun request(method: String, path: String, body: JSONObject? = null, key: String? = null): JSONObject =
        auth.authorized(userId) { bearer -> raw(method, path, body, key, bearer) }

    fun connection(path: String, bearer: String): HttpURLConnection {
        require(path.startsWith("/api/v1/") && !path.contains(".."))
        return (URI(baseUrl.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $bearer")
            setRequestProperty("Accept", "application/json")
        }
    }

    private suspend fun raw(method: String, path: String, body: JSONObject?, key: String?, bearer: String): JSONObject =
        suspendCancellableCoroutine { continuation ->
            val connection = connection(path, bearer)
            continuation.invokeOnCancellation { connection.disconnect() }
            Dispatchers.IO.asExecutor().execute {
                try {
                    if (!continuation.isActive) return@execute
                    connection.requestMethod = method
                    key?.let { connection.setRequestProperty("Idempotency-Key", it) }
                    if (body != null) {
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val output = StringBuilder()
                        val buffer = CharArray(4096)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            if (output.length + count > 1_048_576) throw IOException("Response limit exceeded")
                            output.append(buffer, 0, count)
                        }
                        output.toString()
                    }.orEmpty()
                    if (status !in 200..299) throw AuthApiFailure(status,
                        runCatching { JSONObject(text).optString("code", "HTTP_ERROR") }.getOrDefault("HTTP_ERROR"))
                    continuation.resume(if (status == 204) JSONObject() else JSONObject(text))
                } catch (failure: Exception) { continuation.resumeWithException(failure) }
                finally { connection.disconnect() }
            }
        }
}

fun failureMessage(error: Exception): String = when {
    error is AuthApiFailure && error.status == 401 -> "会话已过期，请在设置中退出后重新登录。"
    error is AuthApiFailure && error.status == 404 -> "内容不存在或暂时不可用。"
    error is AuthApiFailure && error.status == 429 -> "请求较多，请稍后再试。"
    error is AuthApiFailure && error.status == 400 -> "内容格式不符合要求，请检查输入。"
    error is AuthApiFailure && error.status == 503 -> "服务暂时不可用，请稍后再试。"
    error is IOException -> "无法连接服务器，请检查网络后重试。"
    else -> "操作未完成，请重试。"
}
