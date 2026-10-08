package com.fragpicker.android.feature.clipboard

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fragpicker.android.core.network.JsonApi
import com.fragpicker.android.core.ui.Destination
import com.fragpicker.android.core.ui.LocalOpenDestination


@Composable
fun ClipboardRoute(api: JsonApi, focusEpoch: Long, entryEpoch: Long, explicitSharePending: Boolean) {
    val context = LocalContext.current.applicationContext
    val model: ClipboardViewModel = viewModel(key = "clipboard:${api.baseUrl}:${api.userId}",
        factory = viewModelFactory { initializer {
            ClipboardViewModel(api, ClipboardRequestStore(context.getSharedPreferences("clipboard_requests", Context.MODE_PRIVATE),
                api.baseUrl, api.userId), context.getSharedPreferences("feed_last", Context.MODE_PRIVATE))
        } })
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val view = LocalView.current
    val navigate = LocalOpenDestination.current
    val lifecycleState by lifecycle.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(model, focusEpoch, entryEpoch, state.initialized, explicitSharePending, lifecycleState) {
        if (explicitSharePending) model.suppressEntry(entryEpoch)
        if (!explicitSharePending && lifecycleState == Lifecycle.State.RESUMED && view.hasWindowFocus() && model.enter(entryEpoch)) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val description = runCatching { clipboard.primaryClipDescription }.getOrNull()
            if (description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) != true) {
                runCatching { ClipboardLinks.fromClip(clipboard.primaryClip) }.getOrNull()?.let(model::inspect)
            }
        }
    }
    // Window readiness may change after resume. Only actual backgrounding cancels the in-flight preview.
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            if (!lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.pausePreview()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); model.pausePreview() }
    }
    LaunchedEffect(state.receipt) {
        if (state.receipt != null) { navigate(Destination.FEED); model.acknowledgeReceipt() }
    }
    if (state.visible && !explicitSharePending) AlertDialog(
        modifier = Modifier.testTag("clipboard_dialog"),
        onDismissRequest = model::dismiss,
        icon = { Icon(Icons.Rounded.AddLink, null) },
        title = { Text(if (state.blocked) "投喂记录暂不可用" else if (state.pending != null) "确认上次投喂" else "投喂剪切板中的视频？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.preview?.let {
                    Text(it.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("clipboard_title"))
                    Text(listOf(it.author, it.host).filter(String::isNotBlank).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (state.pending != null) "上次提交的结果还未确认。再次确认会使用原请求；稍后确认不会撤销已经提交的任务。"
                        else "已确认视频资源可以读取。开始后会保存视频，并转写整理到你的知识库。",
                        style = MaterialTheme.typography.bodyMedium)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("clipboard_error")) }
                if (state.sending) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在确认投喂…")
                }
            }
        },
        confirmButton = {
            if (!state.blocked) TextButton(model::submit, enabled = !state.sending, modifier = Modifier.testTag("clipboard_confirm")) {
                Text(if (state.pending != null) "确认上次提交" else "开始投喂")
            }
        },
        dismissButton = { TextButton(model::dismiss, enabled = !state.sending, modifier = Modifier.testTag("clipboard_dismiss")) {
            Text(if (state.blocked) "关闭" else if (state.pending != null) "稍后确认" else "暂不投喂")
        } },
    )
}