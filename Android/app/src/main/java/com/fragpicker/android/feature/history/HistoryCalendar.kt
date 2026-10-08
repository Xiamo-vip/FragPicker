package com.fragpicker.android.feature.history

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.YearMonth

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryCalendar(month: YearMonth, date: String, days: Map<String, JSONObject>, loading: Boolean,
                    onMonth: (YearMonth) -> Unit, onDate: (String) -> Unit, onToday: () -> Unit, heading: String? = null) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var summaryOnly by rememberSaveable { mutableStateOf(false) }
    val selectDate by rememberUpdatedState(onDate)
    LaunchedEffect(summaryOnly, month, days, loading, date) {
        if (summaryOnly && !loading && days[date]?.optBoolean("hasSummary") != true) {
            days.entries.filter { it.key.startsWith("$month-") }.sortedByDescending { it.key }
                .firstOrNull { it.value.optBoolean("hasSummary") }?.let { selectDate(it.key) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
        val cardWidth = maxWidth
        if (heading != null && !expanded) Box(Modifier.fillMaxWidth().padding(end = 72.dp).heightIn(min = 56.dp), contentAlignment = Alignment.CenterStart) {
            Text(heading, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("history_heading"))
        }
        val width by animateDpAsState(if (expanded) cardWidth else 56.dp, tween(320, easing = FastOutSlowInEasing), label = "calendar_width")
        val radius by animateDpAsState(if (expanded) 24.dp else 28.dp, tween(320), label = "calendar_radius")
        val shape = RoundedCornerShape(radius)
        Surface(Modifier.width(width).clip(shape).animateContentSize(tween(300), alignment = Alignment.TopEnd).testTag("history_calendar"),
            shape = shape, color = if (expanded) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 2.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                    if (heading != null && expanded) Text(heading, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(start = 16.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(56.dp).testTag("calendar_toggle")
                    .semantics { stateDescription = if (expanded) "日历已展开" else "日历已收起" }) {
                        Icon(Icons.Rounded.CalendarMonth, if (expanded) "收起日历" else "展开日历", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                AnimatedVisibility(expanded, enter = expandVertically(expandFrom = Alignment.Top, animationSpec = tween(300)) + fadeIn(tween(220, 90)),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(250)) + fadeOut(tween(150))) {
                    Column(Modifier.requiredWidth(cardWidth).padding(horizontal = 10.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            IconButton(enabled = month.year > 1000 || month.monthValue > 1, onClick = { onMonth(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "上个月") }
                            Text("${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleMedium)
                            IconButton(enabled = month.year < 9999 || month.monthValue < 12, onClick = { onMonth(month.plusMonths(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "下个月") }
                        }
                        FilterChip(selected = summaryOnly, onClick = { summaryOnly = !summaryOnly }, label = { Text("只看有总结") },
                            leadingIcon = if (summaryOnly) { { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) } } else null,
                            modifier = Modifier.align(Alignment.CenterHorizontally).testTag("calendar_summary_filter"))
                        Row { listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                            Box(Modifier.weight(1f).height(32.dp), contentAlignment = Alignment.Center) { Text(day, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        } }
                        val offset = month.atDay(1).dayOfWeek.value - 1
                        repeat((offset + month.lengthOfMonth() + 6) / 7) { week ->
                            Row(Modifier.fillMaxWidth()) {
                                repeat(7) { weekday ->
                                    val number = week * 7 + weekday - offset + 1
                                    if (number in 1..month.lengthOfMonth()) {
                                        val day = month.atDay(number).toString()
                                        CalendarDate(number, day, days[day], loading, day == date, summaryOnly, { onDate(day) }, Modifier.weight(1f))
                                    } else Spacer(Modifier.weight(1f).height(52.dp))
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                            Legend("已有总结", MaterialTheme.colorScheme.primary, Icons.Rounded.CheckCircle)
                            Legend("待补齐", MaterialTheme.colorScheme.tertiary, Icons.Rounded.Schedule)
                            Legend("尚无总结", MaterialTheme.colorScheme.onSurfaceVariant, Icons.Rounded.RadioButtonUnchecked)
                            Legend("无投喂", MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f), Icons.Rounded.HorizontalRule)
                        }
                        if (summaryOnly && !loading && days.values.none { it.optBoolean("hasSummary") }) Text("本月还没有可读总结。", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { summaryOnly = false; onToday() }, modifier = Modifier.align(Alignment.End)) { Text("回到今天") }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDate(number: Int, day: String, data: JSONObject?, loading: Boolean, selected: Boolean, summaryOnly: Boolean,
                         onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val count = data?.optLong("total") ?: 0
    val hasSummary = data?.optBoolean("hasSummary") == true
    val outdated = hasSummary && data?.optBoolean("summaryOutdated") == true
    val label = when { loading || data == null -> "状态未加载"; hasSummary && outdated -> "待补齐"; hasSummary -> "已有总结";
        count == 0L -> "无投喂"; !data.has("hasSummary") -> "总结状态未知"; else -> "尚无总结" }
    val foreground = when { selected -> colors.onPrimary; outdated -> colors.onTertiaryContainer; hasSummary -> colors.onSecondaryContainer;
        count > 0 -> colors.onSurfaceVariant; else -> colors.onSurfaceVariant.copy(alpha = .55f) }
    val background = when { selected -> colors.primary; outdated -> colors.tertiaryContainer; hasSummary -> colors.secondaryContainer;
        count > 0 -> colors.surfaceContainerHigh; else -> Color.Transparent }
    TextButton(onClick = onClick, enabled = !summaryOnly || hasSummary, shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = background, contentColor = foreground), contentPadding = PaddingValues(0.dp),
        modifier = modifier.heightIn(min = 52.dp).testTag("day_$day").semantics {
            contentDescription = "$day，${count}条投喂，$label"; stateDescription = label; this.selected = selected
        }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(number.toString(), style = MaterialTheme.typography.bodyMedium)
            Icon(when { outdated -> Icons.Rounded.Schedule; hasSummary -> Icons.Rounded.CheckCircle; count > 0 -> Icons.Rounded.RadioButtonUnchecked;
                else -> Icons.Rounded.HorizontalRule }, null, Modifier.size(10.dp))
        }
    }
}

@Composable
private fun Legend(text: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, Modifier.size(12.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
