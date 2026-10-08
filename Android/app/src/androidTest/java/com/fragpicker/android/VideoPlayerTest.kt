package com.fragpicker.android

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.theme.*
import com.fragpicker.android.feature.detail.VideoPlayer
import com.fragpicker.android.feature.detail.VideoChapter
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Rule
import org.junit.Test
import java.io.File

class VideoPlayerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun decodesSelfGeneratedVideoPlaysPausesAndSeeks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "player-test.mp4")
        instrumentation.context.assets.open("player-test.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            compose.setContent {
                var seek by remember { mutableIntStateOf(0) }
                FragmentsPickerTheme(ThemeMode.DARK) { Column {
                    VideoPlayer(file.toURI().toString(), if (seek > 0) 2000 else 0, seek, {}, chapters = listOf(
                        VideoChapter(0, 0, 2000, "基础概念"), VideoChapter(1, 2000, 4000, "切线与变化率")))
                    Button(onClick = { seek++ }) { Text("跳到两秒") }
                } }
            }
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("video_toggle") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("video_toggle").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("00:01 / 00:04").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("暂停").performClick()
            compose.onNodeWithContentDescription("播放").assertExists()
            compose.onNodeWithText("跳到两秒").performClick()
            compose.onNodeWithText("00:02 / 00:04").assertExists()
            compose.onNodeWithTag("video_current_chapter").assertTextContains("切线与变化率", substring = true)
            compose.onNodeWithTag("video_speed").performClick()
            compose.onNodeWithTag("video_speed_1.5").performClick()
            compose.onNodeWithTag("video_speed").assertTextContains("1.5×")
            compose.onNodeWithContentDescription("播放").assertExists()
            compose.onNodeWithTag("video_forward").performClick()
            compose.onNodeWithText("00:04 / 00:04").assertExists()
            compose.onNodeWithTag("video_rewind").performClick()
            compose.onNodeWithText("00:00 / 00:04").assertExists()
            compose.onNodeWithTag("video_seek").performSemanticsAction(SemanticsActions.SetProgress) { it(2000f) }
            compose.onNodeWithTag("video_fullscreen").performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("video_toggle") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("video_fullscreen_panel").assertIsDisplayed()
            compose.onNodeWithText("00:02 / 00:04").assertExists()
            compose.onNodeWithTag("video_fullscreen").performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("video_toggle") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("video_fullscreen_panel").assertDoesNotExist()
            compose.onNodeWithTag("video_error").assertDoesNotExist()
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                File(instrumentation.targetContext.cacheDir, "player-verified.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }; screenshot.recycle()
            }
        } finally { file.delete() }
    }
}
