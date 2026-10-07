package com.fragpicker.android.feature.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
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
import java.time.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryRoute(api: JsonApi, onOpen: (Long) -> Unit) {
    val context = LocalContext.current.applicationContext
    val model: HistoryViewModel = viewModel(factory = viewModelFactory { initializer {
        HistoryViewModel(api, DigestRequestStore(context.getSharedPreferences("digest_requests", android.content.Context.MODE_PRIVATE), api.baseUrl, api.userId))
    } })
    val state by model.state.collectAsStateWithLifecycle()
    val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
    var date by rememberSaveable { mutableStateOf(today.toString()) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var sourcesVisible by rememberSaveable(date) { mutableStateOf(false) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    val month = YearMonth.parse(monthText)
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle, monthText) { lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        model.calendar(monthText); awaitCancellation()
    } }
    LaunchedEffect(lifecycle, date) { category = null; lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        model.select(date); try { awaitCancellation() } finally { model.pauseSummary(date) }
    } }
    val selectedDay = state.days[date]
    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent, topBar = { TopAppBar(title = { Text("回顾") }, actions = {
        IconButton(onClick = { model.calendar(monthText); model.select(date) }, enabled = !state.loading && !state.calendarLoading) { Icon(Icons.Rounded.Refresh, "刷新回顾") }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(bottom = LocalNavigationInset.current).testTag("history_list")
            .semantics { stateDescription = if (state.loading) "正在加载资料" else "已加载${state.items.size}条" },
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("让知识在日历里生长。", style = MaterialTheme.typography.headlineSmall) }
            item {
                ElevatedCard(shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            IconButton(enabled = month.year > 1000 || month.monthValue > 1, onClick = { monthText = month.minusMonths(1).toString(); date = month.minusMonths(1).atDay(1).toString() }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "上个月") }
                            Text("${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleMedium)
                            IconButton(enabled = month.year < 9999 || month.monthValue < 12, onClick = { monthText = month.plusMonths(1).toString(); date = month.plusMonths(1).atDay(1).toString() }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "下个月") }
                        }
                        Row { listOf("一", "二", "三", "四", "五", "六", "日").forEach { day -> Box(Modifier.weight(1f).height(36.dp), contentAlignment = Alignment.Center) { Text(day, style = MaterialTheme.typography.labelMedium) } } }
                        val offset = month.atDay(1).dayOfWeek.value - 1
                        repeat((offset + month.lengthOfMonth() + 6) / 7) { week -> Row(Modifier.fillMaxWidth()) {
                            repeat(7) { weekday ->
                                val number = week * 7 + weekday - offset + 1
                                if (number in 1..month.lengthOfMonth()) {
                                    val day = month.atDay(number).toString(); val count = state.days[day]?.optLong("total") ?: 0
                                    val selected = day == date
                                    TextButton(onClick = { date = day }, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("day_$day")
                                        .semantics { contentDescription = "$day，${count}条投喂"; this.selected = selected },
                                        contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.textButtonColors(
                                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(number.toString()); Text(if (count > 0) "•" else " ", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                } else Spacer(Modifier.weight(1f).height(52.dp))
                            }
                        } }
                        TextButton(onClick = { date = today.toString(); monthText = YearMonth.from(today).toString() }, modifier = Modifier.align(Alignment.End)) { Text("回到今天") }
                        if (state.calendarLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            state.calendarError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                Text(date, style = MaterialTheme.typography.titleLarge)
                Text("本日共${selectedDay?.optLong("total") ?: 0}条 · 已整理${selectedDay?.optLong("ready") ?: 0} · 处理中${selectedDay?.optLong("processing") ?: 0} · 失败${selectedDay?.optLong("failed") ?: 0}",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("history_counts"))
            }
            item(key = "daily_summary") {
                DailyDigestPanel(state, sourcesVisible, { sourcesVisible = !sourcesVisible }, { confirmRegenerate = true }, model::regenerate, model::summary)
            }
            if (sourcesVisible) items(state.digest?.optJSONObject("result")?.optJSONArray("sources")?.objects().orEmpty(), key = { "digest_source_${it.getLong("fragmentId")}" }) {
                FragmentCard(api, it, onOpen, tagPrefix = "digest_source")
            }
            val categories = state.items.flatMap { it.optJSONArray("categories")?.strings().orEmpty() }.distinct()
            if (categories.isNotEmpty()) item {
                Text("筛选已加载资料", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = category == null, onClick = { category = null }, label = { Text("全部") })
                    categories.forEach { label -> FilterChip(selected = category == label, onClick = { category = label }, label = { Text(categoryLabel(label)) }) }
                }
            }
            items(state.items.filter { category == null || category in it.optJSONArray("categories")?.strings().orEmpty() }, key = { it.getLong("fragmentId") }) {
                FragmentCard(api, it, onOpen)
            }
            if (state.items.isEmpty() && !state.loading && state.error == null) item { Text("这一天还没有投喂，给未来的自己留一点灵感。", modifier = Modifier.testTag("history_empty")) }
            item {
                Text("已加载${state.items.size}条", style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("history_loaded"))
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (state.before != null) OutlinedButton(onClick = { model.page() }, modifier = Modifier.testTag("history_more")) { Text("加载更多") }
            }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { model.select(date) }) { Text("重新加载当天资料") } } }
        }
    }
    if (confirmRegenerate) AlertDialog(onDismissRequest = { confirmRegenerate = false }, title = { Text("整理 $date") },
        text = { Text("整理当天已完成的资料，可能产生模型费用。处理中和失败的资料不会作为总结依据。") },
        confirmButton = { TextButton(onClick = { confirmRegenerate = false; model.regenerate() }, modifier = Modifier.testTag("digest_accept")) { Text("确认整理") } },
        dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text("取消") } })
}
