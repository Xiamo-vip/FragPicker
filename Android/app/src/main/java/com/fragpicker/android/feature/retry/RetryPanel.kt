package com.fragpicker.android.feature.retry

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fragpicker.android.core.network.JsonApi
import kotlinx.coroutines.awaitCancellation

@Composable
fun RetryPanel(api: JsonApi, id: Long, onAccepted: () -> Unit, sourceStatus: String? = null) {
    val context = LocalContext.current.applicationContext
    val models = remember(api.baseUrl, api.userId, id) { ViewModelStore() }
    val owner = remember(models) { object : ViewModelStoreOwner { override val viewModelStore = models } }
    DisposableEffect(models) { onDispose { models.clear() } }
    val model: RetryViewModel = viewModel(viewModelStoreOwner = owner, factory = viewModelFactory { initializer {
        RetryViewModel(api, id, RetryRequestStore(context.getSharedPreferences("retry_requests", Context.MODE_PRIVATE), api.baseUrl, api.userId))
    } })
    val state by model.state.collectAsStateWithLifecycle()
    val accepted by rememberUpdatedState(onAccepted)
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(model, lifecycle) { lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        model.resume(); try { awaitCancellation() } finally { model.pause() }
    } }
    LaunchedEffect(model, sourceStatus) { model.refresh() }
    var confirm by rememberSaveable(id) { mutableStateOf(false) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(if (state.requiresReplacement) "重新提交转写？" else "继续处理这条内容？") },
        text = { Text(if (state.requiresReplacement) "先前转写失败或提交结果无法确认。重新提交可能再次产生费用，原任务信息会保留供核对。"
            else "后台将从${stageLabel(state.target)}继续，保留已完成内容。若后续需要云转写或 AI 整理，可能产生服务费用。") },
        confirmButton = { TextButton(onClick = { confirm = false; model.retry(state.requiresReplacement) { accepted() } }, modifier = Modifier.testTag("retry_accept")) { Text("确认继续") } },
        dismissButton = { TextButton(onClick = { confirm = false }, modifier = Modifier.testTag("retry_cancel")) { Text("取消") } })
    if (state.canRetry || state.pending != null || state.error != null || state.message != null) OutlinedCard(Modifier.fillMaxWidth().testTag("retry_panel")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (state.pending != null) "确认重试结果" else if (state.canRetry) "恢复处理" else "处理进度", style = MaterialTheme.typography.titleMedium)
            if (state.canRetry) {
                Text("可从${stageLabel(state.target)}继续。${if (state.requiresReplacement) "需要重新提交转写。" else "已完成步骤会保留。"}")
                if (state.errorCode.isNotEmpty()) Text("错误类别：${state.errorCode}", style = MaterialTheme.typography.labelMedium)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("retry_error")) }
            state.message?.let { Text(it, modifier = Modifier.testTag("retry_message")) }
            if (state.busy || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.pending != null) Button(onClick = { model.retry(false) { accepted() } }, enabled = !state.busy && !state.loading && !state.storageError,
                modifier = Modifier.testTag("retry_reconcile")) { Text("确认上次重试") }
            else if (state.canRetry) Button(onClick = { confirm = true }, enabled = !state.busy && !state.loading && !state.storageError,
                modifier = Modifier.testTag("retry_start")) { Text("重试处理") }
            TextButton(onClick = model::refresh, enabled = !state.busy && !state.loading, modifier = Modifier.testTag("retry_refresh")) { Text("刷新重试状态") }
        }
    }
}

private fun stageLabel(stage: String) = when (stage) {
    "QUEUED" -> "视频解析"
    "MEDIA_PENDING" -> "媒体保存"
    "TRANSCRIPTION_PENDING" -> "提交转写"
    "TRANSCRIBING" -> "原转写任务查询"
    "KNOWLEDGE_PENDING" -> "摘要与分类整理"
    "INDEX_PENDING" -> "本地语义索引"
    else -> "保存的步骤"
}
