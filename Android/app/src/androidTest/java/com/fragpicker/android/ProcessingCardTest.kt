package com.fragpicker.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.fragpicker.android.core.theme.*
import com.fragpicker.android.core.ui.*
import org.junit.Rule
import org.junit.Test

class ProcessingCardTest {
    @get:Rule val compose = createComposeRule()
    @Test fun expandsIntoCardAndHandlesFailureWithAnAction() {
        val visual = mutableStateOf(ProcessingVisual.WAITING)
        var clicked = false
        compose.setContent { FragmentsPickerTheme(ThemeMode.DARK) {
            ProcessingCard("job", visual.value, "整理视频", "后台会继续处理，你可以稍后回来。",
                actionLabel = "查看详情", action = { clicked = true })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("整理视频").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("processing_WAITING").assertIsDisplayed()
        compose.runOnIdle { visual.value = ProcessingVisual.COMPLETE }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("查看详情").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("查看详情").performClick()
        compose.runOnIdle { check(clicked); visual.value = ProcessingVisual.FAILED }
        compose.onNodeWithTag("processing_FAILED").assertIsDisplayed()
    }
}
