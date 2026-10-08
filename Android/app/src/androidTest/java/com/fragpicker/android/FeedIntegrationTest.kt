package com.fragpicker.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.auth.*
import com.fragpicker.android.core.network.JsonApi
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class FeedIntegrationTest {
    @org.junit.After fun cleanup() = cleanupRealSession()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun submitsToRealBackendAndRestoresLastTaskAfterRecreation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realBackend") == "true")
        val username = "feed_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val password = "Integration_" + UUID.randomUUID().toString().take(12)
        runBlocking { AuthApi().register(username, password) }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("login_username").performTextInput(username)
        compose.onNodeWithTag("login_password").performTextInput(password)
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav_FEED").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_FEED").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("feed_submit").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("feed_submit").performScrollTo().performClick()
        compose.onNodeWithTag("feed_error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("feed_share").performScrollTo().performTextInput("https://www.bilibili.com/video/BV1GJ411x7h7")
        compose.onNodeWithTag("feed_submit").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("feed_result").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("feed_result").fetchSemanticsNodes().isNotEmpty() }
        val application = compose.activity.application as FragPickerApplication
        val userId = runBlocking { application.authRepository.restore()!!.id }
        val api = JsonApi(application.authRepository, userId)
        val savedId = application.getSharedPreferences("feed_last", android.content.Context.MODE_PRIVATE)
            .getLong("${api.baseUrl}:$userId", 0)
        assertTrue(savedId > 0)
        val date = runBlocking { api.request("GET", "/api/v1/fragments/$savedId").getString("businessDate") }
        val page = runBlocking { api.request("GET", "/api/v1/fragments?date=$date&limit=20") }
        assertEquals(1, page.getJSONArray("items").length())
        val wrongOwner = assertThrows(AuthApiFailure::class.java) { runBlocking {
            JsonApi(application.authRepository, userId + 1).request("GET", "/api/v1/fragments?limit=20")
        } }
        assertEquals(401, wrongOwner.status)
        compose.onNodeWithTag("nav_SETTINGS").performClick()
        compose.onNodeWithText("退出登录").performScrollTo().performClick()
        compose.onNodeWithText("确认退出").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("login_username").fetchSemanticsNodes().isNotEmpty() }
    }
}
