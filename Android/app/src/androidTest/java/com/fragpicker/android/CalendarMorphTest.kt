package com.fragpicker.android

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.theme.*
import com.fragpicker.android.feature.history.HistoryCalendar
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.YearMonth

class CalendarMorphTest {
    @get:Rule val compose = createComposeRule()
    private val month = YearMonth.of(2026, 10)
    private fun days() = (1..31).associate { number -> month.atDay(number).toString() to JSONObject()
        .put("total", if (number <= 3) 1 else 0).put("hasSummary", number == 1 || number == 3).put("summaryOutdated", number == 3) }
    @Test fun startsAsBallExpandsInPlaceAndCollapsesWithoutLeavingCalendarNodes() {
        compose.mainClock.autoAdvance = false
        compose.setContent { FragmentsPickerTheme(ThemeMode.LIGHT) { HistoryCalendar(month, "2026-10-01", days(), false, {}, {}, {}, heading = "让知识在日历里生长。") } }
        compose.mainClock.advanceTimeBy(32)
        val ball = compose.onNodeWithTag("history_calendar").fetchSemanticsNode().boundsInRoot
        val heading = compose.onNodeWithTag("history_heading").fetchSemanticsNode().boundsInRoot
        assertTrue("Calendar ball sits beside the heading", ball.left > heading.right)
        assertEquals("Calendar ball shares the heading row", heading.center.y, ball.center.y, 2f)
        compose.onNodeWithTag("day_2026-10-01").assertDoesNotExist()
        compose.onNodeWithTag("calendar_toggle").performClick()
        compose.mainClock.advanceTimeBy(800)
        val card = compose.onNodeWithTag("history_calendar").fetchSemanticsNode().boundsInRoot
        assertTrue(card.width > ball.width * 3)
        assertEquals("Expansion keeps the right anchor", ball.right, card.right, 2f)
        val toggle = compose.onNodeWithTag("calendar_toggle").fetchSemanticsNode().boundsInRoot
        assertEquals("Toggle stays at the original ball", ball.center.x, toggle.center.x, 2f)
        compose.onNodeWithTag("day_2026-10-02").assert(hasStateDescription("尚无总结"))
        compose.onNodeWithTag("calendar_toggle").performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("day_2026-10-01").assertDoesNotExist()
        assertEquals(ball.width, compose.onNodeWithTag("history_calendar").fetchSemanticsNode().boundsInRoot.width, 2f)
    }
    @Test fun statusesRemainDistinctInDarkThemeAndSummaryFilterKeepsReadableDates() {
        val date = mutableStateOf("2026-10-01")
        compose.setContent { FragmentsPickerTheme(ThemeMode.DARK) { HistoryCalendar(month, date.value, days(), false, {}, { date.value = it }, { date.value = "2026-10-02" }) } }
        compose.onNodeWithTag("calendar_toggle").performClick()
        compose.onNodeWithTag("day_2026-10-01").assert(hasStateDescription("已有总结"))
        compose.onNodeWithTag("day_2026-10-02").assert(hasStateDescription("尚无总结"))
        compose.onNodeWithTag("day_2026-10-03").assert(hasStateDescription("待补齐"))
        compose.onNodeWithTag("day_2026-10-04").assert(hasStateDescription("无投喂"))
        compose.onNodeWithTag("calendar_summary_filter").performClick()
        compose.onNodeWithTag("day_2026-10-02").assertIsNotEnabled()
        compose.onNodeWithTag("day_2026-10-01").assertIsEnabled()
        compose.onNodeWithTag("day_2026-10-03").performClick()
        compose.runOnIdle { assertEquals("2026-10-03", date.value) }
        savePreview()
        compose.onNodeWithText("回到今天").performClick()
        compose.runOnIdle { assertEquals("2026-10-02", date.value) }
        compose.onNodeWithTag("calendar_summary_filter").assertIsNotSelected()
    }
    private fun savePreview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "calendar-top-right-preview.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FragPicker-QA") }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { compose.onNodeWithTag("history_calendar").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
