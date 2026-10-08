package com.fragpicker.android.feature.chat

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fragpicker.android.core.network.*
import com.fragpicker.android.core.ui.*
import kotlinx.coroutines.awaitCancellation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoute(api: JsonApi, onOpen: (Long) -> Unit) {
    val context = LocalContext.current.applicationContext
    val model: ChatViewModel = viewModel(factory = viewModelFactory { initializer {
        ChatViewModel(api, PendingMessageVault(context, api.baseUrl, api.userId), context.getSharedPreferences("chat_selected", Context.MODE_PRIVATE))
    } })
    val state by model.state.collectAsStateWithLifecycle()
    var question by rememberSaveable { mutableStateOf("") }
    var showSessions by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(model, lifecycle) { lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        model.sessions(); try { awaitCancellation() } finally { model.pause() }
    } }
    DisposableEffect(model) { onDispose { model.pause() } }
    LaunchedEffect(state.pending?.key) { if (state.pending != null) question = "" }
    LaunchedEffect(state.pending?.key, state.turns.lastOrNull()?.optLong("turnId")) {
        if (list.layoutInfo.totalItemsCount > 0) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
    if (showSessions) ModalBottomSheet(onDismissRequest = { showSessions = false }) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("chat_sessions")) {
            item { Text("历史对话", style = MaterialTheme.typography.titleLarge) }
            items(state.sessions, key = { it.getLong("sessionId") }) { session ->
                FilledTonalButton(onClick = { model.choose(session.getLong("sessionId")); showSessions = false },
                    enabled = !state.busy && state.pending == null, modifier = Modifier.fillMaxWidth().testTag("session_${session.getLong("sessionId")}")) {
                    Text("${session.getString("title")} · ${session.getString("updatedAt").take(10)}")
                }
            }
            if (state.sessions.isEmpty() && !state.sessionsLoading) item { Text("还没有历史对话。") }
            item { if (state.sessionsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (state.sessionCursor != null) TextButton(onClick = { model.sessions(more = true) }) { Text("加载更多对话") } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent, topBar = { TopAppBar(title = { Text("对话") }, actions = {
        IconButton(onClick = { model.sessions(); showSessions = true }) { Icon(Icons.Rounded.History, "历史对话") }
        IconButton(enabled = !state.busy && state.pending == null, onClick = { model.choose(null); question = "" }) { Icon(Icons.Rounded.Add, "新对话") }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)) }, bottomBar = {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.imePadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp).padding(bottom = LocalNavigationInset.current),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.busy) { Text(state.phase, style = MaterialTheme.typography.labelLarge); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.pending?.takeIf { !state.busy }?.let {
                    Text("上次发送结果尚未确认。", style = MaterialTheme.typography.labelLarge)
                    Button(onClick = model::confirm, modifier = Modifier.fillMaxWidth().testTag("chat_confirm")) { Text("确认上次消息结果") }
                }
                if (state.storageError) TextButton(onClick = model::initialize) { Text("重新读取本机记录") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(question, { if (it.length <= 4000 && it.codePointCount(0, it.length) <= 2000) question = it },
                        label = { Text("想找回什么？") }, textStyle = MaterialTheme.typography.bodyMedium,
                        maxLines = 4, enabled = state.initialized && !state.busy && state.pending == null && !state.storageError,
                        modifier = Modifier.weight(1f).testTag("chat_question"))
                    FilledIconButton(enabled = state.initialized && !state.busy && state.pending == null && !state.storageError,
                        onClick = { keyboard?.hide(); model.send(question) }, modifier = Modifier.padding(top = 8.dp).testTag("chat_send")) { Icon(Icons.AutoMirrored.Rounded.Send, "发送") }
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("chat_messages"), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.turns.isEmpty() && state.pending == null) item {
                Text("和记忆聊一聊。", style = MaterialTheme.typography.headlineSmall)
                Text("描述主题、时间或作者，让 AI 从你保存的内容中寻找线索。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { question = "请帮我找之前保存的数学资源里与导数有关的内容。" }) { Text("找找导数的学习资源") }
            }
            if (state.before != null) item { TextButton(onClick = { model.history() }, enabled = !state.historyLoading) { Text("更早的消息") } }
            if (state.historyLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(state.turns, key = { it.getLong("turnId") }) { turn ->
                Column(Modifier.testTag("chat_turn_${turn.getLong("turnId")}"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                        SelectionContainer { Text(turn.getString("question"), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                    if (turn.getString("state") == "COMPLETED") {
                        MarkdownText(turn.optionalString("answer") ?: "回答已保存。", Modifier.testTag("chat_answer_${turn.getLong("turnId")}"))
                        if (turn.optBoolean("contextTruncated")) Text("本次对话仅包含部分较早上下文。", style = MaterialTheme.typography.labelSmall)
                        turn.optJSONArray("cards")?.objects()?.forEach { FragmentCard(api, it, onOpen) }
                    } else if (turn.getString("state") == "FAILED") {
                        Text(chatError(turn.optString("errorCode")), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { question = turn.getString("question") }, enabled = !state.busy && state.pending == null) { Text("再次提问") }
                    } else {
                        Text("这条消息仍在处理中。")
                        TextButton(onClick = { model.history(first = true) }, enabled = !state.historyLoading) { Text("刷新已保存状态") }
                    }
                }
            }
            state.pending?.let { pending -> item {
                if (state.turns.none { it.getLong("turnId") == pending.turnId }) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                    Text(pending.question, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
                if (state.streamText.isNotBlank()) MarkdownText(state.streamText, Modifier.testTag("chat_stream"))
            } }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("chat_error")) } }
        }
    }
}
