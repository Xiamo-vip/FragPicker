package com.fragpicker.android.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fragpicker.android.core.auth.UserProfile
import com.fragpicker.android.core.network.*
import com.fragpicker.android.core.ui.*
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeRoute(api: JsonApi, user: UserProfile, onFeed: () -> Unit, onChat: () -> Unit, onHistory: () -> Unit, onOpen: (Long) -> Unit) {
    val model: HomeViewModel = viewModel(key = "home", factory = viewModelFactory { initializer { HomeViewModel(api) } })
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, model) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (true) {
                    val date = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                    model.refresh(date.toString())
                    val wait = if ((state.digest?.optLong("processing", 0) ?: 0L) > 0L || state.digest?.optString("status") in listOf("QUEUED", "RUNNING")) 10_000L else 60_000L
                    val midnight = Duration.between(Instant.now(), date.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()).toMillis().coerceAtLeast(1)
                    delay(minOf(wait, midnight))
                }
            } finally { model.pause() }
        }
    }
    HomeScreen(api, user, state, { model.refresh() }, onFeed, onChat, onHistory, onOpen)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(api: JsonApi, user: UserProfile, state: HomeState, onRefresh: () -> Unit,
               onFeed: () -> Unit, onChat: () -> Unit, onHistory: () -> Unit, onOpen: (Long) -> Unit) {
    val digest = state.digest
    val result = digest?.optJSONObject("result")
    Scaffold(containerColor = Color.Transparent, topBar = {
        TopAppBar(title = { Text("FragmentsPicker", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
            actions = { IconButton(onClick = onRefresh, enabled = !state.loading, modifier = Modifier.testTag("home_refresh")) { Icon(Icons.Rounded.Refresh, "刷新首页") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("home_list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = LocalNavigationInset.current + 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.SIMPLIFIED_CHINESE).format(LocalDate.parse(state.date)) + " · 北京时间",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("今天，拾起了什么？", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("欢迎回来，${user.username}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.loading && digest == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                ElevatedCard(shape = MaterialTheme.shapes.extraLarge) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        HomeCount(digest?.optLong("total"), "今日投喂", "home_total")
                        HomeCount(digest?.optLong("ready"), "可以回顾", "home_ready")
                        HomeCount(digest?.optLong("processing"), "正在整理", "home_processing")
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onFeed, modifier = Modifier.weight(1f).heightIn(min = 50.dp).testTag("home_feed")) {
                        Icon(Icons.Rounded.AddLink, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("投喂新内容")
                    }
                    OutlinedButton(onClick = onChat, modifier = Modifier.weight(1f).heightIn(min = 50.dp).testTag("home_chat")) {
                        Icon(Icons.AutoMirrored.Rounded.Chat, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("问问 AI")
                    }
                }
            }
            state.digestError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("home_digest_error")) } }
            if ((digest?.optLong("failed") ?: 0L) > 0) item {
                TextButton(onClick = onHistory) { Text("${digest!!.optLong("failed")}条内容未完成，去回顾查看") }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth().testTag("home_digest"), shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                            Text("今日知识小结", style = MaterialTheme.typography.titleLarge)
                        }
                        if (result != null) {
                            DailyDigestBody(result, summaryTag = "home_summary", compact = true)
                            HorizontalDivider()
                            Text("已归纳${result.optLong("sourceCount")}条内容", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (digest.optBoolean("outdated")) Text("新内容还在补齐，这里显示最近一次总结。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text(when (digest?.optString("status")) {
                                "EMPTY" -> "今天还没有投喂。收藏一个值得记住的片段，让灵感慢慢沉淀。"
                                "QUEUED", "RUNNING" -> "正在整理今天的知识，完成后会自动更新。"
                                "FAILED" -> "今日总结暂未完成，可前往回顾重新整理。"
                                "STALE" -> "内容发生变化，今日总结等待更新。"
                                "WAITING" -> if (digest.optBoolean("canRegenerate")) "北京时间22:00自动生成，次日补齐。已完成的内容可先逐条回顾。" else "每日总结暂不可用，已完成的内容仍可回顾。"
                                else -> if (state.digestError != null) "暂时无法读取今日总结，请刷新重试。" else "正在读取今天的知识…"
                            }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onHistory, modifier = Modifier.testTag("home_history")) { Text("查看今日完整回顾") }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("最近拾起", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onHistory) { Text("全部内容") }
                }
            }
            state.itemsError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (state.items.isEmpty() && !state.loading && state.itemsError == null) item {
                Text("复制视频分享链接，或从其他应用分享到 FragPicker。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.items, key = { it.getLong("fragmentId") }) { item -> FragmentCard(api, item, onOpen, "home_fragment") }
        }
    }
}

@Composable
private fun HomeCount(count: Long?, label: String, tag: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(count?.toString() ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(tag))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
