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
                    VideoPlayer(file.toURI().toString(), if (seek > 0) 2000 else 0, seek, {})
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
            compose.onNodeWithTag("video_error").assertDoesNotExist()
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                File(instrumentation.targetContext.cacheDir, "player-verified.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }; screenshot.recycle()
            }
        } finally { file.delete() }
    }
}
