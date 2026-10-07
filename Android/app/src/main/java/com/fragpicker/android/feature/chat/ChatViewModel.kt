package com.fragpicker.android.feature.chat

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fragpicker.android.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

data class ChatState(val initialized: Boolean = false, val storageError: Boolean = false, val busy: Boolean = false,
    val session: Long? = null, val sessions: List<JSONObject> = emptyList(), val sessionCursor: String? = null,
    val sessionsLoading: Boolean = false, val turns: List<JSONObject> = emptyList(), val before: Long? = null,
    val historyLoading: Boolean = false, val pending: PendingMessage? = null, val streamText: String = "",
    val phase: String = "", val error: String? = null)

class ChatViewModel(private val api: JsonApi, private val vault: PendingMessageVault,
    private val preferences: SharedPreferences) : ViewModel() {
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()
    private val preferenceKey = "${api.baseUrl}:${api.userId}"
    private var turnJob: Job? = null
    private var historyJob: Job? = null
    private var sessionJob: Job? = null
    private var initialization: Job? = null
    private var generation = 0
    private var turnGeneration = 0
    init { initialize() }

    fun initialize() {
        if (initialization?.isActive == true) return
        initialization = viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { vault.load() }
                val id = saved?.sessionId ?: preferences.getLong(preferenceKey, 0).takeIf { it > 0 }
                mutable.value = state.value.copy(initialized = true, pending = saved, session = id, storageError = false, error = null)
                sessions(); if (id != null) history(first = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = state.value.copy(initialized = true, storageError = true, error = "本机待确认消息无法读取，请重试读取；可以先查看服务器对话历史。") }
        }
    }
    fun sessions(more: Boolean = false) {
        if (sessionJob?.isActive == true || more && state.value.sessionCursor == null) return
        val cursor = if (more) "&cursor=" + URLEncoder.encode(state.value.sessionCursor!!, "UTF-8") else ""
        mutable.value = state.value.copy(sessionsLoading = true)
        sessionJob = viewModelScope.launch {
            try {
                val result = api.request("GET", "/api/v1/chat/sessions?limit=20$cursor")
                mutable.value = state.value.copy(sessionsLoading = false, sessions =
                    ((if (more) state.value.sessions else emptyList()) + result.getJSONArray("items").objects()).distinctBy { it.getLong("sessionId") },
                    sessionCursor = result.optionalString("nextCursor"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.value = state.value.copy(sessionsLoading = false, error = failureMessage(failure)) }
        }
    }
    fun choose(id: Long?) {
        if (state.value.busy || state.value.pending != null) return
        historyJob?.cancel(); generation++
        mutable.value = state.value.copy(session = id, turns = emptyList(), before = null, streamText = "", error = null, historyLoading = false)
        preferences.edit().putLong(preferenceKey, id ?: 0).apply()
        if (id != null) history(first = true)
    }
    fun history(first: Boolean = false) {
        if (state.value.busy) return
        val session = state.value.session ?: return
        if (state.value.historyLoading || !first && state.value.before == null) return
        val requestGeneration = generation
        val before = if (first) "" else "&before=${state.value.before}"
        mutable.value = state.value.copy(historyLoading = true)
        historyJob = viewModelScope.launch {
            try {
                val result = api.request("GET", "/api/v1/chat/sessions/$session/messages?limit=10$before")
                if (requestGeneration == generation && state.value.session == session) mutable.value = state.value.copy(historyLoading = false,
                    turns = ((if (first) emptyList() else state.value.turns) + result.getJSONArray("items").objects())
                        .distinctBy { it.getLong("turnId") }.sortedBy { it.getLong("turnId") }, before = result.optionalLong("nextBefore"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (requestGeneration == generation) mutable.value = state.value.copy(historyLoading = false, error = failureMessage(failure)) }
        }
    }
    fun send(question: String) {
        if (!state.value.initialized || state.value.storageError || state.value.busy || state.value.pending != null) return
        val text = question.trim()
        if (text.isBlank() || text.length > 4000 || text.codePointCount(0, text.length) > 2000) {
            mutable.value = state.value.copy(error = "请输入1至2000字的问题。"); return
        }
        mutable.value = state.value.copy(pending = PendingMessage(text, UUID.randomUUID().toString(), UUID.randomUUID().toString(), state.value.session))
        start()
    }
    fun confirm() { if (!state.value.busy && state.value.pending != null && !state.value.storageError) start() }
    fun pause() {
        turnGeneration++
        turnJob?.cancel(); turnJob = null
        if (state.value.busy) mutable.value = state.value.copy(busy = false, streamText = "", phase = "连接已暂停", error = "可确认上次消息结果，保存的请求键仍有效。")
    }
    private fun start() {
        if (turnJob?.isActive == true) return
        val requestGeneration = ++turnGeneration
        historyJob?.cancel(); generation++
        mutable.value = state.value.copy(busy = true, historyLoading = false, error = null, streamText = "", phase = "正在连接")
        turnJob = viewModelScope.launch {
            try {
                save(state.value.pending!!)
                if (state.value.pending!!.turnId != null) { poll(); return@launch }
                if (state.value.pending!!.sessionId == null) {
                    val pending = state.value.pending!!
                    // A fixed title makes an uncertain session-creation replay identical.
                    val session = api.request("POST", "/api/v1/chat/sessions", JSONObject().put("title", "新对话"), pending.createKey).getLong("sessionId")
                    save(pending.copy(sessionId = session))
                    mutable.value = state.value.copy(session = session)
                    preferences.edit().putLong(preferenceKey, session).apply()
                }
                val pending = state.value.pending!!
                var terminal = false
                ChatStream(api).send(pending.sessionId!!, pending.key, pending.question).collect { event ->
                    when (event.name) {
                        "accepted" -> {
                            require(event.data.getLong("sessionId") == pending.sessionId)
                            save(state.value.pending!!.copy(turnId = event.data.getLong("turnId")))
                            mutable.value = state.value.copy(phase = "正在检索与回答")
                        }
                        "round_start" -> mutable.value = state.value.copy(streamText = "", phase = "正在检索与回答")
                        "delta" -> {
                            val text = state.value.streamText + event.data.getString("text")
                            if (text.length > 65_536) throw IOException("Answer limit exceeded")
                            mutable.value = state.value.copy(streamText = text)
                        }
                        "round_end" -> if (event.data.getBoolean("intermediate")) mutable.value = state.value.copy(streamText = "", phase = "正在查找资料")
                        "done", "failed", "pending" -> terminal = snapshot(event.data)
                        "error" -> mutable.value = state.value.copy(error = chatError(event.data.optString("code")))
                    }
                }
                if (!terminal) {
                    if (state.value.pending?.turnId != null) poll() else throw IOException("Message result unconfirmed")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.value = state.value.copy(error = failureMessage(failure) + " 点击确认上次消息结果，沿用原请求键。", streamText = "") }
            finally { if (requestGeneration == turnGeneration) { mutable.value = state.value.copy(busy = false); sessions() } }
        }
    }
    private suspend fun save(value: PendingMessage) {
        withContext(Dispatchers.IO) { vault.save(value) }
        mutable.value = state.value.copy(pending = value)
    }
    private suspend fun poll() {
        repeat(80) {
            val pending = state.value.pending ?: return
            val result = api.request("GET", "/api/v1/chat/sessions/${pending.sessionId}/messages/${pending.turnId}")
            if (snapshot(result)) return
            mutable.value = state.value.copy(phase = "后台仍在处理，正在确认结果")
            delay(3_000)
        }
        throw IOException("Message still pending")
    }
    private suspend fun snapshot(value: JSONObject): Boolean {
        val pending = state.value.pending ?: return true
        require(value.getLong("sessionId") == pending.sessionId && value.getLong("turnId") == pending.turnId)
        val terminal = value.getString("state") in listOf("COMPLETED", "FAILED")
        mutable.value = state.value.copy(turns = (state.value.turns.filter { it.getLong("turnId") != value.getLong("turnId") } + value).sortedBy { it.getLong("turnId") },
            streamText = "", error = if (value.getString("state") == "FAILED") chatError(value.optString("errorCode")) else state.value.error)
        if (terminal) {
            withContext(Dispatchers.IO) { vault.clear() }
            mutable.value = state.value.copy(pending = null)
        }
        return terminal
    }
}

fun chatError(code: String) = when (code) {
    "CHAT_DISABLED" -> "服务器尚未启用聊天服务。"
    "CHAT_BUSY" -> "服务忙碌，请稍后再次提问。"
    "CHAT_CANCELLED", "CHAT_INTERRUPTED", "CHAT_TIMEOUT" -> "这次回答未完成，可以重新提问。"
    else -> "对话未完成（${code.ifBlank { "UNKNOWN" }}），请稍后重试。"
}
