package com.fragpicker.android.feature.chat

import com.fragpicker.android.core.auth.AuthApiFailure
import com.fragpicker.android.core.network.JsonApi
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.Reader
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicReference

data class ChatEvent(val name: String, val data: JSONObject) {
    override fun toString() = "ChatEvent[$name, content=REDACTED]"
}

/** Reads UTF-8-decoded SSE with bounded lines/events, comments, CRLF and multiline data. */
object ChatEventDecoder {
    suspend fun read(reader: Reader, consume: suspend (ChatEvent) -> Unit) {
        var event = "message"; val data = StringBuilder(); var total = 0
        suspend fun dispatch() {
            if (data.isNotEmpty()) consume(ChatEvent(event, JSONObject(data.toString().removeSuffix("\n"))))
            event = "message"; data.setLength(0)
        }
        val line = StringBuilder()
        while (true) {
            currentCoroutineContext().ensureActive()
            val value = reader.read()
            if (value < 0) { if (data.isNotEmpty() || line.isNotEmpty()) throw IOException("Incomplete SSE event"); return }
            if (++total > 16 * 1024 * 1024) throw IOException("Stream limit exceeded")
            if (value == '\n'.code) {
                val text = line.toString().removeSuffix("\r"); line.setLength(0)
                if (text.isEmpty()) dispatch()
                else if (!text.startsWith(':')) {
                    val colon = text.indexOf(':'); val field = if (colon < 0) text else text.substring(0, colon)
                    val content = if (colon < 0) "" else text.substring(colon + 1).removePrefix(" ")
                    when (field) {
                        "event" -> { if (content.length > 64) throw IOException("Invalid event name"); event = content }
                        "data" -> { if (data.length + content.length > 1_048_576) throw IOException("Event limit exceeded"); data.append(content).append('\n') }
                    }
                }
            } else { if (line.length >= 1_048_576) throw IOException("Line limit exceeded"); line.append(value.toChar()) }
        }
    }
}

class ChatStream(private val api: JsonApi) {
    fun send(session: Long, key: String, message: String): Flow<ChatEvent> = callbackFlow {
        val active = AtomicReference<HttpURLConnection?>()
        val worker = launch(Dispatchers.IO) {
            try {
                api.auth.authorized(api.userId) { bearer ->
                    val connection = api.connection("/api/v1/chat/sessions/$session/messages", bearer)
                    active.set(connection)
                    try {
                        ensureActive(); connection.requestMethod = "POST"; connection.doOutput = true
                        connection.setRequestProperty("Accept", "text/event-stream")
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        connection.setRequestProperty("Idempotency-Key", key)
                        connection.outputStream.use { it.write(JSONObject().put("message", message).toString().toByteArray(Charsets.UTF_8)) }
                        val status = connection.responseCode
                        if (status != 200) {
                            val bytes = connection.errorStream?.use { input ->
                                val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                                while (output.size() < 16_384) {
                                    val count = input.read(buffer, 0, minOf(buffer.size, 16_384 - output.size()))
                                    if (count < 0) break; output.write(buffer, 0, count)
                                }; output.toByteArray()
                            }
                            val code = runCatching { JSONObject(bytes?.toString(Charsets.UTF_8).orEmpty()).optString("code", "HTTP_ERROR") }.getOrDefault("HTTP_ERROR")
                            throw AuthApiFailure(status, code)
                        }
                        if (!connection.contentType.orEmpty().startsWith("text/event-stream")) throw IOException("Invalid stream response")
                        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            ChatEventDecoder.read(reader) { event -> send(event) }
                        }
                    } finally { active.compareAndSet(connection, null); connection.disconnect() }
                }
                close()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { close(failure) }
        }
        awaitClose { active.getAndSet(null)?.disconnect(); worker.cancel() }
    }
}
