package com.fragpicker.android.feature.clipboard

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fragpicker.android.core.auth.AuthApiFailure
import com.fragpicker.android.core.network.JsonApi
import com.fragpicker.android.core.network.failureMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.net.URI
import java.time.LocalDate
import java.util.UUID

data class ClipboardState(val initialized: Boolean = false, val preview: ClipboardPreview? = null,
    val pending: PendingClipboardFeed? = null, val visible: Boolean = false, val sending: Boolean = false,
    val error: String? = null, val receipt: Long? = null, val blocked: Boolean = false)

class ClipboardViewModel(private val api: JsonApi, private val store: ClipboardRequestStore,
    private val feedPreferences: SharedPreferences) : ViewModel() {
    private val mutable = MutableStateFlow(ClipboardState())
    val state = mutable.asStateFlow()
    private var previewJob: Job? = null
    private var previewGeneration = 0L
    private var lastAttempt = -20_000L
    private var suppressedEpoch = -1L
    private var currentEntry = -1L
    private var lastReadEntry = -1L
    private var dismissedPendingEntry = -1L
    private var memorySeen: String? = null
    private val feedKey = "${api.baseUrl}:${api.userId}"

    init {
        viewModelScope.launch {
            try {
                val pending = withContext(Dispatchers.IO) { store.load() }
                mutable.value = ClipboardState(initialized = true, pending = pending, preview = pending?.preview, visible = pending != null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.value = ClipboardState(initialized = true, blocked = true, visible = true,
                    error = "无法读取上次投喂记录。请稍后重启应用，再确认提交结果。")
            }
        }
    }
    fun suppressEntry(epoch: Long) { suppressedEpoch = epoch; pausePreview() }
    fun enter(epoch: Long): Boolean {
        if (epoch == suppressedEpoch || !state.value.initialized || state.value.blocked) return false
        currentEntry = epoch
        if (state.value.pending != null && dismissedPendingEntry != epoch) mutable.value = state.value.copy(visible = true)
        if (lastReadEntry == epoch) return false
        lastReadEntry = epoch
        return state.value.preview == null && state.value.pending == null && !state.value.sending
    }
    fun inspect(candidate: ClipboardCandidate) {
        if (state.value.preview != null || state.value.pending != null || state.value.sending || state.value.blocked) return
        if (candidate.fingerprint == memorySeen || SystemClock.elapsedRealtime() - lastAttempt < 20_000) return
        pausePreview()
        val generation = previewGeneration
        previewJob = viewModelScope.launch {
            try {
                if (withContext(Dispatchers.IO) { store.seen(candidate.fingerprint) }) { memorySeen = candidate.fingerprint; return@launch }
                lastAttempt = SystemClock.elapsedRealtime()
                val result = api.request("POST", "/api/v1/fragments/preview", JSONObject().put("shareText", candidate.url))
                require(result.getBoolean("resourceAvailable") && result.getString("contentType").startsWith("video/"))
                val url = result.getString("sourceUrl")
                require(url == candidate.url && ClipboardLinks.extract(url) == url)
                val host = result.getString("sourceHost")
                require(URI(url).host == host)
                val title = display(result.getString("title"), 160).ifBlank { "视频资源" }
                val author = display(result.optString("author").takeUnless { it == "null" }.orEmpty(), 80)
                if (generation != previewGeneration) return@launch
                memorySeen = candidate.fingerprint
                mutable.value = state.value.copy(preview = ClipboardPreview(url, title, author, host), visible = true, error = null)
                withContext(Dispatchers.IO) { store.markSeen(candidate.fingerprint) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                // Invalid/unreadable clipboard is quiet. Transient errors can be retried on a later entry.
                if (failure is AuthApiFailure && failure.status in listOf(400, 422)) {
                    memorySeen = candidate.fingerprint
                    runCatching { withContext(Dispatchers.IO) { store.markSeen(candidate.fingerprint) } }
                }
            }
        }
    }
    fun pausePreview() { previewGeneration++; previewJob?.cancel(); previewJob = null }
    fun dismiss() {
        if (state.value.sending) return
        if (state.value.pending != null) dismissedPendingEntry = currentEntry
        mutable.value = if (state.value.pending != null || state.value.blocked) state.value.copy(visible = false)
            else state.value.copy(preview = null, visible = false, error = null)
    }

    fun submit() {
        val preview = state.value.preview ?: return
        if (state.value.sending || state.value.blocked) return
        pausePreview()
        mutable.value = state.value.copy(sending = true, error = null)
        viewModelScope.launch {
            try {
                val pending = state.value.pending ?: PendingClipboardFeed(UUID.randomUUID().toString(), preview)
                withContext(Dispatchers.IO) { store.save(pending) }
                ensureActive()
                mutable.value = state.value.copy(pending = pending)
                val result = api.request("POST", "/api/v1/fragments",
                    JSONObject().put("shareText", pending.preview.url).put("note", ""), pending.key)
                val id = result.getLong("fragmentId")
                require(id > 0)
                LocalDate.parse(result.getString("businessDate"))
                require(result.getString("status") in setOf("QUEUED", "PARSING", "MEDIA_PENDING", "MEDIA_SAVING",
                    "TRANSCRIPTION_PENDING", "TRANSCRIPTION_WORKING", "KNOWLEDGE_PENDING", "KNOWLEDGE_ENRICHING", "INDEX_PENDING", "INDEXING", "READY", "FAILED"))
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!feedPreferences.edit().putLong(feedKey, id).commit()) throw java.io.IOException("Feed receipt could not be saved")
                    store.clear(pending.key)
                }
                mutable.value = ClipboardState(initialized = true, receipt = id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutable.value = state.value.copy(sending = false, error = failureMessage(failure) +
                    if (state.value.pending != null) " 再次确认会查询同一笔投喂，不会新建重复任务。" else " 未发送投喂，请稍后重试。")
            }
        }
    }
    fun acknowledgeReceipt() { mutable.value = state.value.copy(receipt = null) }
    private fun display(value: String, maximum: Int): String = value.filterNot {
        Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt()
    }.trim().let { it.substring(0, it.offsetByCodePoints(0, minOf(maximum, it.codePointCount(0, it.length)))) }
}