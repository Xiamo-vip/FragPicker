package com.fragpicker.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.network.JsonApi
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.time.*
import java.util.UUID

class CalendarSummaryIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.After fun cleanup() = cleanupRealSession()
    @Test fun publishedSummaryAndLateSubmissionUpdateCalendarWithoutExpandingByDefault() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("realBackend") == "true" && args.getString("knowledgeFixture") == "true")
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("login_username").performTextInput("android_fixture")
        compose.onNodeWithTag("login_password").performTextInput("Android-fixture-123!")
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav_HISTORY").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_HISTORY").performClick()
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("calendar_toggle").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("day_$today").assertDoesNotExist()
        compose.onNodeWithTag("calendar_toggle").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("day_$today") and hasStateDescription("已有总结")).fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as FragPickerApplication
        val api = JsonApi(app.authRepository, runBlocking { app.authRepository.restore()!!.id })
        runBlocking { api.request("POST", "/api/v1/fragments", JSONObject().put("shareText", "https://b23.tv/${UUID.randomUUID()}"), UUID.randomUUID().toString()) }
        compose.onNodeWithContentDescription("刷新回顾").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("day_$today") and hasStateDescription("待补齐")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("calendar_toggle").performClick()
        compose.onNodeWithTag("day_$today").assertDoesNotExist()
    }
}
