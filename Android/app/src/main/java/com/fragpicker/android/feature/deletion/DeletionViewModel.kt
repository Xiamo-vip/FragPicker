package com.fragpicker.android.feature.deletion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fragpicker.android.core.auth.AuthApiFailure
import com.fragpicker.android.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DeletionState(val loading: Boolean = true, val pending: Boolean = false, val busy: Boolean = false,
    val storageError: Boolean = false, val deleted: Boolean = false, val error: String? = null)

class DeletionViewModel(private val api: JsonApi, val id: Long, private val requests: DeletionRequestStore,
    private val clearLocalContent: () -> Unit) : ViewModel() {
    private val mutable = MutableStateFlow(DeletionState())
    val state = mutable.asStateFlow()
    fun load() {
        if (state.value.busy) return
        viewModelScope.launch {
            try {
                val pending = withContext(Dispatchers.IO) { requests.pending(id) }
                mutable.value = state.value.copy(loading = false, pending = pending, storageError = false, error = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = state.value.copy(loading = false, storageError = true, error = "本机删除标识无法读取，请重试读取。") }
        }
    }
    fun delete() {
        if (state.value.loading || state.value.busy || state.value.storageError || state.value.deleted) return
        mutable.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            var saved = false
            try {
                withContext(Dispatchers.IO) { requests.save(id) }; saved = true
                mutable.value = state.value.copy(pending = true)
                val result = api.request("DELETE", "/api/v1/fragments/$id")
                require(result.getLong("fragmentId") == id && result.getString("status") == "DELETED")
                // Server acknowledgement wins even if a local cache cannot be cleared. The same DELETE is replayable.
                withContext(Dispatchers.IO) { runCatching { clearLocalContent() }; runCatching { requests.clear(id) } }
                mutable.value = state.value.copy(busy = false, deleted = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val known = failure is AuthApiFailure && failure.status in listOf(400, 401, 404, 409, 429)
                val loaded = runCatching { withContext(Dispatchers.IO) { if (known && saved) requests.clear(id); requests.pending(id) } }
                mutable.value = state.value.copy(busy = false, pending = loaded.getOrDefault(saved), storageError = loaded.isFailure || !saved,
                    error = if (!saved || loaded.isFailure) "本机删除标识无法保存或读取，请先重试读取。"
                        else if (loaded.getOrDefault(saved)) "删除结果尚未确认，请确认上次删除。"
                        else failureMessage(failure))
            }
        }
    }
}
