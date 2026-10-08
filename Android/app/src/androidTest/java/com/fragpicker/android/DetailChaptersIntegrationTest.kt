package com.fragpicker.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.network.JsonApi
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.*

class DetailChaptersIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.After fun cleanup() = cleanupRealSession()
    @Test fun readsOwnedChaptersAndSeparatesOverviewOriginalAndSource() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("realBackend") == "true" && args.getString("knowledgeFixture") == "true")
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("login_username").performTextInput("android_fixture")
        compose.onNodeWithTag("login_password").performTextInput("Android-fixture-123!")
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav_HOME").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as FragPickerApplication
        val api = JsonApi(app.authRepository, runBlocking { app.authRepository.restore()!!.id })
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val id = runBlocking { api.request("GET", "/api/v1/fragments?date=$today").getJSONArray("items").getJSONObject(0).getLong("fragmentId") }
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_fragment_$id"))
        compose.onNodeWithTag("home_fragment_$id").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("detail_source").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_source").assertIsDisplayed()
        compose.onNodeWithTag("detail_page").performScrollToNode(hasTestTag("detail_tab_1"))
        compose.onNodeWithTag("detail_tab_1").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("chapter_1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chapter_1").assertTextContains("用差商极限求导", substring = true)
        compose.onNodeWithText("核心摘要").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("chapter_1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_tab_2").performClick()
        compose.onNodeWithTag("detail_page").performScrollToNode(hasTestTag("transcript_seek"))
        compose.onNodeWithTag("transcript_seek").assertIsDisplayed()
        compose.onNodeWithTag("detail_error").assertDoesNotExist()
    }
}
