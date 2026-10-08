package com.fragpicker.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.auth.AuthApi
import com.fragpicker.android.core.network.JsonApi
import com.fragpicker.android.feature.history.DigestRequestStore
import com.fragpicker.android.feature.history.HistoryViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.*
import java.util.UUID

class DailyDigestIntegrationTest {
    @org.junit.After fun cleanup() = cleanupRealSession()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private val today get() = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
    private fun login(username: String, password: String): JsonApi {
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("login_username").performTextInput(username)
        compose.onNodeWithTag("login_password").performTextInput(password)
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav_HISTORY").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as FragPickerApplication
        return JsonApi(app.authRepository, runBlocking { app.authRepository.restore()!!.id })
    }
    @Test fun ownedDailySummaryOpensSourcesAndRebuildsThroughRealModel() {
        assumeTrue(args.getString("realBackend") == "true" && args.getString("knowledgeFixture") == "true")
        val api = login("android_fixture", "Android-fixture-123!")
        val initial = runBlocking { api.request("GET", "/api/v1/daily-digests/$today") }
        assertEquals("READY", initial.getString("status"))
        val id = initial.getJSONObject("result").getJSONArray("sources").getJSONObject(0).getLong("fragmentId")
        compose.onNodeWithTag("nav_HISTORY").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("history_list") and hasStateDescription("已加载1条")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("digest_summary"))
        compose.onNodeWithTag("digest_summary").assertTextEquals(initial.getJSONObject("result").getString("summary"))
        compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("digest_sources_toggle"))
        compose.onNodeWithTag("digest_sources_toggle").performClick()
        compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("digest_source_$id"))
        compose.onNodeWithTag("digest_source_$id").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("detail_play").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("history_list") and hasStateDescription("已加载1条")).fetchSemanticsNodes().isNotEmpty() }
        if (args.getString("digestLive") == "true") {
            compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("digest_regenerate"))
            compose.onNodeWithTag("digest_regenerate").performClick()
            compose.onNodeWithText("取消").performClick()
            assertEquals(1L, runBlocking { api.request("GET", "/api/v1/daily-digests/$today").getLong("requestedRevision") })
            compose.onNodeWithTag("digest_regenerate").performClick()
            compose.onNodeWithTag("digest_accept").performClick()
            compose.waitUntil(180_000) { compose.onAllNodes(hasTestTag("digest_card") and hasStateDescription("状态 READY · 第2版")).fetchSemanticsNodes().isNotEmpty() }
            val completed = runBlocking { api.request("GET", "/api/v1/daily-digests/$today") }
            assertEquals(2L, completed.getLong("completedRevision")); assertEquals(1L, completed.getJSONObject("result").getLong("sourceCount"))
            assertFalse(completed.getJSONObject("result").getString("summary").contains("其他人的秘密"))
            assertEquals(id, completed.getJSONObject("result").getJSONArray("sources").getJSONObject(0).getLong("fragmentId"))
            compose.onNodeWithTag("digest_confirm").assertDoesNotExist()
        }
        compose.onNodeWithTag("nav_SETTINGS").performClick()
        chooseAppearance(true)
        compose.onNodeWithTag("nav_HISTORY").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("digest_summary").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("digest_summary"))
        savePreview("daily-digest-dark.png")
        compose.onNodeWithTag("nav_SETTINGS").performClick()
        chooseAppearance(false)
    }
    @Test fun persistedUnknownSubmissionReconcilesSameRequestAfterRecreation() {
        assumeTrue(args.getString("realBackend") == "true" && args.getString("digestLive") == "true")
        val username = "pending_" + UUID.randomUUID().toString().take(8)
        val password = "Integration-123!"
        runBlocking { AuthApi().register(username, password) }
        val api = login(username, password)
        runBlocking { api.request("POST", "/api/v1/fragments", JSONObject().put("shareText", "https://b23.tv/" + UUID.randomUUID()), UUID.randomUUID().toString()) }
        val key = UUID.randomUUID().toString()
        assertEquals(1L, runBlocking { api.request("POST", "/api/v1/daily-digests/$today/regenerate", key = key).getLong("revision") })
        val preferences = compose.activity.getSharedPreferences("digest_requests", android.content.Context.MODE_PRIVATE)
        val saved = DigestRequestStore(preferences, api.baseUrl, api.userId)
        assertEquals(null, saved.load(today)); assertTrue(runBlocking { api.request("GET", "/api/v1/daily-digests/$today").getLong("requestedRevision") } == 1L)
        saved.save(today, key) // Simulate the durable state after losing the response, with the server request already committed.
        val models = androidx.lifecycle.ViewModelStore()
        lateinit var model: HistoryViewModel
        compose.runOnIdle { model = HistoryViewModel(api, saved); models.put("lifecycle", model); model.select(today) }
        compose.waitUntil(20_000) { model.state.value.digest != null && !model.state.value.digestLoading }
        val previous = model.state.value.digest
        compose.runOnIdle { model.pauseSummary(today); model.regenerate() }
        compose.waitUntil(20_000) { !model.state.value.digestBusy }
        assertSame("A completed submission must not restart reads after leaving the page", previous, model.state.value.digest)
        assertFalse(model.state.value.digestLoading)
        compose.runOnIdle { models.clear() }
        saved.save(today, key)
        compose.onNodeWithTag("nav_HISTORY").performClick(); compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("digest_confirm").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("digest_confirm"))
        compose.onNodeWithTag("digest_confirm").performClick()
        compose.waitUntil(20_000) { saved.load(today) == null }
        assertEquals(1L, runBlocking { api.request("GET", "/api/v1/daily-digests/$today").getLong("requestedRevision") })
        compose.onNodeWithTag("digest_confirm").assertDoesNotExist()
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
    private fun savePreview(name: String) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val resolver = compose.activity.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FragPicker-QA")
        }
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Unable to save QA screenshot")
        resolver.openOutputStream(uri).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it!!) }
    }
}
