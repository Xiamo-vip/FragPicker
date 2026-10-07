package com.fragpicker.android.core.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

enum class ProcessingVisual { WAITING, COMPLETE, FAILED }

/** A state-driven circle → pill → card. Indeterminate progress never invents a percentage. */
@Composable
fun ProcessingCard(identity: String, visual: ProcessingVisual, title: String, detail: String,
                   modifier: Modifier = Modifier, actionLabel: String? = null, action: (() -> Unit)? = null) {
    var stage by remember(identity) { mutableIntStateOf(0) }
    LaunchedEffect(identity, visual) {
        if (stage == 0) { delay(100); stage = 1 }
        if (visual != ProcessingVisual.WAITING) { delay(240); stage = 2 }
        else if (stage == 2) stage = 1
    }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width by animateDpAsState(when (stage) {
            0 -> 68.dp
            1 -> minOf(maxWidth, 300.dp)
            else -> maxWidth
        }, tween(260, delayMillis = if (stage == 2) 120 else 0), label = "pill width")
        val minimumHeight by animateDpAsState(if (stage < 2) 68.dp else 140.dp,
            tween(180), label = "card height")
        val radius by animateDpAsState(if (stage < 2) 34.dp else 24.dp, tween(220), label = "card radius")
        val failed = visual == ProcessingVisual.FAILED
        Surface(Modifier.width(width).heightIn(min = minimumHeight).animateContentSize(tween(220))
            .testTag("processing_${visual.name}"), shape = RoundedCornerShape(radius),
            color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (stage == 0) Arrangement.Center else Arrangement.spacedBy(12.dp)) {
                    Icon(when (visual) {
                        ProcessingVisual.WAITING -> Icons.Rounded.AutoAwesome
                        ProcessingVisual.COMPLETE -> Icons.Rounded.CheckCircle
                        ProcessingVisual.FAILED -> Icons.Rounded.ErrorOutline
                    }, if (stage == 0) title else null, Modifier.size(24.dp))
                    AnimatedVisibility(stage >= 1, enter = fadeIn(tween(160, 120)), exit = fadeOut()) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    }
                }
                AnimatedVisibility(stage == 1, enter = fadeIn(tween(140, 220))) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (visual == ProcessingVisual.WAITING) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
                AnimatedVisibility(stage == 2, enter = fadeIn(tween(180, 220)) + expandVertically()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(detail, style = MaterialTheme.typography.bodyMedium)
                        if (actionLabel != null && action != null) TextButton(onClick = action) { Text(actionLabel) }
                    }
                }
            }
        }
    }
}
