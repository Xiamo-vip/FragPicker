package com.fragpicker.android.feature.feed

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fragpicker.android.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID

data class FeedState(val sending: Boolean = false, val id: Long? = null, val status: String? = null,
                     val date: String = "", val duplicate: Boolean = false, val error: String? = null)

class FeedViewModel(private val api: JsonApi, private val preferences: SharedPreferences) : ViewModel() {
    private val preferenceKey = "${api.baseUrl}:${api.userId}"
    private val mutable = MutableStateFlow(FeedState(id = preferences.getLong(preferenceKey, 0).takeIf { it > 0 }))
    val state = mutable.asStateFlow()
    private var polling: Job? = null
    private var observing = false
    private var requestKey = UUID.randomUUID().toString()
    private var requestPayload: Pair<String, String>? = null

    fun submit(share: String, note: String) {
        if (state.value.sending) return
        if (share.isBlank() || share.length > 4096 || !Regex("https?://\\S+").containsMatchIn(share) || note.length > 1000) {
            mutable.value = state.value.copy(error = "请输入包含视频链接的分享内容，备注最多1000字。")
            return
        }
        val payload = share.trim() to note.trim()
        if (payload != requestPayload) { requestKey = UUID.randomUUID().toString(); requestPayload = payload }
        polling?.cancel(); polling = null
        mutable.value = state.value.copy(sending = true, error = null)
        viewModelScope.launch {
            try {
                val result = api.request("POST", "/api/v1/fragments", JSONObject().put("shareText", payload.first).put("note", payload.second), requestKey)
                val id = result.getLong("fragmentId")
                preferences.edit().putLong(preferenceKey, id).apply()
                mutable.value = FeedState(id = id, status = result.getString("status"), date = result.getString("businessDate"), duplicate = result.getBoolean("duplicate"))
                if (observing) startPolling()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutable.value = state.value.copy(sending = false, error = failureMessage(failure) + " 若提交结果未确认，请保留内容再次提交；会使用同一请求键。")
            }
        }
    }

    fun resume() {
        observing = true
        startPolling()
    }
    private fun startPolling() {
        if (polling?.isActive == true || state.value.id == null || state.value.sending) return
        polling = viewModelScope.launch {
            do {
                if (!refresh()) break
                if (state.value.status in listOf("READY", "FAILED")) break
                delay(5_000)
            } while (isActive)
        }
    }

    fun pause() { observing = false; polling?.cancel(); polling = null }
    fun retryStatus() { mutable.value = state.value.copy(error = null); pause(); resume() }
    private suspend fun refresh(): Boolean {
        val id = state.value.id ?: return false
        return try {
            val result = api.request("GET", "/api/v1/fragments/$id")
            mutable.value = state.value.copy(status = result.getString("status"), date = result.getString("businessDate"),
                error = if (result.getString("status") == "FAILED") "处理未完成，错误类别：${result.optString("errorCode", "UNKNOWN")}" else null)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutable.value = state.value.copy(error = failureMessage(failure)); false }
    }
}
