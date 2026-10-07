package com.fragpicker.android.feature.deletion

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fragpicker.android.core.network.JsonApi
import com.fragpicker.android.feature.retry.RetryRequestStore

@Composable
fun DeletionAction(api: JsonApi, id: Long, onDeleted: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val model: DeletionViewModel = viewModel(key = "deletion:${api.baseUrl}:${api.userId}:$id", factory = viewModelFactory { initializer {
        DeletionViewModel(api, id, DeletionRequestStore(context.getSharedPreferences("deletion_requests", Context.MODE_PRIVATE), api.baseUrl, api.userId)) {
            val retry = RetryRequestStore(context.getSharedPreferences("retry_requests", Context.MODE_PRIVATE), api.baseUrl, api.userId)
            retry.load(id)?.let { retry.clear(id, it.key) }
            val feed = context.getSharedPreferences("feed_last", Context.MODE_PRIVATE)
            val key = "${api.baseUrl}:${api.userId}"
            if (feed.getLong(key, 0) == id) check(feed.edit().remove(key).commit())
        }
    } })
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val deleted by rememberUpdatedState(onDeleted)
    LaunchedEffect(model) { model.load() }
    LaunchedEffect(state.deleted, lifecycleState) { if (state.deleted && lifecycleState.isAtLeast(Lifecycle.State.STARTED)) deleted() }
    var confirm by rememberSaveable(api.userId, id) { mutableStateOf(false) }
    IconButton(onClick = { confirm = true }, enabled = !state.loading && !state.busy, modifier = Modifier.testTag("delete_start")) {
        Icon(Icons.Rounded.DeleteOutline, if (state.pending) "确认上次删除" else "删除内容")
    }
    if (confirm) AlertDialog(onDismissRequest = { if (!state.busy) confirm = false },
        title = { Text(if (state.pending) "确认上次删除？" else "删除这条内容？") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("这条内容、原文、检索索引和来源卡片将被移除，当日总结会重新整理。已有对话文字保留，云媒体在后台清理。删除后无法恢复。")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("delete_error")) }
                if (state.busy) LinearProgressIndicator()
                if (state.storageError) TextButton(onClick = model::load) { Text("重试读取") }
            }
        },
        confirmButton = { TextButton(onClick = model::delete, enabled = !state.busy && !state.storageError && !state.loading,
            modifier = Modifier.testTag("delete_accept")) { Text(if (state.pending) "确认上次删除" else "确认删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }, enabled = !state.busy, modifier = Modifier.testTag("delete_cancel")) { Text("取消") } })
}
