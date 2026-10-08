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
import androidx.compose.ui.graphics.StrokeCap
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
internal class EmberAnimation(val seconds: MutableFloatState, val seed: Long)
internal val LocalEmberAnimation = staticCompositionLocalOf<EmberAnimation?> { null }

@Composable
internal fun rememberEmberAnimation(active: Boolean): EmberAnimation {
    val animation = remember { EmberAnimation(mutableFloatStateOf(0f), Random.nextLong()) }
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
    val animation = LocalEmberAnimation.current ?: rememberEmberAnimation(dark)
    val colors = MaterialTheme.colorScheme
    return drawWithCache {
        if (dark) {
            val random = Random(animation.seed)
            val count = (size.width * size.height / (12_000f * density * density)).toInt().coerceIn(24, 64)
            val particles = List(count) { EmberSlot(random.nextLong(), random.nextFloat() * 24f, 12f + random.nextFloat() * 18f) }
            onDrawBehind {
                drawRect(colors.background)
                val seconds = animation.seconds.floatValue
                particles.forEach { slot ->
                    val age = seconds + slot.offset
                    slot.respawn((age / slot.lifetime).toLong())
                    val progress = (age % slot.lifetime) / slot.lifetime
                    val wave = sin(progress * 4f * PI.toFloat() + slot.phase)
                    val turbulence = sin(progress * 9f * PI.toFloat() + slot.phase * 1.7f) * .22f
                    val x = slot.x * size.width + (wave + turbulence) * slot.drift * density
                    val travel = progress * .75f + progress * progress * .25f
                    val y = size.height + 16f * density - travel * (size.height + 32f * density)
                    val radius = slot.radius * density * (1f - progress * .45f)
                    val flicker = .8f + .2f * sin(seconds * 3.1f + slot.phase)
                    val alpha = sin(progress * PI.toFloat()).coerceAtLeast(0f) * slot.opacity * flicker
                    val warm = if (slot.golden) Color(0xFFFFC978) else Color(0xFFFF794B)
                    val center = Offset(x, y)
                    drawCircle(warm.copy(alpha = alpha * .09f), radius * 3.5f, center)
                    // A short fading trail follows the ember; there are no flame silhouettes.
                    if (slot.trail) drawLine(warm.copy(alpha = alpha * .3f), center,
                        Offset(x - wave * radius, y + radius * 4f), radius * .8f, StrokeCap.Round)
                    drawCircle(warm.copy(alpha = alpha), radius, center)
                    drawCircle(Color(0xFFFFE9BC).copy(alpha = alpha * .8f), radius * .4f, center)
                }
            }
        } else {
            val brush = Brush.verticalGradient(listOf(colors.surface, colors.primaryContainer.copy(alpha = .5f), colors.surface))
            onDrawBehind { drawRect(brush) }
        }
    }
}

private class EmberSlot(val seed: Long, val offset: Float, val lifetime: Float) {
    private var cycle = Long.MIN_VALUE
    var x = 0f; var radius = 0f; var drift = 0f; var phase = 0f; var opacity = 0f
    var golden = false; var trail = false
    fun respawn(generation: Long) {
        if (generation == cycle) return
        cycle = generation
        val random = Random(seed xor (generation * -7046029254386353131L))
        x = .03f + random.nextFloat() * .94f
        radius = .55f + random.nextFloat() * 1.05f
        drift = 8f + random.nextFloat() * 28f
        phase = random.nextFloat() * 2f * PI.toFloat()
        opacity = .25f + random.nextFloat() * .3f
        golden = random.nextBoolean(); trail = random.nextFloat() < .3f
    }
}
