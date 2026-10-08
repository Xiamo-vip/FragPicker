package com.fragpicker.android

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.network.JsonApi
import com.fragpicker.android.feature.retry.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.util.UUID

class RetryIntegrationTest {
    @After fun cleanup() = cleanupRealSession()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun login(name: String): Pair<JsonApi, Long> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("realBackend") == "true" && args.getString("retryFixture") == "true")
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("login_username").performTextInput("android_retry_$name")
        compose.onNodeWithTag("login_password").performTextInput("Android-fixture-123!")
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav_HISTORY").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as FragPickerApplication
        val api = JsonApi(app.authRepository, runBlocking { app.authRepository.restore()!!.id })
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
        val id = runBlocking { api.request("GET", "/api/v1/fragments?date=$today&limit=20").getJSONArray("items").getJSONObject(0).getLong("fragmentId") }
        return api to id
    }
    private fun restoreFeed(api: JsonApi, id: Long) {
        compose.activity.getSharedPreferences("feed_last", Context.MODE_PRIVATE).edit().putLong("${api.baseUrl}:${api.userId}", id).commit()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav_FEED").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_FEED").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("feed_result").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun store(api: JsonApi) = RetryRequestStore(compose.activity.getSharedPreferences("retry_requests", Context.MODE_PRIVATE), api.baseUrl, api.userId)
    private fun status(api: JsonApi, id: Long) = runBlocking { api.request("GET", "/api/v1/fragments/$id") }
    @Test fun feedConfirmsRealRetryAndCancelLeavesFailedJobUntouched() {
        val (api, id) = login("parse"); restoreFeed(api, id)
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("retry_start").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("retry_start").performScrollTo().performClick()
        compose.onNodeWithTag("retry_cancel").performClick()
        assertEquals("FAILED", status(api, id).getString("status")); assertNull(store(api).load(id))
        compose.onNodeWithTag("retry_start").performScrollTo().performClick()
        compose.onNodeWithTag("retry_accept").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("retry_message").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("QUEUED", status(api, id).getString("status")); assertNull(store(api).load(id))
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("feed_result").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("retry_start").assertDoesNotExist()
    }
    @Test fun detailRequiresReplacementConfirmationForUncertainTranscription() {
        val (api, id) = login("uncertain")
        compose.onNodeWithTag("nav_HISTORY").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("history_list") and hasStateDescription("已加载1条")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("fragment_$id"))
        compose.onNodeWithTag("fragment_$id").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("retry_start").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_page").performScrollToNode(hasTestTag("retry_start"))
        compose.onNodeWithTag("retry_start").performClick()
        compose.onNodeWithText("重新提交转写？").assertIsDisplayed()
        compose.onNodeWithText("先前转写失败或提交结果无法确认。重新提交可能再次产生费用，原任务信息会保留供核对。").assertIsDisplayed()
        compose.onNodeWithTag("retry_cancel").performClick(); assertEquals("FAILED", status(api, id).getString("status"))
        compose.onNodeWithTag("retry_start").performClick(); compose.onNodeWithTag("retry_accept").performClick()
        compose.waitUntil(20_000) { status(api, id).getString("status") == "TRANSCRIPTION_PENDING" }
        assertNull(store(api).load(id)); assertFalse(status(api, id).getBoolean("canRetry"))
    }
    @Test fun persistedUnknownResponseReusesRequestAndPausedPageDoesNotRestartReads() {
        val (api, id) = login("resume"); val key = UUID.randomUUID().toString()
        val first = runBlocking { api.request("POST", "/api/v1/fragments/$id/retry", JSONObject().put("replaceTranscription", false), key) }
        assertEquals("TRANSCRIBING", first.getString("status"))
        val saved = store(api); saved.save(id, PendingRetry(key, false))
        val models = androidx.lifecycle.ViewModelStore()
        lateinit var model: RetryViewModel; var callback = false
        compose.runOnIdle { model = RetryViewModel(api, id, saved); models.put("retry", model); model.resume() }
        compose.waitUntil(20_000) { !model.state.value.loading }
        compose.runOnIdle { model.pause(); model.retry(false) { callback = true } }
        compose.waitUntil(20_000) { !model.state.value.busy }
        assertFalse(callback); assertFalse(model.state.value.loading); assertNull(saved.load(id))
        compose.runOnIdle { models.clear() }
        saved.save(id, PendingRetry(key, false)); restoreFeed(api, id)
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("retry_reconcile").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("retry_reconcile").performScrollTo().performClick()
        compose.waitUntil(20_000) { saved.load(id) == null }
        val replay = runBlocking { api.request("POST", "/api/v1/fragments/$id/retry", JSONObject().put("replaceTranscription", false), key) }
        assertTrue(replay.getBoolean("duplicate")); assertEquals(first.getLong("jobVersion"), replay.getLong("jobVersion"))
    }
}
