package com.fragpicker.android.core.theme

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

val LocalDarkTheme = staticCompositionLocalOf { false }
internal class FlameAnimation(val seconds: MutableFloatState, val seed: Long)
internal val LocalFlameAnimation = staticCompositionLocalOf<FlameAnimation?> { null }

@Composable
internal fun rememberFlameAnimation(active: Boolean): FlameAnimation {
    val animation = remember { FlameAnimation(mutableFloatStateOf(0f), Random.nextLong()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(active, lifecycle) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = 0L
            while (currentCoroutineContext().isActive) {
                val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
                if (scale <= 0f) { previous = 0L; delay(250); continue }
                withInfiniteAnimationFrameNanos { now ->
                    if (previous != 0L) animation.seconds.floatValue +=
                        ((now - previous) / 1_000_000_000f).coerceIn(0f, .1f) / scale
                    previous = now
                }
            }
        }
    }
    return animation
}

/** One shared draw-phase clock; each slot respawns with fresh random geometry. */
@Composable
fun Modifier.appBackground(): Modifier {
    val dark = LocalDarkTheme.current
    val animation = LocalFlameAnimation.current ?: rememberFlameAnimation(dark)
    val colors = MaterialTheme.colorScheme
    return drawWithCache {
        if (dark) {
            val random = Random(animation.seed)
            val count = (size.width * size.height / (24_000f * density * density)).toInt().coerceIn(14, 42)
            val particles = List(count) { FlameSlot(random.nextLong(), random.nextFloat() * 30f, 18f + random.nextFloat() * 20f) }
            val flame = Path().apply {
                moveTo(0f, -1.7f)
                cubicTo(.2f, -.55f, 1.2f, -.25f, .85f, .65f)
                cubicTo(.5f, 1.35f, -.65f, 1.2f, -.85f, .55f)
                cubicTo(-1.1f, -.2f, -.35f, -.45f, 0f, -1.7f)
                close()
            }
            onDrawBehind {
                drawRect(colors.background)
                val seconds = animation.seconds.floatValue
                particles.forEach { slot ->
                    val age = seconds + slot.offset
                    slot.respawn((age / slot.lifetime).toLong())
                    val progress = (age % slot.lifetime) / slot.lifetime
                    val wave = sin(progress * 2f * PI.toFloat() + slot.phase)
                    val x = slot.x * size.width + wave * slot.drift * density
                    val y = size.height + 30f * density - progress * (size.height + 60f * density)
                    val radius = slot.radius * density
                    val alpha = sin(progress * PI.toFloat()).coerceAtLeast(0f) * slot.opacity
                    drawCircle(Color(0xFFFFAC64).copy(alpha = alpha * .12f), radius * 2.4f, Offset(x, y))
                    withTransform({ translate(x, y); scale(radius * (.9f + .1f * wave), radius, Offset.Zero) }) {
                        drawPath(flame, Color(0xFFFF935C).copy(alpha = alpha))
                        withTransform({ translate(0f, .35f); scale(.45f, .55f, Offset.Zero) }) {
                            drawPath(flame, Color(0xFFFFDF9E).copy(alpha = alpha * 1.5f))
                        }
                    }
                }
            }
        } else {
            val brush = Brush.verticalGradient(listOf(colors.surface, colors.primaryContainer.copy(alpha = .5f), colors.surface))
            onDrawBehind { drawRect(brush) }
        }
    }
}

private class FlameSlot(val seed: Long, val offset: Float, val lifetime: Float) {
    private var cycle = Long.MIN_VALUE
    var x = 0f; var radius = 0f; var drift = 0f; var phase = 0f; var opacity = 0f
    fun respawn(generation: Long) {
        if (generation == cycle) return
        cycle = generation
        val random = Random(seed xor (generation * -7046029254386353131L))
        x = .03f + random.nextFloat() * .94f
        radius = 2.5f + random.nextFloat() * 3.5f
        drift = 8f + random.nextFloat() * 22f
        phase = random.nextFloat() * 2f * PI.toFloat()
        opacity = .12f + random.nextFloat() * .16f
    }
}
