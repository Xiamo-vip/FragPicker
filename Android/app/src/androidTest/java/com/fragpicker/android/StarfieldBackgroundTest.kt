package com.fragpicker.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.fragpicker.android.core.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StarfieldBackgroundTest {
    @get:Rule val compose = createComposeRule()
    @Test fun darkBackgroundIsSolidWithSparseStarsAndFollowsAppTheme() {
        val mode = mutableStateOf(ThemeMode.DARK)
        compose.setContent { FragmentsPickerTheme(mode.value) {
            Box(Modifier.fillMaxSize().appBackground().testTag("background"))
        } }
        val dark = compose.onNodeWithTag("background").captureToImage().toPixelMap()
        var base = 0; var stars = 0
        for (y in 0 until dark.height step 2) for (x in 0 until dark.width step 2) {
            val pixel = dark[x, y]
            if (kotlin.math.abs(pixel.red - 20f / 255) < .005f &&
                kotlin.math.abs(pixel.green - 18f / 255) < .005f &&
                kotlin.math.abs(pixel.blue - 24f / 255) < .005f) base++ else stars++
        }
        assertTrue("Most of the dark canvas must remain its solid theme color", base > stars * 50)
        assertTrue("Stars must actually render", stars > 0)
        compose.runOnIdle { mode.value = ThemeMode.LIGHT }
        val light = compose.onNodeWithTag("background").captureToImage().toPixelMap()
        assertTrue(light[light.width / 2, light.height / 2].red > .8f)
        compose.runOnIdle { mode.value = ThemeMode.DARK }
        val restored = compose.onNodeWithTag("background").captureToImage().toPixelMap()
        for (y in 0 until dark.height step 17) for (x in 0 until dark.width step 17)
            assertEquals("Stars stay stable across theme changes", dark[x, y], restored[x, y])
    }
}
