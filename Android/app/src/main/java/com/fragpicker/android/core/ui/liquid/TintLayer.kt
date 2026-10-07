package com.fragpicker.android.core.ui.liquid

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

/** Compose 1.9 equivalent of the upstream graphicsLayer(colorFilter = ...) overload. */
@Composable
internal fun Modifier.tintLayer(color: Color): Modifier {
    val layer = rememberGraphicsLayer()
    return drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        layer.colorFilter = ColorFilter.tint(color)
        drawLayer(layer)
    }
}
