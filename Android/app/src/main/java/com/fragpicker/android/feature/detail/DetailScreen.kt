package com.fragpicker.android.feature.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fragpicker.android.core.network.*
import com.fragpicker.android.core.ui.SignedCover
import org.json.JSONObject

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
    var section by rememberSaveable(id) { mutableIntStateOf(0) }
    val uriHandler = LocalUriHandler.current
    fun jump(time: Long) { seekMs = time; seekVersion++; if (state.videoUrl == null) model.play() }
    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent, topBar = { TopAppBar(title = { Text("内容详情") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
    }, actions = {
        com.fragpicker.android.feature.deletion.DeletionAction(api, id, onDeleted)
        IconButton(onClick = model::refresh, enabled = !state.loading) { Icon(Icons.Rounded.Refresh, "刷新内容") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("detail_page"), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.content?.let { content ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(content.optionalString("displayTitle") ?: content.optionalString("title") ?: "等待解析的视频",
                            style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${content.optionalString("author") ?: content.optString("sourceHost")} · ${content.optString("businessDate")}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        FilledTonalButton(onClick = { runCatching { uriHandler.openUri(content.getString("sourceUrl")) } },
                            modifier = Modifier.fillMaxWidth().testTag("detail_source")) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("打开原始视频")
                        }
                    }
                }
                if (content.optString("status") == "FAILED") item {
                    com.fragpicker.android.feature.retry.RetryPanel(api, id, model::refresh, content.optString("status"))
                }
                item {
                    val duration = content.optionalObject("knowledge")?.optLong("durationMs") ?: 0
                    state.videoUrl?.let { VideoPlayer(it, seekMs, seekVersion, model::play, state.videoRevision, state.chapters, duration) }
                        ?: Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SignedCover(api, id, content.optionalString("coverMediaPath") != null, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                            Button(onClick = model::play, enabled = !state.mediaLoading && content.optionalString("videoMediaPath") != null,
                                modifier = Modifier.fillMaxWidth().testTag("detail_play")) { Text(if (state.mediaLoading) "获取播放地址…" else "播放视频") }
                            if (duration > 0 && state.chapters.isNotEmpty()) {
                                ChapterProgress(seekMs, duration, state.chapters, content.optionalString("videoMediaPath") != null, ::jump)
                                Text("点击分段进度条跳转，章节内可查看时间概括。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                }
                item {
                    PrimaryTabRow(selectedTabIndex = section) {
                        listOf("概览", "章节", "原文").forEachIndexed { index, label -> Tab(selected = section == index,
                            onClick = { section = index }, text = { Text(label) }, modifier = Modifier.testTag("detail_tab_$index")) }
                    }
                }
                when (section) {
                    0 -> {
                        content.optionalObject("knowledge")?.let { knowledge -> item { KnowledgeOverview(content, knowledge) } }
                            ?: item { Text("整理完成后显示内容概览。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        item { SourceDetails(content) }
                    }
                    1 -> {
                        item {
                            Text("时间概括", style = MaterialTheme.typography.titleLarge)
                            Text("选择一段，从对应时间开始观看。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(state.chapters, key = { "chapter_${it.ordinal}" }) { chapter ->
                            OutlinedCard(onClick = { jump(chapter.startMs) }, modifier = Modifier.fillMaxWidth().testTag("chapter_${chapter.ordinal}")) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("${timestamp(chapter.startMs)} — ${timestamp(chapter.endMs)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                    Text(chapter.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        item {
                            if (state.chaptersLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else if (state.chapterCursor != null) TextButton(onClick = { model.loadChapters() }) { Text("加载其余章节") }
                            else if (state.chapters.isEmpty()) Text("暂无时间要点，可切换原文阅读。")
                            state.chapterError?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { model.loadChapters(first = true) }) { Text("重试章节") } }
                        }
                    }
                    2 -> {
                        item {
                            Text("视频原文", style = MaterialTheme.typography.titleLarge)
                            if (!state.available && !state.paging) Text("转写完成后将在这里显示原文。")
                        }
                        items(state.segments, key = { "sentence:${it.getInt("ordinal")}:${it.getInt("offset")}" }) { segment ->
                            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { jump(segment.getLong("startMs")) }, modifier = Modifier.testTag("transcript_seek"), contentPadding = PaddingValues(0.dp)) {
                                    Text("${timestamp(segment.getLong("startMs"))} — ${timestamp(segment.getLong("endMs"))}${if (segment.optBoolean("continuation")) " · 续" else ""}")
                                }
                                SelectionContainer { Text(segment.getString("text"), style = MaterialTheme.typography.bodyMedium) }
                            } }
                        }
                        item {
                            if (state.paging) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else if (state.cursor != null) OutlinedButton(onClick = { model.page() }) { Text("继续阅读原文") }
                            else if (state.available && state.segments.isEmpty()) Text("暂无原文。")
                        }
                    }
                }
            }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("detail_error")); TextButton(onClick = model::refresh) { Text("重新加载") } } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KnowledgeOverview(content: JSONObject, knowledge: JSONObject) {
    var expanded by rememberSaveable(content.optLong("id")) { mutableStateOf(false) }
    var pointsExpanded by rememberSaveable(content.optLong("id")) { mutableStateOf(false) }
    var summaryOverflow by remember { mutableStateOf(false) }
    var pointsOverflow by remember { mutableStateOf(false) }
    val summary = knowledge.optionalString("summary") ?: knowledge.optionalString("originalSummaryPreview") ?: "暂无摘要"
    val points = knowledge.optJSONArray("points")?.strings().orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        content.optionalString("introduction")?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("核心摘要", style = MaterialTheme.typography.titleMedium)
                SelectionContainer { Text(summary, style = MaterialTheme.typography.bodyLarge, maxLines = if (expanded) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis, onTextLayout = { if (!expanded) summaryOverflow = it.hasVisualOverflow }) }
                if (summaryOverflow || expanded) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("detail_summary_expand")) { Text(if (expanded) "收起摘要" else "阅读完整摘要") }
            }
        }
        if (points.isNotEmpty()) {
            Text("记住这几件事", style = MaterialTheme.typography.titleMedium)
            (if (pointsExpanded) points else points.take(2)).forEachIndexed { index, point ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("%02d".format(index + 1), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(point, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = if (pointsExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis, onTextLayout = { if (!pointsExpanded && it.hasVisualOverflow) pointsOverflow = true })
                }
            }
            if (points.size > 2 || pointsOverflow || pointsExpanded) TextButton(onClick = { pointsExpanded = !pointsExpanded }) { Text(if (pointsExpanded) "收起要点" else "查看完整${points.size}条要点") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            knowledge.optJSONArray("categories")?.strings()?.forEach { Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Text(categoryLabel(it), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            } }
        }
    }
}

@Composable
private fun SourceDetails(content: JSONObject) {
    var expanded by rememberSaveable(content.optLong("id")) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("detail_metadata_toggle")) { Text(if (expanded) "收起保存信息" else "原始标题、备注与关键词") }
            if (expanded) {
                Text("原始标题", style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(content.optionalString("title") ?: "无标题", style = MaterialTheme.typography.bodyMedium) }
                content.optionalString("note")?.let { Text("保存时的想法", style = MaterialTheme.typography.labelLarge); SelectionContainer { Text(it) } }
                content.optionalObject("knowledge")?.optJSONArray("keywords")?.strings()?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
