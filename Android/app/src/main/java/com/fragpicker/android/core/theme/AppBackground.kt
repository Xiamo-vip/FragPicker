package com.fragpicker.android.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

val LocalDarkTheme = staticCompositionLocalOf { false }

/** Cached, static stars keep the background quiet and avoid a perpetual animation loop. */
@Composable
fun Modifier.appBackground(): Modifier {
    val dark = LocalDarkTheme.current
    val colors = MaterialTheme.colorScheme
    return drawWithCache {
        if (dark) {
            val random = Random(20261007)
            val count = (size.width * size.height / (9000f * density * density)).toInt().coerceIn(24, 180)
            val stars = List(count) {
                Triple(Offset(random.nextFloat() * size.width, random.nextFloat() * size.height),
                    (0.45f + random.nextFloat() * 0.8f) * density, 0.12f + random.nextFloat() * 0.34f)
            }
            onDrawBehind {
                drawRect(colors.background)
                stars.forEach { (position, radius, alpha) ->
                    drawCircle(Color.White.copy(alpha = alpha), radius, position)
                }
            }
        } else {
            val brush = Brush.verticalGradient(listOf(colors.surface, colors.primaryContainer.copy(alpha = .5f), colors.surface))
            onDrawBehind { drawRect(brush) }
        }
    }
}
