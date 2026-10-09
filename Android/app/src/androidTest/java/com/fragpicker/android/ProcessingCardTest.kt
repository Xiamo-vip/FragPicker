package com.fragpicker.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.fragpicker.android.core.theme.*
import com.fragpicker.android.core.ui.*
import com.fragpicker.android.feature.feed.FeedFeedback
import com.fragpicker.android.feature.feed.FeedState
import org.junit.Assert.*
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

    @Test fun completionHasOneMonotonicExpansionAndNoDelayedSecondGrowth() {
        compose.mainClock.autoAdvance = false
        val visual = mutableStateOf(ProcessingVisual.WAITING)
        val detail = "保存摘要和原文时间点，稍后从回顾页查看。".repeat(8)
        compose.setContent { FragmentsPickerTheme(ThemeMode.LIGHT) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                ProcessingCard("long-job", visual.value, "整理视频", detail,
                    actionLabel = "查看详情", action = {})
            }
        } }
        compose.mainClock.advanceTimeBy(800)
        val pill = compose.onNodeWithTag("processing_WAITING").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { visual.value = ProcessingVisual.COMPLETE }
        var previous = pill
        repeat(24) {
            compose.mainClock.advanceTimeBy(16)
            val frame = compose.onNodeWithTag("processing_COMPLETE").fetchSemanticsNode().boundsInRoot
            assertTrue("Width never contracts during expansion", frame.width >= previous.width - 1f)
            assertTrue("Height never contracts during expansion", frame.height >= previous.height - 1f)
            previous = frame
        }
        val settled = previous
        compose.mainClock.advanceTimeBy(800)
        val final = compose.onNodeWithTag("processing_COMPLETE").fetchSemanticsNode().boundsInRoot
        assertEquals("No second width animation after 384ms", settled.width, final.width, 1f)
        assertEquals("No second height animation after 384ms", settled.height, final.height, 1f)
        assertTrue(final.height > pill.height)
        compose.onAllNodesWithText(detail).assertCountEquals(1)
        compose.onNodeWithText("查看详情").assertIsDisplayed()
    }

    @Test fun receiptDoesNotRecreateOrReplayTheSubmissionCircle() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(FeedState(sending = true))
        compose.setContent { FragmentsPickerTheme(ThemeMode.LIGHT) { FeedFeedback(state.value) } }
        compose.mainClock.advanceTimeBy(800)
        val submitted = compose.onNodeWithTag("processing_WAITING").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = FeedState(id = 42, status = "PENDING", date = "2026-10-09") }
        repeat(40) {
            compose.mainClock.advanceTimeBy(16)
            val accepted = compose.onNodeWithTag("processing_WAITING").fetchSemanticsNode().boundsInRoot
            assertEquals("ACK must retain the expanded width", submitted.width, accepted.width, 1f)
        }
        compose.onNodeWithTag("feed_result").assertIsDisplayed()
        compose.onNodeWithText("后台已接收").assertIsDisplayed()
    }
}
