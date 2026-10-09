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

/** About 2.5–3 times the original density, capped for large windows. Area is in dp². */
internal fun emberCount(areaDp: Float): Int = (areaDp / 4_500f).toInt().coerceIn(64, 160)

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
            val count = emberCount(size.width * size.height / (density * density))
            val particles = List(count) {
                val lifetime = 9f + random.nextFloat() * 13f
                EmberSlot(random.nextLong(), random.nextFloat() * lifetime, lifetime)
            }
            onDrawBehind {
                drawRect(colors.background)
                val seconds = animation.seconds.floatValue
                particles.forEach { slot ->
                    val age = seconds + slot.offset
                    slot.respawn((age / slot.lifetime).toLong())
                    val progress = (age % slot.lifetime) / slot.lifetime
                    val wave = sin(progress * 4f * PI.toFloat() + slot.phase)
                    val turbulence = sin(progress * 9f * PI.toFloat() + slot.phase * 1.7f) * .3f
                    val x = slot.x * size.width + (wave + turbulence) * slot.drift * density
                    val travel = progress * .75f + progress * progress * .25f
                    val y = size.height + 16f * density - travel * (size.height + 32f * density)
                    val radius = slot.radius * density * (1f - progress * .45f)
                    val flicker = .78f + .22f * sin(seconds * slot.flickerFrequency + slot.phase)
                    val alpha = sin(progress * PI.toFloat()).coerceAtLeast(0f) * slot.opacity * flicker
                    val warm = if (slot.golden) Color(0xFFFFC978) else Color(0xFFFF794B)
                    val center = Offset(x, y)
                    // Soft concentric glow, not a background wash or flame silhouette.
                    drawCircle(warm.copy(alpha = alpha * .045f), radius * 4f, center)
                    drawCircle(warm.copy(alpha = alpha * .16f), radius * 2.2f, center)
                    if (slot.trail) {
                        val tailX = x - (wave + turbulence) * radius * 1.6f
                        val tailY = y + slot.tailLength * density
                        val middle = Offset((x + tailX) * .5f, (y + tailY) * .5f)
                        drawLine(warm.copy(alpha = alpha * .4f), center, middle, radius * .75f, StrokeCap.Round)
                        drawLine(warm.copy(alpha = alpha * .16f), middle, Offset(tailX, tailY), radius * .4f, StrokeCap.Round)
                    }
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
    var tailLength = 0f; var flickerFrequency = 0f
    var golden = false; var trail = false
    fun respawn(generation: Long) {
        if (generation == cycle) return
        cycle = generation
        val random = Random(seed xor (generation * -7046029254386353131L))
        x = .03f + random.nextFloat() * .94f
        radius = .6f + random.nextFloat() * 1.2f
        drift = 10f + random.nextFloat() * 32f
        phase = random.nextFloat() * 2f * PI.toFloat()
        opacity = .4f + random.nextFloat() * .35f
        tailLength = 3f + random.nextFloat() * 6f
        flickerFrequency = 2f + random.nextFloat() * 4f
        golden = random.nextBoolean(); trail = random.nextFloat() < .65f
    }
}
