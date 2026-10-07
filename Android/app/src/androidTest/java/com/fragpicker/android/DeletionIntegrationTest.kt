package com.fragpicker.android

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.auth.*
import com.fragpicker.android.core.network.JsonApi
import com.fragpicker.android.feature.deletion.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.util.UUID

class DeletionIntegrationTest {
    @After fun cleanup() = cleanupRealSession()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun login(): JsonApi {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realBackend") == "true")
        val name = "delete_" + UUID.randomUUID().toString().take(8)
        val password = "Integration_" + UUID.randomUUID().toString().take(12)
        runBlocking { AuthApi().register(name, password) }
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("login_username").performTextInput(name)
        compose.onNodeWithTag("login_password").performTextInput(password)
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav_HISTORY").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as FragPickerApplication
        return JsonApi(app.authRepository, runBlocking { app.authRepository.restore()!!.id })
    }
    private fun submit(api: JsonApi) = runBlocking {
        api.request("POST", "/api/v1/fragments", JSONObject().put("shareText", "https://b23.tv/" + UUID.randomUUID()), UUID.randomUUID().toString()).getLong("fragmentId")
    }
    private fun store(api: JsonApi) = DeletionRequestStore(compose.activity.getSharedPreferences("deletion_requests", Context.MODE_PRIVATE), api.baseUrl, api.userId)
    @Test fun cancelThenDeleteReturnsToEmptyHistoryAndClearsFeedAfterRecreation() {
        val api = login(); val id = submit(api)
        val feed = compose.activity.getSharedPreferences("feed_last", Context.MODE_PRIVATE)
        feed.edit().putLong("${api.baseUrl}:${api.userId}", id).commit()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("查看内容").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("查看内容").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("delete_start") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("delete_start").performClick()
        compose.onNodeWithTag("delete_cancel").performClick()
        assertFalse(store(api).pending(id)); runBlocking { api.request("GET", "/api/v1/fragments/$id") }
        compose.onNodeWithTag("delete_start").performClick(); compose.onNodeWithTag("delete_accept").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("nav_HISTORY") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("history_list") and hasStateDescription("已加载0条")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, feed.getLong("${api.baseUrl}:${api.userId}", 0)); assertFalse(store(api).pending(id))
        try { runBlocking { api.request("GET", "/api/v1/fragments/$id/content") }; fail("Deleted content was returned") }
        catch (error: AuthApiFailure) { assertEquals(404, error.status) }
        compose.onNodeWithTag("nav_FEED").performClick(); compose.onNodeWithTag("feed_result").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("feed_share").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("feed_result").assertDoesNotExist()
    }
    @Test fun acknowledgedDeleteWithLostClientResponseReplaysSameOwnedTombstone() {
        val api = login(); val id = submit(api)
        val result = runBlocking { api.request("DELETE", "/api/v1/fragments/$id") }
        assertFalse(result.getBoolean("duplicate"))
        val saved = store(api); saved.save(id)
        val models = androidx.lifecycle.ViewModelStore()
        lateinit var model: DeletionViewModel; var cleared = false
        compose.runOnIdle { model = DeletionViewModel(api, id, saved) { cleared = true }; models.put("delete", model); model.load() }
        compose.waitUntil(20_000) { !model.state.value.loading }
        assertTrue(model.state.value.pending)
        compose.runOnIdle { model.delete() }
        compose.waitUntil(20_000) { model.state.value.deleted }
        assertTrue(cleared); assertFalse(saved.pending(id))
        assertTrue(runBlocking { api.request("DELETE", "/api/v1/fragments/$id") }.getBoolean("duplicate"))
        compose.runOnIdle { models.clear() }
    }
    @Test fun deletionIntentSurvivesNewStoreAndCannotCrossAccountOrBackend() {
        val prefs = compose.activity.getSharedPreferences("deletion_test_" + UUID.randomUUID(), Context.MODE_PRIVATE)
        val first = DeletionRequestStore(prefs, "https://example.com/", 1)
        first.save(2)
        assertTrue(DeletionRequestStore(prefs, "https://example.com", 1).pending(2))
        assertFalse(DeletionRequestStore(prefs, "https://example.com", 3).pending(2))
        assertFalse(DeletionRequestStore(prefs, "https://other.example.com", 1).pending(2))
        assertFalse(first.pending(3)); first.clear(2); assertFalse(first.pending(2)); prefs.edit().clear().commit()
    }
}
