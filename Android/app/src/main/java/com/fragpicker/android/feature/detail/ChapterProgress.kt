package com.fragpicker.android.feature.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.fragpicker.android.core.network.timestamp

data class VideoChapter(val ordinal: Int, val startMs: Long, val endMs: Long, val text: String)

internal fun timelineChapters(chapters: List<VideoChapter>, durationMs: Long) = chapters
    .filter { it.startMs >= 0 && it.startMs < durationMs && it.endMs >= it.startMs }
    .sortedWith(compareBy({ it.startMs }, { it.ordinal })).distinctBy { it.startMs }

@Composable
fun ChapterProgress(positionMs: Long, durationMs: Long, chapters: List<VideoChapter>, enabled: Boolean, onSeek: (Long) -> Unit) {
    val duration = durationMs.coerceAtLeast(1)
    val timeline = remember(chapters, duration) { timelineChapters(chapters, duration) }
    val stops = remember(timeline, duration) { (listOf(0L) + timeline.map { it.startMs } + duration).distinct().sorted() }
    val position = positionMs.coerceIn(0, duration)
    val colors = MaterialTheme.colorScheme
    val seek by rememberUpdatedState(onSeek)
    Canvas(Modifier.fillMaxWidth().height(48.dp).testTag("video_seek")
        .semantics {
            contentDescription = "分段播放进度"
            stateDescription = "${timestamp(position)} / ${timestamp(duration)}，${timeline.size}个章节"
            progressBarRangeInfo = ProgressBarRangeInfo(position.toFloat(), 0f..duration.toFloat())
            if (enabled) setProgress { seek(it.toLong().coerceIn(0, duration)); true } else disabled()
        }
        .pointerInput(enabled, duration) {
            if (enabled) detectTapGestures { seek((it.x / size.width * duration).toLong().coerceIn(0, duration)) }
        }
        .pointerInput(enabled, duration) {
            if (enabled) detectDragGestures(onDragStart = { seek((it.x / size.width * duration).toLong().coerceIn(0, duration)) }) { change, _ ->
                change.consume(); seek((change.position.x / size.width * duration).toLong().coerceIn(0, duration))
            }
        }) {
        val gap = minOf(3.dp.toPx(), size.width / (stops.size * 3f))
        val y = size.height / 2
        val stroke = 5.dp.toPx()
        stops.zipWithNext().forEach { (from, to) ->
            val left = from.toFloat() / duration * size.width + gap / 2
            val right = to.toFloat() / duration * size.width - gap / 2
            if (right > left) {
                drawLine(colors.surfaceVariant, Offset(left, y), Offset(right, y), stroke, StrokeCap.Round)
                if (position > from) drawLine(colors.primary.copy(alpha = if (enabled) 1f else .5f), Offset(left, y),
                    Offset((minOf(position, to).toFloat() / duration * size.width - gap / 2).coerceAtLeast(left), y), stroke, StrokeCap.Round)
            }
        }
        drawCircle(colors.primary, 6.dp.toPx(), Offset((position.toFloat() / duration * size.width).coerceIn(6.dp.toPx(), (size.width - 6.dp.toPx()).coerceAtLeast(6.dp.toPx())), y))
    }
}
