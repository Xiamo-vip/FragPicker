package com.fragpicker.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ContentValues
import android.provider.MediaStore
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
    @Test fun denserEmbersMoveWhileTheDarkCanvasStaysSolidAndThemeSwitchWorks() {
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
        assertTrue("Most of the canvas remains solid", base > embers * 24)
        assertTrue("Small warm embers actually render", embers > 0)
        assertTrue("More particles still occupy under 4% of the canvas", embers < (base + embers) * .04f)
        assertTrue("Embers float rather than staying static", changed > 10)
        savePreview()
        compose.runOnIdle { mode.value = ThemeMode.LIGHT }
        compose.mainClock.advanceTimeBy(400)
        val light = compose.onNodeWithTag("background").captureToImage().toPixelMap()
        assertTrue(light[light.width/2,light.height/2].red > .8f)
    }

    @Test fun densityIncreasesOnPhonesAndStaysBoundedOnLargeWindows() {
        listOf(240f * 320, 360f * 800, 412f * 900, 600f * 960, 1_200f * 2_000).forEach { area ->
            val previous = (area / 12_000f).toInt().coerceIn(24, 64)
            val current = emberCount(area)
            assertTrue("More than twice the previous particle slots", current > previous * 2)
            assertTrue("Particle budget is bounded", current in 64..160)
        }
        assertTrue(emberCount(412f * 900) > emberCount(360f * 800))
    }

    @Test fun animationPausesInBackgroundAndResumesWithoutCatchingUp() {
        compose.mainClock.autoAdvance = false
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        var clock: EmberAnimation? = null
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            FragmentsPickerTheme(ThemeMode.DARK) {
                val current = LocalEmberAnimation.current
                SideEffect { clock = current }
                Box(Modifier.fillMaxSize().appBackground().testTag("paused_background"))
            }
        } }
        compose.mainClock.advanceTimeBy(500)
        val activeSeconds = clock!!.seconds.floatValue
        assertTrue(activeSeconds > 0f)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.mainClock.advanceTimeBy(32)
        val before = compose.onNodeWithTag("paused_background").captureToImage().toPixelMap()
        val pausedSeconds = clock!!.seconds.floatValue
        compose.mainClock.advanceTimeBy(2_000)
        val after = compose.onNodeWithTag("paused_background").captureToImage().toPixelMap()
        assertEquals(pausedSeconds, clock!!.seconds.floatValue, 0f)
        for (y in 0 until after.height step 12) for (x in 0 until after.width step 12)
            assertEquals("Background is static while paused", before[x,y], after[x,y])
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeBy(48)
        assertTrue("No two-second catch-up jump", clock!!.seconds.floatValue - pausedSeconds < .1f)
        compose.mainClock.advanceTimeBy(500)
        assertTrue(clock!!.seconds.floatValue > pausedSeconds + .2f)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }

    private fun savePreview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "embers-0.1.4-preview.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FragPicker-QA")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use {
            compose.onNodeWithTag("background").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
