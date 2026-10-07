package com.fragpicker.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.auth.AuthApi
import com.fragpicker.android.core.network.JsonApi
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.time.*
import java.util.UUID

class HistoryIntegrationTest {
    @org.junit.After fun cleanup() = cleanupRealSession()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun calendarListsOnlyOwnedDayPagesAndOpensDetails() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realBackend") == "true")
        val username = "history_" + UUID.randomUUID().toString().take(8)
        val password = "Integration_" + UUID.randomUUID().toString().take(12)
        runBlocking { AuthApi().register(username, password) }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("login_username").performTextInput(username)
        compose.onNodeWithTag("login_password").performTextInput(password)
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("feed_share").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as FragPickerApplication
        val api = JsonApi(app.authRepository, runBlocking { app.authRepository.restore()!!.id })
        val ids = runBlocking { (0 until 23).map { index -> api.request("POST", "/api/v1/fragments",
            JSONObject().put("shareText", "https://www.douyin.com/video/${7000000000000000000L + index}").put("note", "日历测试 $index"),
            UUID.randomUUID().toString()).getLong("fragmentId") } }
        compose.onNodeWithTag("nav_HISTORY").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("本日共23条 · 已整理0 · 处理中23 · 失败0").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("history_list") and hasStateDescription("已加载20条")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("history_more"))
        compose.onNodeWithTag("history_more").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("history_list") and hasStateDescription("已加载23条")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history_more").assertDoesNotExist()
        compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("fragment_${ids.first()}"))
        compose.onNodeWithTag("fragment_${ids.first()}").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("detail_play").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_page").performScrollToNode(hasText("日历测试 0"))
        compose.onNodeWithText("日历测试 0").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("history_list").performScrollToNode(hasContentDescription("上个月"))
        compose.onNodeWithContentDescription("上个月").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("history_list") and hasStateDescription("已加载0条")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("history_empty"))
        compose.onNodeWithTag("history_empty").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        val lastMonth = YearMonth.from(LocalDate.now(ZoneId.of("Asia/Shanghai"))).minusMonths(1)
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("history_list") and hasStateDescription("已加载0条")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history_list").performScrollToNode(hasText("${lastMonth.year}年${lastMonth.monthValue}月"))
        compose.onNodeWithText("回到今天").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("本日共23条 · 已整理0 · 处理中23 · 失败0").fetchSemanticsNodes().isNotEmpty() }
    }
}
