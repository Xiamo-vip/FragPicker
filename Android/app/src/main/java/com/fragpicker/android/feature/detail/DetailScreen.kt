package com.fragpicker.android.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fragpicker.android.core.network.*
import com.fragpicker.android.core.ui.SignedCover

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailRoute(api: JsonApi, id: Long, onBack: () -> Unit, onDeleted: () -> Unit = onBack) {
    val store = remember(id, api.userId) { ViewModelStore() }
    val owner = remember(store) { object : ViewModelStoreOwner { override val viewModelStore = store } }
    DisposableEffect(store) { onDispose { store.clear() } }
    val model: DetailViewModel = viewModel(viewModelStoreOwner = owner, factory = viewModelFactory { initializer { DetailViewModel(api, id) } })
    val state by model.state.collectAsStateWithLifecycle()
    var seekMs by rememberSaveable(id) { mutableLongStateOf(0) }
    var seekVersion by rememberSaveable(id) { mutableIntStateOf(0) }
    val uriHandler = LocalUriHandler.current
    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent, topBar = { TopAppBar(title = { Text("内容详情") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
    }, actions = {
        com.fragpicker.android.feature.deletion.DeletionAction(api, id, onDeleted)
        IconButton(onClick = model::refresh, enabled = !state.loading) { Icon(Icons.Rounded.Refresh, "刷新内容") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("detail_page"), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.content?.let { content ->
                item {
                    Text(content.optionalString("title") ?: "等待解析的视频", style = MaterialTheme.typography.headlineSmall)
                    Text("${content.optionalString("author") ?: content.optString("sourceHost")} · ${content.optString("businessDate")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (content.optString("status") != "READY") Text("处理状态：${content.optString("status")}", style = MaterialTheme.typography.labelLarge)
                }
                item { com.fragpicker.android.feature.retry.RetryPanel(api, id, model::refresh, content.optString("status")) }
                item {
                    state.videoUrl?.let { VideoPlayer(it, seekMs, seekVersion, model::play, state.videoRevision) } ?: Column {
                        SignedCover(api, id, content.optionalString("coverMediaPath") != null, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                        Button(onClick = model::play, enabled = !state.mediaLoading && content.optionalString("videoMediaPath") != null,
                            modifier = Modifier.fillMaxWidth().testTag("detail_play")) { Text(if (state.mediaLoading) "获取播放地址…" else "播放视频") }
                    }
                }
                content.optionalObject("knowledge")?.let { knowledge ->
                    item {
                        ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("内容摘要", style = MaterialTheme.typography.titleLarge)
                            SelectionContainer { Text(knowledge.optionalString("summary") ?: knowledge.optionalString("originalSummaryPreview") ?: "暂无摘要") }
                            if (knowledge.optBoolean("summaryTruncated") || knowledge.optBoolean("originalSummaryTruncated")) Text("摘要仅展示预览，可继续阅读下方原文。", style = MaterialTheme.typography.labelMedium)
                            knowledge.optJSONArray("points")?.strings()?.forEachIndexed { index, point -> Text("${index + 1}. $point") }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                knowledge.optJSONArray("categories")?.strings()?.forEach { SuggestionChip(onClick = {}, label = { Text(categoryLabel(it)) }) }
                            }
                            knowledge.optJSONArray("keywords")?.strings()?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), color = MaterialTheme.colorScheme.primary) }
                        } }
                    }
                }
                content.optionalString("note")?.let { item { Text("保存时的想法", style = MaterialTheme.typography.titleMedium); SelectionContainer { Text(it) } } }
                item { TextButton(onClick = { runCatching { uriHandler.openUri(content.getString("sourceUrl")) } }) { Text("打开原始分享链接") } }
                item {
                    Text("视频原文", style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = state.kind == "SENTENCE", onClick = { model.transcript("SENTENCE") }, label = { Text("逐句原文") })
                        FilterChip(selected = state.kind == "KEY_POINT", onClick = { model.transcript("KEY_POINT") }, label = { Text("时间要点") })
                    }
                    if (!state.available && !state.paging) Text("转写完成后将在这里显示原文。")
                }
                items(state.segments, key = { "${state.kind}:${it.getInt("ordinal")}:${it.getInt("offset")}" }) { segment ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${timestamp(segment.getLong("startMs"))} — ${timestamp(segment.getLong("endMs"))}${if (segment.optBoolean("continuation")) " · 续" else ""}",
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable {
                                seekMs = segment.getLong("startMs"); seekVersion++; if (state.videoUrl == null) model.play()
                            }.testTag("transcript_seek"))
                        SelectionContainer { Text(segment.getString("text")) }
                    } }
                }
                item {
                    if (state.paging) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else if (state.cursor != null) OutlinedButton(onClick = { model.page() }) { Text("继续阅读原文") }
                    else if (state.available && state.segments.isEmpty()) Text("暂无此类原文。")
                }
            }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("detail_error")); TextButton(onClick = model::refresh) { Text("重新加载") } } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
