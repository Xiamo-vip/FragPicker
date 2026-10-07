package com.fragpicker.android.feature.feed

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fragpicker.android.core.auth.UserProfile
import com.fragpicker.android.core.network.JsonApi
import com.fragpicker.android.core.ui.*
import kotlinx.coroutines.awaitCancellation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedRoute(api: JsonApi, user: UserProfile, onOpen: ((Long) -> Unit)? = null,
    incomingId: String? = null, incomingText: String? = null, onImported: (String) -> Unit = {}) {
    val context = LocalContext.current.applicationContext
    val model: FeedViewModel = viewModel(factory = viewModelFactory { initializer {
        FeedViewModel(api, context.getSharedPreferences("feed_last", Context.MODE_PRIVATE))
    } })
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle, model) { lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        model.resume(); try { awaitCancellation() } finally { model.pause() }
    } }
    DisposableEffect(model) { onDispose { model.pause() } }
    var share by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var replaceDraft by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(incomingId, state.sending) {
        if (incomingId != null && incomingText != null && !state.sending) {
            if (share.isBlank() || share == incomingText) { share = incomingText; onImported(incomingId) }
            else replaceDraft = true
        }
    }
    if (replaceDraft && incomingId != null && incomingText != null) AlertDialog(
        onDismissRequest = { replaceDraft = false; onImported(incomingId) }, title = { Text("收到新的视频分享") },
        text = { Text("当前已有投喂草稿，可以替换链接与备注，或保留当前草稿。") },
        confirmButton = { TextButton(onClick = { share = incomingText; note = ""; replaceDraft = false; onImported(incomingId) }) { Text("替换草稿") } },
        dismissButton = { TextButton(onClick = { replaceDraft = false; onImported(incomingId) }) { Text("保留草稿") } })
    val keyboard = LocalSoftwareKeyboardController.current
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text("投喂") },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(bottom = LocalNavigationInset.current).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("欢迎回来，${user.username}", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("留住灵感，\n慢慢消化。", style = MaterialTheme.typography.headlineLarge)
            Text("把视频分享链接交给这里，重点与知识会自动整理到你的个人空间。",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ElevatedCard(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(share, { if (it.length <= 4096) share = it }, label = { Text("视频分享链接") },
                        placeholder = { Text("支持粘贴链接或整段分享内容") }, minLines = 3, maxLines = 6,
                        enabled = !state.sending, modifier = Modifier.fillMaxWidth().testTag("feed_share"))
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
                            ?.toString()?.take(4096)?.let { share = it }
                    }, enabled = !state.sending) { Icon(Icons.Rounded.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text("粘贴分享内容") }
                    OutlinedTextField(note, { if (it.length <= 1000) note = it }, label = { Text("想记住什么？（可选）") },
                        maxLines = 3, enabled = !state.sending, modifier = Modifier.fillMaxWidth().testTag("feed_note"))
                    Button(onClick = { keyboard?.hide(); model.submit(share, note) }, enabled = !state.sending,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("feed_submit")) {
                        Icon(Icons.Rounded.AddLink, null); Spacer(Modifier.width(8.dp)); Text(if (state.sending) "正在投喂…" else "收进知识空间")
                    }
                }
            }
            if (state.sending) ProcessingCard("sending", ProcessingVisual.WAITING, "正在提交", "正在确认后台是否已接收。")
            state.id?.let { id ->
                val visual = when (state.status) { "READY" -> ProcessingVisual.COMPLETE; "FAILED" -> ProcessingVisual.FAILED; else -> ProcessingVisual.WAITING }
                ProcessingCard(id.toString(), visual, when (visual) {
                    ProcessingVisual.COMPLETE -> "已整理完成"
                    ProcessingVisual.FAILED -> "处理未完成"
                    else -> if (state.duplicate) "这条内容已在知识空间" else "后台已接收"
                }, "${state.date} · ${phaseLabel(state.status)}\n处理可在后台继续，稍后也能从回顾页查看。",
                    modifier = Modifier.testTag("feed_result"),
                    actionLabel = if (onOpen != null) "查看内容" else null, action = onOpen?.let { { it(id) } })
                TextButton(onClick = model::retryStatus) { Text("刷新处理状态") }
                com.fragpicker.android.feature.retry.RetryPanel(api, id, model::retryStatus, state.status)
                if (visual == ProcessingVisual.WAITING && onOpen != null) TextButton(onClick = { onOpen(id) }) { Text("查看内容") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("feed_error")) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun phaseLabel(status: String?) = when (status) {
    null -> "正在读取状态"
    "PENDING", "QUEUED" -> "等待解析"
    "PARSING" -> "解析视频"
    "PARSED", "MEDIA_SAVING", "STORING" -> "保存视频与封面"
    "TRANSCRIBING", "MEDIA_STORED", "TRANSCRIPTION_PENDING" -> "转写与提取重点"
    "TRANSCRIBED", "ENRICHING", "INDEXING", "ENRICHED" -> "整理摘要与检索索引"
    "READY" -> "已完成"
    "FAILED" -> "请查看错误提示"
    else -> "处理中"
}
