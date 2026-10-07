package com.fragpicker.android.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fragpicker.android.core.network.*
import org.json.JSONObject

@Composable
fun FragmentCard(api: JsonApi, item: JSONObject, onOpen: (Long) -> Unit) {
    val id = item.getLong("fragmentId")
    ElevatedCard(onClick = { onOpen(id) }, modifier = Modifier.fillMaxWidth().testTag("fragment_$id"), shape = MaterialTheme.shapes.extraLarge) {
        SignedCover(api, id, item.optionalString("coverMediaPath") != null, Modifier.fillMaxWidth().height(148.dp))
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.optionalString("title") ?: "投喂记录", style = MaterialTheme.typography.titleMedium)
            Text("${item.optionalString("author") ?: item.optionalString("sourceHost") ?: "视频资料"} · ${item.optString("businessDate")}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.optionalString("summary")?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                ?: Text("${statusLabel(item.optionalString("status"))} · 整理完成后显示摘要", color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.optJSONArray("categories")?.strings()?.takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · ") { category -> categoryLabel(category) }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (item.optBoolean("summaryTruncated")) Text("摘要预览，点击阅读完整内容", style = MaterialTheme.typography.labelSmall)
            if (item.optionalString("status") == "FAILED") Text("处理未完成：${item.optionalString("errorCode") ?: "UNKNOWN"}", color = MaterialTheme.colorScheme.error)
            Text("查看内容与视频 →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

fun statusLabel(status: String?) = when (status) {
    "READY" -> "已整理"; "FAILED" -> "处理失败"; "PENDING", "QUEUED" -> "等待解析"
    "PARSED", "MEDIA_SAVING" -> "保存媒体"; "TRANSCRIBING", "MEDIA_STORED" -> "转写中"
    "TRANSCRIBED", "ENRICHING", "ENRICHED", "INDEXING" -> "整理知识"; else -> "处理中"
}
