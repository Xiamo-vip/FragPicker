package com.fragpicker.android.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fragpicker.android.core.network.*
import org.json.JSONObject

/** Separates prose, takeaways and topic metadata in both today and history views. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DailyDigestBody(result: JSONObject, summaryTag: String = "digest_summary", compact: Boolean = false) {
    val summary = result.getString("summary")
    var proseExpanded by rememberSaveable(summary) { mutableStateOf(false) }
    var pointsExpanded by rememberSaveable(summary) { mutableStateOf(false) }
    var labelsExpanded by rememberSaveable(summary) { mutableStateOf(false) }
    var proseOverflow by remember(summary) { mutableStateOf(false) }
    val points = result.optJSONArray("points")?.objects().orEmpty()
    val categories = result.optJSONArray("categories")?.strings().orEmpty().map(::categoryLabel)
    val keywords = result.optJSONArray("keywords")?.strings().orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Surface(Modifier.fillMaxWidth().testTag("${summaryTag}_prose"), color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("一天的收获", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SelectionContainer { Text(summary, style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (proseExpanded) Int.MAX_VALUE else if (compact) 6 else 8, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!proseExpanded) proseOverflow = it.hasVisualOverflow }, modifier = Modifier.testTag(summaryTag)) }
                if (!compact && (proseOverflow || proseExpanded)) TextButton(onClick = { proseExpanded = !proseExpanded },
                    modifier = Modifier.testTag("${summaryTag}_expand"), contentPadding = PaddingValues(0.dp)) {
                    Text(if (proseExpanded) "收起正文" else "阅读全文")
                }
            }
        }
        if (points.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("${summaryTag}_takeaways")) {
                Text("带走的要点", style = MaterialTheme.typography.titleMedium)
                val visible = if (pointsExpanded) points else points.take(if (compact) 2 else 3)
                visible.forEachIndexed { index, point ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("%02d".format(index + 1), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        SelectionContainer(modifier = Modifier.weight(1f)) { Text(point.getString("text"), style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (pointsExpanded) Int.MAX_VALUE else if (compact) 2 else 3, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (!compact) TextButton(onClick = { pointsExpanded = !pointsExpanded },
                    modifier = Modifier.testTag("${summaryTag}_points_expand"), contentPadding = PaddingValues(0.dp)) {
                    Text(if (pointsExpanded) "收起要点" else "查看完整${points.size}条要点")
                }
            }
        }
        val labels = (categories + keywords).distinct()
        if (labels.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("${summaryTag}_topics")) {
                if (!compact) { HorizontalDivider(); Text("主题与线索", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (if (labelsExpanded) labels else labels.take(6)).forEach { label ->
                        Surface(shape = MaterialTheme.shapes.small, color = if (label in categories) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (!compact && labels.size > 6) TextButton(onClick = { labelsExpanded = !labelsExpanded }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (labelsExpanded) "收起线索" else "查看全部线索")
                }
            }
        }
    }
}
