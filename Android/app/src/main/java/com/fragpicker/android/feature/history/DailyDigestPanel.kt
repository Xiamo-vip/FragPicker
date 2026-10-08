package com.fragpicker.android.feature.history

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.fragpicker.android.core.network.*
import com.fragpicker.android.core.ui.DailyDigestBody
import java.time.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DailyDigestPanel(state: HistoryState, sourcesVisible: Boolean, onSources: () -> Unit,
                     onRegenerate: () -> Unit, onConfirm: () -> Unit, onRefresh: () -> Unit) {
    val digest = state.digest; val result = digest?.optJSONObject("result")
    val status = digest?.optString("status")
    ElevatedCard(Modifier.fillMaxWidth().testTag("digest_card").semantics {
        stateDescription = "状态 ${status ?: "LOADING"} · 第${digest?.optLong("completedRevision") ?: 0}版"
    }, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Text("当天总结", style = MaterialTheme.typography.titleLarge)
            }
            if (state.digestLoading && digest == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status != null) Text(when (status) {
                "EMPTY" -> "这一天还没有投喂。"; "WAITING" -> "等待每日整理 · 北京时间22:00生成，次日补齐。"
                "QUEUED" -> "已排队，等待整理。"; "RUNNING" -> "正在归纳当天的知识。"
                "FAILED" -> if (digest.optionalString("errorCode") == "DIGEST_AI_UNCONFIRMED") "上次模型请求结果未确认，可手动重新整理。" else "整理未完成，可手动重新整理。"
                "STALE" -> "资料发生变化，旧总结等待更新。"; else -> "已整理"
            }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status in listOf("QUEUED", "RUNNING")) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (result != null) {
                DailyDigestBody(result)
                HorizontalDivider()
                Text("已归纳${result.optLong("sourceCount")}条 · 第${digest.optLong("completedRevision")}版", style = MaterialTheme.typography.labelMedium)
                digest.optionalString("generatedAt")?.let { timestamp ->
                    val formatted = runCatching { DateTimeFormatter.ofPattern("MM月dd日 HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.parse(timestamp)) }.getOrNull()
                    if (formatted != null) Text("生成于 $formatted", style = MaterialTheme.typography.labelSmall)
                }
                if (digest.optBoolean("outdated")) Text("有新内容待补齐，当前显示上一次总结。", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                val sources = result.optJSONArray("sources")?.length() ?: 0
                if (sources > 0) TextButton(onClick = onSources, modifier = Modifier.testTag("digest_sources_toggle")) {
                    Text(if (sourcesVisible) "收起总结来源" else "查看总结来源（$sources）")
                }
            }
            if (digest != null && digest.optLong("total") > 0 && !digest.optBoolean("canRegenerate"))
                Text("每日总结服务尚未启用，现有资料仍可逐条查看。", style = MaterialTheme.typography.labelSmall)
            state.digestMessage?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            state.digestError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.digestBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.digestPendingKey != null) Button(onClick = onConfirm, enabled = !state.digestBusy && !state.digestStorageError,
                    modifier = Modifier.testTag("digest_confirm")) { Text("确认上次提交") }
                else if (digest?.optBoolean("canRegenerate") == true) Button(onClick = onRegenerate,
                    enabled = !state.digestBusy && !state.digestLoading && !state.digestStorageError, modifier = Modifier.testTag("digest_regenerate")) {
                    Text(if (result == null) "生成当天总结" else "重新整理")
                }
                TextButton(onClick = onRefresh, enabled = !state.digestBusy, modifier = Modifier.testTag("digest_refresh")) { Text("刷新总结") }
            }
        }
    }
}
