package com.fragpicker.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.fragpicker.android.core.ui.liquid.InteractiveHighlight
import com.fragpicker.android.core.ui.liquid.shapes.Capsule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LiquidHighlightClipTest {
    @get:Rule val compose = createComposeRule()
    @Test fun heldHighlightStaysInsideContinuousCapsule() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val highlight = remember(scope) { InteractiveHighlight(scope) }
            Box(Modifier.size(240.dp, 64.dp).testTag("capsule").clip(Capsule())
                .background(Color(0xFF202020)).then(highlight.modifier).then(highlight.gestureModifier))
        }
        val before = compose.onNodeWithTag("capsule").captureToImage().toPixelMap()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("capsule").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1500)
        val held = compose.onNodeWithTag("capsule").captureToImage().toPixelMap()
        for (x in 1..5) for (y in 1..5) {
            assertEquals("Highlight must not paint the left rounded corner", before[x, y], held[x, y])
            assertEquals("Highlight must not paint the right rounded corner", before[before.width-1-x, y], held[held.width-1-x, y])
        }
        assertTrue("The test must actually activate the highlight", held[held.width/2, held.height/2].red > before[before.width/2, before.height/2].red + .04f)
        compose.onNodeWithTag("capsule").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1500)
        compose.mainClock.autoAdvance = true
    }
}
