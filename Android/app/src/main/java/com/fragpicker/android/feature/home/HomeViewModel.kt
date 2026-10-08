package com.fragpicker.android.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fragpicker.android.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

data class HomeState(val date: String = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(),
    val digest: JSONObject? = null, val items: List<JSONObject> = emptyList(), val loading: Boolean = false,
    val digestError: String? = null, val itemsError: String? = null)

class HomeViewModel(private val api: JsonApi) : ViewModel() {
    private val mutable = MutableStateFlow(HomeState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var generation = 0
    fun refresh(date: String = state.value.date) {
        if (state.value.loading && state.value.date == date) return
        request?.cancel(); val stamp = ++generation
        mutable.value = (if (date == state.value.date) state.value else HomeState(date)).copy(loading = true, digestError = null, itemsError = null)
        request = viewModelScope.launch {
            supervisorScope {
                val digest = async { read { api.request("GET", "/api/v1/daily-digests/$date").also { require(it.getString("date") == date) } } }
                val items = async { read { api.request("GET", "/api/v1/fragments?date=$date&limit=5").also { it.getJSONArray("items").objects() } } }
                val summary = digest.await(); val recent = items.await()
                if (stamp == generation) mutable.value = state.value.copy(loading = false,
                    digest = summary.getOrNull() ?: state.value.digest,
                    items = recent.getOrNull()?.getJSONArray("items")?.objects() ?: state.value.items,
                    digestError = summary.exceptionOrNull()?.let { failureMessage(it as Exception) },
                    itemsError = recent.exceptionOrNull()?.let { failureMessage(it as Exception) })
            }
        }
    }
    fun pause() { request?.cancel(); generation++; mutable.value = state.value.copy(loading = false) }
    private suspend fun read(action: suspend () -> JSONObject): Result<JSONObject> = try { Result.success(action()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
}
