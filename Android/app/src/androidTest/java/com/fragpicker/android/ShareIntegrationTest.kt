package com.fragpicker.android

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.auth.AuthApi
import com.fragpicker.android.core.network.JsonApi
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.*
import java.util.UUID

class ShareIntegrationTest {
    @org.junit.After fun cleanup() = cleanupRealSession()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun share(text: String) { compose.activityRule.scenario.onActivity { activity ->
        activity.startActivity(Intent(activity, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND; type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    } }
    @Test fun keepsPreLoginShareAndImportsWarmShareWithoutAutomaticSubmission() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realBackend") == "true")
        val username = "share_" + UUID.randomUUID().toString().take(8)
        val password = "Integration_" + UUID.randomUUID().toString().take(12)
        runBlocking { AuthApi().register(username, password) }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val first = "https://www.bilibili.com/video/BV1GJ411x7h7"
        val implicit = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; setPackage(compose.activity.packageName); putExtra(Intent.EXTRA_TEXT, first) }
        assertNotNull(compose.activity.packageManager.resolveActivity(implicit, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY))
        share(first)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("incoming_share").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("incoming_share").assertExists()
        compose.onNodeWithTag("login_username").performTextInput(username)
        compose.onNodeWithTag("login_password").performTextInput(password)
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("feed_share") and hasText(first, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as FragPickerApplication
        val api = JsonApi(app.authRepository, runBlocking { app.authRepository.restore()!!.id })
        val month = YearMonth.from(LocalDate.now(ZoneId.of("Asia/Shanghai")))
        fun count() = runBlocking { api.request("GET", "/api/v1/calendar?month=$month").getLong("total") }
        assertEquals(0L, count())
        compose.onNodeWithTag("feed_submit").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("feed_result").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1L, count())
        compose.onNodeWithTag("nav_HISTORY").performClick()
        val second = "https://www.douyin.com/video/7000000000000000099"
        share(second)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("替换草稿").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("替换草稿").performClick()
        compose.onNodeWithTag("feed_share").assertTextContains(second, substring = true)
        assertEquals(1L, count())
        compose.onNodeWithTag("feed_submit").performScrollTo().performClick()
        compose.waitUntil(20_000) { count() == 2L }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("feed_share").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("feed_share").assertTextContains(second, substring = true)
        compose.onNodeWithText("收到新的视频分享").assertDoesNotExist()
    }
}
