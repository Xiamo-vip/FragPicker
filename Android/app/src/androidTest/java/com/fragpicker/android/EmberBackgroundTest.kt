package com.fragpicker.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.sp
import com.fragpicker.android.core.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EmberBackgroundTest {
    @get:Rule val compose = createComposeRule()
    @Test fun defaultTextRemainsReadableOnTheDarkCanvas() {
        compose.setContent { FragmentsPickerTheme(ThemeMode.DARK) {
            Text("知识", fontSize = 48.sp, modifier = Modifier.testTag("heading"))
        } }
        val pixels = compose.onNodeWithTag("heading").captureToImage().toPixelMap()
        var bright = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height)
            if (pixels[x,y].red > .7f && pixels[x,y].green > .7f && pixels[x,y].blue > .7f) bright++
        assertTrue(bright > 100)
    }
    @Test fun sparseEmbersMoveWhileTheDarkCanvasStaysSolidAndThemeSwitchWorks() {
        compose.mainClock.autoAdvance = false
        val mode = mutableStateOf(ThemeMode.DARK)
        compose.setContent { FragmentsPickerTheme(mode.value) {
            Box(Modifier.fillMaxSize().appBackground().testTag("background"))
        } }
        compose.mainClock.advanceTimeBy(32)
        val first = compose.onNodeWithTag("background").captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(2_000)
        val second = compose.onNodeWithTag("background").captureToImage().toPixelMap()
        var base = 0; var embers = 0; var changed = 0
        for (y in 0 until second.height step 2) for (x in 0 until second.width step 2) {
            val pixel = second[x,y]
            if (kotlin.math.abs(pixel.red - 20f/255) < .005f && kotlin.math.abs(pixel.green - 18f/255) < .005f && kotlin.math.abs(pixel.blue - 24f/255) < .005f) base++ else embers++
            if (first[x,y] != pixel) changed++
        }
        assertTrue("Most of the canvas remains solid", base > embers * 30)
        assertTrue("Small warm embers actually render", embers > 0)
        assertTrue("Embers remain tiny decorations", embers < base / 80)
        assertTrue("Embers float rather than staying static", changed > 10)
        compose.runOnIdle { mode.value = ThemeMode.LIGHT }
        compose.mainClock.advanceTimeBy(400)
        val light = compose.onNodeWithTag("background").captureToImage().toPixelMap()
        assertTrue(light[light.width/2,light.height/2].red > .8f)
    }
}
