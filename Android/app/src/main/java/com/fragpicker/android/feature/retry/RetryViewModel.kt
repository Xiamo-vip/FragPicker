package com.fragpicker.android.feature.retry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fragpicker.android.core.auth.AuthApiFailure
import com.fragpicker.android.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

data class RetryState(val loading: Boolean = true, val busy: Boolean = false, val canRetry: Boolean = false,
    val requiresReplacement: Boolean = false, val target: String = "", val status: String = "", val errorCode: String = "",
    val pending: PendingRetry? = null, val storageError: Boolean = false, val error: String? = null, val message: String? = null)

class RetryViewModel(private val api: JsonApi, val id: Long, private val requests: RetryRequestStore) : ViewModel() {
    private val mutable = MutableStateFlow(RetryState())
    val state = mutable.asStateFlow()
    private var active = false
    private var reading: Job? = null
    private var generation = 0
    fun resume() { active = true; refresh() }
    fun pause() { active = false; generation++; reading?.cancel(); reading = null }
    fun refresh() {
        if (!active || state.value.busy || reading?.isActive == true) return
        val expected = ++generation
        reading = viewModelScope.launch {
            mutable.value = state.value.copy(loading = true, error = null)
            try {
                val pending = withContext(Dispatchers.IO) { requests.load(id) }
                val result = api.request("GET", "/api/v1/fragments/$id")
                if (expected == generation) mutable.value = state.value.copy(loading = false,
                    canRetry = result.optBoolean("canRetry"), requiresReplacement = result.optBoolean("retryRequiresNewTranscription"),
                    target = result.optString("retryTarget"), status = result.getString("status"),
                    errorCode = result.optionalString("errorCode").orEmpty(), pending = pending, storageError = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (expected == generation) {
                    val loaded = runCatching { withContext(Dispatchers.IO) { requests.load(id) } }
                    mutable.value = state.value.copy(loading = false, pending = loaded.getOrNull(), storageError = loaded.isFailure,
                        error = if (loaded.isFailure) "本机重试标识无法读取，请刷新后再试。" else failureMessage(failure))
                }
            }
        }
    }
    fun retry(replace: Boolean, onAccepted: () -> Unit) {
        if (state.value.busy || state.value.loading || state.value.storageError || (!state.value.canRetry && state.value.pending == null)) return
        generation++; reading?.cancel(); reading = null
        mutable.value = state.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            var sent: PendingRetry? = null
            try {
                val request = withContext(Dispatchers.IO) { requests.getOrCreate(id, replace) }
                sent = request
                mutable.value = state.value.copy(pending = request)
                val result = api.request("POST", "/api/v1/fragments/$id/retry", JSONObject().put("replaceTranscription", request.replace), request.key)
                require(result.getLong("fragmentId") == id && result.getLong("jobVersion") > 0)
                withContext(Dispatchers.IO) { requests.clear(id, request.key) }
                mutable.value = state.value.copy(busy = false, pending = null, canRetry = false,
                    status = result.getString("status"), message = "重试请求已确认，后台将从已保存的步骤继续。")
                if (active) { onAccepted(); refresh() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val known = failure is AuthApiFailure && failure.status in listOf(400, 401, 404, 409, 429)
                val previous = sent
                val pending = runCatching { withContext(Dispatchers.IO) {
                    if (known && previous != null) requests.clear(id, previous.key)
                    requests.load(id)
                } }
                val storageError = pending.isFailure || sent == null
                mutable.value = state.value.copy(busy = false, pending = pending.getOrNull(), storageError = storageError,
                    error = when {
                        storageError -> "本机重试标识无法保存或读取，请先刷新。"
                        pending.getOrNull() != null -> "提交结果尚未确认，请用原请求确认结果。"
                        failure is AuthApiFailure && failure.code == "RETRY_CONFIRM_TRANSCRIPTION" -> "转写状态已变化，请刷新并确认是否重新提交。"
                        failure is AuthApiFailure && failure.code == "RETRY_NOT_FAILED" -> "内容正在处理或已完成，请刷新状态。"
                        failure is AuthApiFailure && failure.code == "RETRY_COOLDOWN" -> "刚刚提交过重试，请稍后再试。"
                        else -> failureMessage(failure)
                    })
            }
        }
    }
}
