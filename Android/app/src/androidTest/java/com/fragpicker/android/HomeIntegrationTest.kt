package com.fragpicker.android

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.auth.AuthApi
import com.fragpicker.android.core.network.JsonApi
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.time.*
import java.util.UUID

class HomeIntegrationTest {
    @After fun cleanup() = cleanupRealSession()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun login(username: String, password: String): JsonApi {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realBackend") == "true")
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("login_username").performTextInput(username)
        compose.onNodeWithTag("login_password").performTextInput(password)
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("home_total") and hasText("0")).fetchSemanticsNodes().isNotEmpty() || compose.onAllNodes(hasTestTag("home_total") and hasText("1")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_HOME").assertIsSelected()
        val app = compose.activity.application as FragPickerApplication
        return JsonApi(app.authRepository, runBlocking { app.authRepository.restore()!!.id })
    }
    @Test fun defaultHomeFeedsAndRefreshesTodayThenOpensDetailAndChat() {
        val username="home_"+UUID.randomUUID().toString().take(8); val password="Integration_123!"
        assumeTrue(InstrumentationRegistry.getArguments().getString("realBackend") == "true")
        runBlocking { AuthApi().register(username,password) }
        val api=login(username,password)
        compose.onNodeWithTag("home_total").assertTextEquals("0")
        compose.onNodeWithTag("home_feed").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("feed_share").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("feed_share").performTextReplacement("https://b23.tv/HomeTest123")
        compose.onNodeWithTag("feed_submit").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("feed_result").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_HOME").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("home_total") and hasText("1")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home_processing").assertTextEquals("1")
        val day=LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val id=runBlocking { api.request("GET","/api/v1/fragments?date=$day&limit=5").getJSONArray("items").getJSONObject(0).getLong("fragmentId") }
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_fragment_$id"))
        compose.onNodeWithTag("home_fragment_$id").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("detail_play").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("nav_HOME").assertIsSelected()
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_chat"))
        compose.onNodeWithTag("home_chat").performClick()
        compose.onNodeWithTag("nav_CHAT").assertIsSelected()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("nav_HOME").assertIsSelected()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("home_total").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_HOME").assertIsSelected()
    }
    @Test fun showsOnlyOwnedSummaryAndKeepsItThroughThemeAndHistoryNavigation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("knowledgeFixture") == "true")
        val api=login("android_fixture","Android-fixture-123!")
        val today=LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val expected=runBlocking { api.request("GET","/api/v1/daily-digests/$today").getJSONObject("result").getString("summary") }
        compose.onNodeWithTag("home_ready").assertTextEquals("1")
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_summary"))
        compose.onNodeWithTag("home_summary").assertTextEquals(expected)
        compose.onNodeWithText("其他人的秘密每日总结").assertDoesNotExist()
        compose.onNodeWithTag("nav_SETTINGS").performClick()
        chooseAppearance(true)
        compose.onNodeWithTag("nav_HOME").performClick()
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_summary"))
        compose.onNodeWithTag("home_summary").assertTextEquals(expected)
        savePreview()
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_history"))
        compose.onNodeWithTag("home_history").performClick()
        compose.onNodeWithTag("nav_HISTORY").assertIsSelected()
    }
    private fun chooseAppearance(dark: Boolean) {
        val switch = compose.onNodeWithTag("theme_switch").performScrollTo()
        val matches = runCatching { if (dark) switch.assertIsOn() else switch.assertIsOff() }.isSuccess
        if (!matches) switch.performClick()
        compose.waitUntil(10_000) { runCatching {
            switch.assertIsEnabled()
            if (dark) switch.assertIsOn() else switch.assertIsOff()
        }.isSuccess }
    }
    private fun savePreview() {
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        val resolver=compose.activity.contentResolver
        val uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,"home-dark.png"); put(MediaStore.Images.Media.MIME_TYPE,"image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/FragPicker-QA")
        })!!
        resolver.openOutputStream(uri).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it!!) }
    }
}
