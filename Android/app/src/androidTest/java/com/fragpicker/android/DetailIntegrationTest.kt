package com.fragpicker.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.auth.AuthApi
import com.fragpicker.android.core.network.JsonApi
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class DetailIntegrationTest {
    @org.junit.After fun cleanup() = cleanupRealSession()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun readsOwnedPendingContentAndReturnsToFeed() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realBackend") == "true")
        val username = "detail_" + UUID.randomUUID().toString().take(8)
        val password = "Integration_" + UUID.randomUUID().toString().take(12)
        runBlocking { AuthApi().register(username, password) }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("login_username").performTextInput(username)
        compose.onNodeWithTag("login_password").performTextInput(password)
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("feed_share").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("feed_share").performScrollTo().performTextInput("https://www.bilibili.com/video/BV1GJ411x7h7")
        compose.onNodeWithTag("feed_submit").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("查看内容").fetchSemanticsNodes().isNotEmpty() }
        val application = compose.activity.application as FragPickerApplication
        val api = JsonApi(application.authRepository, runBlocking { application.authRepository.restore()!!.id })
        val id = application.getSharedPreferences("feed_last", android.content.Context.MODE_PRIVATE).getLong("${api.baseUrl}:${api.userId}", 0)
        runBlocking { api.request("GET", "/api/v1/fragments/$id/content") }
        compose.onNodeWithText("查看内容").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("detail_play").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("detail_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_error").assertDoesNotExist()
        compose.onNodeWithTag("detail_play").assertIsNotEnabled()
        compose.onNodeWithTag("nav_FEED").assertDoesNotExist()
        compose.onNodeWithText("转写完成后将在这里显示原文。").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("投喂记录").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("feed_share").assertExists()
    }
}
