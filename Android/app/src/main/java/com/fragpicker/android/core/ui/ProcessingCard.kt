package com.fragpicker.android.core.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
        val entering = stage == 0
        if (entering) { delay(100); stage = 1 }
        if (visual != ProcessingVisual.WAITING) {
            if (entering) delay(320)
            stage = 2
        }
        else if (stage == 2) stage = 1
    }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width = when (stage) {
            0 -> 68.dp
            1 -> minOf(maxWidth, 300.dp)
            else -> maxWidth
        }
        val radius by animateDpAsState(if (stage < 2) 34.dp else 24.dp, tween(320), label = "card radius")
        val titleAlpha by animateFloatAsState(if (stage >= 1) 1f else 0f,
            tween(160, 120), label = "processing title")
        val detailAlpha by animateFloatAsState(if (stage >= 1) 1f else 0f,
            tween(160, 160), label = "processing detail")
        val shape = RoundedCornerShape(radius)
        val failed = visual == ProcessingVisual.FAILED
        // Measure the destination at its final width. Only this modifier animates
        // the bounds: animating min-height and child expansion too retargeted it every frame.
        Surface(Modifier.testTag("processing_${visual.name}").clip(shape)
            .animateContentSize(tween(320), alignment = Alignment.TopStart)
            .width(width).heightIn(min = if (stage < 2) 68.dp else 140.dp), shape = shape,
            color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (stage == 0) Arrangement.Center else Arrangement.spacedBy(12.dp)) {
                    Icon(when (visual) {
                        ProcessingVisual.WAITING -> Icons.Rounded.AutoAwesome
                        ProcessingVisual.COMPLETE -> Icons.Rounded.CheckCircle
                        ProcessingVisual.FAILED -> Icons.Rounded.ErrorOutline
                    }, if (stage == 0) title else null, Modifier.size(24.dp))
                    if (stage >= 1) Text(title, Modifier.graphicsLayer { alpha = titleAlpha },
                        style = MaterialTheme.typography.titleMedium, maxLines = 2)
                }
                if (stage >= 1) Column(Modifier.graphicsLayer { alpha = detailAlpha },
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Keep a single body, not overlapping outgoing pill and incoming card bodies.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (visual == ProcessingVisual.WAITING) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(detail, style = if (stage == 2) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                            maxLines = if (stage == 2) Int.MAX_VALUE else 2)
                    }
                    if (stage == 2 && actionLabel != null && action != null)
                        TextButton(onClick = action) { Text(actionLabel) }
                }
            }
        }
    }
}
