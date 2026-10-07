package com.fragpicker.android.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fragpicker.android.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

data class HistoryState(val date: String = "", val month: String = "", val days: Map<String, JSONObject> = emptyMap(),
    val items: List<JSONObject> = emptyList(), val before: Long? = null, val loading: Boolean = false,
    val calendarLoading: Boolean = false, val error: String? = null, val calendarError: String? = null)

class HistoryViewModel(private val api: JsonApi) : ViewModel() {
    private val mutable = MutableStateFlow(HistoryState())
    val state = mutable.asStateFlow()
    private var listJob: Job? = null
    private var calendarJob: Job? = null
    private var generation = 0

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
        listJob?.cancel(); generation++
        mutable.value = state.value.copy(date = date, items = emptyList(), before = null, loading = false, error = null)
        page(first = true)
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
}
