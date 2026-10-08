package com.fragpicker.android.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fragpicker.android.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.net.URI

data class DetailState(val loading: Boolean = true, val content: JSONObject? = null,
    val segments: List<JSONObject> = emptyList(), val cursor: JSONObject? = null,
    val paging: Boolean = false, val available: Boolean = false, val kind: String = "SENTENCE",
    val mediaLoading: Boolean = false, val videoUrl: String? = null, val videoRevision: Int = 0, val error: String? = null,
    val chapters: List<VideoChapter> = emptyList(), val chapterCursor: JSONObject? = null,
    val chaptersLoading: Boolean = false, val chapterError: String? = null)

class DetailViewModel(private val api: JsonApi, val id: Long) : ViewModel() {
    private val mutable = MutableStateFlow(DetailState())
    val state = mutable.asStateFlow()
    private var transcriptJob: Job? = null
    private var contentJob: Job? = null
    private var chapterJob: Job? = null
    private var generation = 0
    init { refresh() }
    fun refresh() {
        if (contentJob?.isActive == true) return
        contentJob = viewModelScope.launch {
            mutable.value = state.value.copy(loading = true, error = null)
            try {
                val content = api.request("GET", "/api/v1/fragments/$id/content")
                mutable.value = state.value.copy(loading = false, content = content)
                transcript(state.value.kind)
                loadChapters(first = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.value = state.value.copy(loading = false, error = failureMessage(failure)) }
        }
    }
    fun loadChapters(first: Boolean = false) {
        if (!first && (state.value.chaptersLoading || state.value.chapterCursor == null)) return
        chapterJob?.cancel()
        mutable.value = state.value.copy(chaptersLoading = true, chapterError = null)
        chapterJob = viewModelScope.launch {
            try {
                var cursor = if (first) null else state.value.chapterCursor
                val collected = (if (first) emptyList() else state.value.chapters).associateBy { it.ordinal }.toMutableMap()
                // Bound each automatic batch; very long material can load the remaining chapters on demand.
                for (page in 0 until 10) {
                    val ordinal = cursor?.getInt("ordinal") ?: 0
                    val offset = cursor?.getInt("offset") ?: 0
                    val result = api.request("GET", "/api/v1/fragments/$id/transcript?kind=KEY_POINT&ordinal=$ordinal&offset=$offset&limit=20")
                    result.getJSONArray("items").objects().filter { !it.optBoolean("continuation") }.forEach {
                        val number = it.getInt("ordinal")
                        collected[number] = VideoChapter(number, it.getLong("startMs"), it.getLong("endMs"), it.getString("text"))
                    }
                    cursor = result.optionalObject("nextCursor")
                    if (cursor == null) break
                }
                mutable.value = state.value.copy(chapters = collected.values.sortedWith(compareBy({ it.startMs }, { it.ordinal })),
                    chapterCursor = cursor, chaptersLoading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.value = state.value.copy(chaptersLoading = false, chapterError = failureMessage(failure)) }
        }
    }
    fun transcript(kind: String) {
        transcriptJob?.cancel(); generation++
        mutable.value = state.value.copy(kind = kind, segments = emptyList(), cursor = null, error = null, paging = false)
        page(first = true)
    }
    fun page(first: Boolean = false) {
        if (state.value.paging || (!first && state.value.cursor == null)) return
        val current = state.value; val requestGeneration = generation
        val ordinal = if (first) 0 else current.cursor!!.getInt("ordinal")
        val offset = if (first) 0 else current.cursor!!.getInt("offset")
        mutable.value = current.copy(paging = true, error = null)
        transcriptJob = viewModelScope.launch {
            try {
                val result = api.request("GET", "/api/v1/fragments/$id/transcript?kind=${current.kind}&ordinal=$ordinal&offset=$offset&limit=10")
                if (requestGeneration == generation) mutable.value = state.value.copy(paging = false,
                    segments = (if (first) emptyList() else state.value.segments) + result.getJSONArray("items").objects(),
                    available = result.getBoolean("transcriptionAvailable"), cursor = result.optionalObject("nextCursor"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (requestGeneration == generation) mutable.value = state.value.copy(paging = false, error = failureMessage(failure)) }
        }
    }
    fun play() {
        if (state.value.mediaLoading) return
        mutable.value = state.value.copy(mediaLoading = true, error = null)
        viewModelScope.launch {
            try {
                val media = api.request("GET", "/api/v1/fragments/$id/media?kind=VIDEO")
                val url = media.getString("url"); val uri = URI(url)
                require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
                mutable.value = state.value.copy(videoUrl = url, mediaLoading = false, videoRevision = state.value.videoRevision + 1)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.value = state.value.copy(mediaLoading = false, error = failureMessage(failure)) }
        }
    }
}
