package com.fragpicker.android.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fragpicker.android.core.network.*
import com.fragpicker.android.core.auth.AuthApiFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID

data class HistoryState(val date: String = "", val month: String = "", val days: Map<String, JSONObject> = emptyMap(),
    val items: List<JSONObject> = emptyList(), val before: Long? = null, val loading: Boolean = false,
    val calendarLoading: Boolean = false, val error: String? = null, val calendarError: String? = null,
    val digest: JSONObject? = null, val digestLoading: Boolean = false, val digestError: String? = null,
    val digestMessage: String? = null, val digestBusy: Boolean = false, val digestPendingKey: String? = null,
    val digestStorageError: Boolean = false)

class HistoryViewModel(private val api: JsonApi, private val requests: DigestRequestStore) : ViewModel() {
    private val mutable = MutableStateFlow(HistoryState())
    val state = mutable.asStateFlow()
    private var listJob: Job? = null
    private var calendarJob: Job? = null
    private var generation = 0
    private var digestJob: Job? = null
    private var digestGeneration = 0
    private var summaryActive = false

    fun calendar(month: String) {
        calendarJob?.cancel()
        mutable.value = state.value.copy(month = month, calendarLoading = true, calendarError = null,
            days = if (month == state.value.month) state.value.days else emptyMap())
        calendarJob = viewModelScope.launch {
            try {
                val result = api.request("GET", "/api/v1/calendar?month=$month")
                if (state.value.month == month) mutable.value = state.value.copy(calendarLoading = false,
                    days = result.getJSONArray("days").objects().associateBy { it.getString("date") })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.value = state.value.copy(calendarLoading = false, calendarError = failureMessage(failure)) }
        }
    }
    fun select(date: String) {
        summaryActive = true
        listJob?.cancel(); generation++
        digestJob?.cancel(); digestGeneration++
        mutable.value = state.value.copy(date = date, items = emptyList(), before = null, loading = false, error = null,
            digest = null, digestError = null, digestMessage = null, digestPendingKey = null, digestStorageError = false)
        page(first = true); summary()
    }
    fun page(first: Boolean = false) {
        if (state.value.loading || (!first && state.value.before == null)) return
        val current = state.value; val requestGeneration = generation
        mutable.value = current.copy(loading = true, error = null)
        listJob = viewModelScope.launch {
            try {
                val path = "/api/v1/fragments?date=${current.date}&limit=20" + if (first) "" else "&before=${current.before}"
                val result = api.request("GET", path)
                if (requestGeneration == generation) mutable.value = state.value.copy(loading = false,
                    items = (if (first) emptyList() else state.value.items) + result.getJSONArray("items").objects(), before = result.optionalLong("nextBefore"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (requestGeneration == generation) mutable.value = state.value.copy(loading = false, error = failureMessage(failure)) }
        }
    }
    fun pauseSummary(date: String) {
        if (state.value.date == date) { summaryActive = false; digestJob?.cancel(); digestGeneration++; mutable.value = state.value.copy(digestLoading = false) }
    }
    fun summary() {
        val date = state.value.date; if (date.isEmpty() || !summaryActive) return
        digestJob?.cancel(); val requestGeneration = ++digestGeneration
        mutable.value = state.value.copy(digestLoading = true, digestError = null)
        digestJob = viewModelScope.launch {
            var loaded = false
            try {
                val key = withContext(Dispatchers.IO) { requests.load(date) }
                loaded = true
                if (requestGeneration != digestGeneration) return@launch
                mutable.value = state.value.copy(digestPendingKey = key, digestStorageError = false)
                while (isActive && requestGeneration == digestGeneration) {
                    val result = api.request("GET", "/api/v1/daily-digests/$date")
                    require(result.getString("date") == date)
                    if (requestGeneration != digestGeneration) return@launch
                    mutable.value = state.value.copy(digest = result, digestLoading = false, digestError = null)
                    if (result.optString("status") !in listOf("QUEUED", "RUNNING")) break
                    delay(5_000)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (requestGeneration == digestGeneration) mutable.value = state.value.copy(digestLoading = false,
                digestStorageError = !loaded, digestError = if (!loaded) "本机请求标识无法读取，请先刷新总结再重试。" else failureMessage(failure)) }
        }
    }
    fun regenerate() {
        if (state.value.digestBusy || state.value.digestStorageError || state.value.date.isEmpty()) return
        val date = state.value.date
        val key = state.value.digestPendingKey ?: UUID.randomUUID().toString()
        mutable.value = state.value.copy(digestBusy = true, digestError = null, digestMessage = null, digestPendingKey = key)
        viewModelScope.launch {
            var saved = false
            try {
                withContext(Dispatchers.IO) { requests.save(date, key) }; saved = true
                val response = api.request("POST", "/api/v1/daily-digests/$date/regenerate", key = key)
                require(response.getString("date") == date && response.getLong("revision") > 0)
                withContext(Dispatchers.IO) { requests.clear(date) }
                mutable.value = state.value.copy(digestBusy = false)
                if (state.value.date == date) {
                    mutable.value = state.value.copy(digestPendingKey = null, digestMessage = "请求已确认，正在整理当天已完成的资料。")
                    summary()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                var pending: String? = if (saved) key else null
                var storageError = !saved
                val known = failure is AuthApiFailure && (failure.status in listOf(400,401,404,409,429) || failure.code == "DIGEST_DISABLED")
                if (known && saved) {
                    try { withContext(Dispatchers.IO) { requests.clear(date) }; pending = null }
                    catch (_: Exception) { storageError = true }
                }
                mutable.value = state.value.copy(digestBusy = false)
                if (state.value.date == date) mutable.value = state.value.copy(digestPendingKey = pending, digestStorageError = storageError,
                    digestError = if (storageError) "本机请求标识无法保存或读取，请先刷新总结再重试。"
                        else if (pending != null) "提交结果尚未确认，请使用原请求确认结果。"
                        else if (failure is AuthApiFailure && failure.code == "DIGEST_DISABLED") "每日总结服务尚未启用。"
                        else if (failure is AuthApiFailure && failure.code == "DIGEST_REBUILD_COOLDOWN") "刚刚提交过整理请求，请稍后再试。"
                        else failureMessage(failure))
            }
        }
    }
}
