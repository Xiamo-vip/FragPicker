package com.fragpicker.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.theme.*
import com.fragpicker.android.core.ui.DailyDigestBody
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DailyDigestLayoutTest {
    @get:Rule val compose = createComposeRule()
    private fun result() = JSONObject().put("summary", "今天理解了导数与变化率的关系，先掌握极限，再练习求导。".repeat(30))
        .put("points", JSONArray((1..4).map { JSONObject().put("text", "关键要点$it") }))
        .put("categories", JSONArray(listOf("LEARNING"))).put("keywords", JSONArray(listOf("导数", "变化率")))
    @Test fun proseTakeawaysAndTopicsHaveSeparateBoundsAndExpandOnDemand() {
        compose.setContent { FragmentsPickerTheme(ThemeMode.LIGHT) {
            LazyColumn(Modifier.fillMaxSize().testTag("digest_list"), contentPadding = PaddingValues(20.dp)) { item { DailyDigestBody(result()) } }
        } }
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val initial = compose.onNodeWithTag("digest_summary").fetchSemanticsNode().boundsInRoot.height / density
        assertTrue("The collapsed prose is bounded", initial < 230)
        val prose = compose.onNodeWithTag("digest_summary_prose").fetchSemanticsNode().boundsInRoot
        val points = compose.onNodeWithTag("digest_summary_takeaways").fetchSemanticsNode().boundsInRoot
        assertTrue("Main prose has its own surface and spacing", points.top > prose.bottom)
        compose.onNodeWithText("关键要点4").assertDoesNotExist()
        compose.onNodeWithTag("digest_list").performScrollToNode(hasTestTag("digest_summary_points_expand"))
        compose.onNodeWithTag("digest_summary_points_expand").performClick()
        compose.onNodeWithText("关键要点4").assertExists()
        compose.onNodeWithTag("digest_list").performScrollToNode(hasTestTag("digest_summary_expand"))
        compose.onNodeWithTag("digest_summary_expand").performClick()
        val expanded = compose.onNodeWithTag("digest_summary").fetchSemanticsNode().boundsInRoot.height / density
        assertTrue("Full prose is reachable", expanded > initial * 2)
    }
    @Test fun compactHomeUsesTheSameHierarchyWithTwoTakeaways() {
        compose.setContent { FragmentsPickerTheme(ThemeMode.DARK) { DailyDigestBody(result(), "home_summary", compact = true) } }
        compose.onNodeWithText("关键要点1").assertExists(); compose.onNodeWithText("关键要点2").assertExists()
        compose.onNodeWithText("关键要点3").assertDoesNotExist()
        compose.onNodeWithTag("home_summary_prose").assertExists()
        compose.onNodeWithTag("home_summary_topics").assertExists()
    }
}
