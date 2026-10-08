package com.fragpicker.android.core.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

internal class ThemeRevealAction(val busy: Boolean, val change: (ThemeMode, Offset, (ThemeMode) -> Unit) -> Unit)
internal val LocalThemeReveal = staticCompositionLocalOf<ThemeRevealAction?> { null }

/** A single live page stays mounted beneath a temporary snapshot of the previous appearance. */
@Composable
internal fun ThemeRevealHost(actualDark: Boolean, systemDark: Boolean, content: @Composable (Boolean, Boolean) -> Unit) {
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var shownDark by remember { mutableStateOf(actualDark) }
    var busy by remember { mutableStateOf(false) }
    var previous by remember { mutableStateOf<ImageBitmap?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var expanding by remember { mutableStateOf(true) }
    val currentSystemDark by rememberUpdatedState(systemDark)
    LaunchedEffect(actualDark) { shownDark = actualDark }
    val barsDark by remember { derivedStateOf {
        if (previous != null && (if (expanding) progress.value < .5f else progress.value > .5f)) !shownDark else shownDark
    } }
    val action = ThemeRevealAction(busy) { mode, anchor, commit ->
        if (!busy) {
            val nextDark = when (mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> currentSystemDark }
            if (nextDark == shownDark) commit(mode)
            else {
                busy = true
                scope.launch {
                    try {
                        val animate = rootSize.width > 0 && rootSize.height > 0 &&
                            (currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f) > 0f
                        val snapshot = if (animate) try { layer.toImageBitmap() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: RuntimeException) { null } else null
                        origin = (anchor - rootOrigin).let { Offset(it.x.coerceIn(0f, rootSize.width.toFloat()), it.y.coerceIn(0f, rootSize.height.toFloat())) }
                        expanding = nextDark
                        progress.snapTo(if (nextDark) 0f else 1f)
                        previous = snapshot
                        shownDark = nextDark
                        commit(mode)
                        if (snapshot != null) {
                            withFrameNanos { }
                            progress.animateTo(if (nextDark) 1f else 0f, tween(520, easing = FastOutSlowInEasing))
                        }
                    } finally { previous = null; busy = false }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().testTag("theme_host").onGloballyPositioned { rootOrigin = it.positionInWindow(); rootSize = it.size }) {
        Box(Modifier.matchParentSize().drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }) { CompositionLocalProvider(LocalThemeReveal provides action) { content(shownDark, barsDark) } }
        previous?.let { image ->
            Canvas(Modifier.matchParentSize().testTag("theme_reveal").semantics {
                stateDescription = if (expanding) "深色主题圆形展开" else "深色主题圆形收回"
                progressBarRangeInfo = ProgressBarRangeInfo(progress.value, 0f..1f)
            }.pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            }) {
                val extent = hypot(max(origin.x, size.width - origin.x), max(origin.y, size.height - origin.y)) + 2f
                val radius = extent * progress.value
                val mask = Path().apply {
                    if (expanding) { fillType = PathFillType.EvenOdd; addRect(Rect(Offset.Zero, size)) }
                    if (radius > 0f) addOval(Rect(origin.x - radius, origin.y - radius, origin.x + radius, origin.y + radius))
                }
                clipPath(mask) { drawImage(image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())) }
            }
        }
    }
}
