package com.fragpicker.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.auth.AuthApi
import com.fragpicker.android.core.network.JsonApi
import com.fragpicker.android.feature.clipboard.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.time.*
import java.util.UUID

/** Real authenticated HTTP + MySQL. External resource preview alone uses the opt-in test fixture. */
class ClipboardIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @After fun cleanup() {
        compose.activityRule.scenario.onActivity { (it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("", "测试结束")) }
        cleanupRealSession()
    }
    private fun login(): JsonApi {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realBackend") == "true" &&
            InstrumentationRegistry.getArguments().getString("previewFixture") == "true")
        compose.activityRule.scenario.onActivity { (it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("", "普通文字")) }
        val username = "clip_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val password = "Integration_123!"
        runBlocking { AuthApi().register(username, password) }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("login_username").performTextInput(username)
        compose.onNodeWithTag("login_password").performTextInput(password)
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("home_total") and hasText("0")).fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as FragPickerApplication
        return JsonApi(app.authRepository, runBlocking { app.authRepository.restore()!!.id })
    }
    private fun count(api: JsonApi): Long = runBlocking {
        api.request("GET", "/api/v1/calendar?month=" + YearMonth.from(LocalDate.now(ZoneId.of("Asia/Shanghai")))).getLong("total")
    }
    private fun enter() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }
    private fun copyAndEnter(value: String) {
        compose.activityRule.scenario.onActivity { (it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("", value)) }
        enter()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("clipboard_dialog").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun checksResourceThenConfirmsOnceAndPreservesManualDraft() {
        val api = login()
        compose.onNodeWithTag("nav_FEED").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("feed_share").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("feed_share").performTextReplacement("https://b23.tv/manualdraft")
        compose.onNodeWithTag("feed_note").performTextReplacement("手动备注")
        compose.activityRule.scenario.onActivity {
            (it.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(it.window.decorView.windowToken, 0)
            it.currentFocus?.clearFocus()
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav_HOME").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_HOME").performClick()
        val source = "https://b23.tv/ClipboardValid" + UUID.randomUUID().toString().take(8)
        copyAndEnter("私人复制附言 $source。")
        compose.onNodeWithTag("clipboard_title").assertTextEquals("剪切板数学资源")
        assertEquals(0L, count(api))
        savePreview()
        compose.onNodeWithTag("clipboard_confirm").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("feed_result").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("feed_result").assertIsDisplayed()
        compose.onNodeWithTag("nav_FEED").assertIsSelected()
        compose.onNodeWithTag("feed_share").assertTextContains("https://b23.tv/manualdraft", substring = true)
        compose.onNodeWithTag("feed_note").assertTextContains("手动备注", substring = true)
        assertEquals(1L, count(api))
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("feed_result").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("clipboard_dialog").assertDoesNotExist()
        assertEquals(1L, count(api))
        val day = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val id = runBlocking { api.request("GET", "/api/v1/fragments?date=$day").getJSONArray("items").getJSONObject(0).getLong("fragmentId") }
        val detail = runBlocking { api.request("GET", "/api/v1/fragments/$id") }
        assertFalse(detail.toString().contains("私人复制附言"))
    }
    @Test fun cancellingNeverSubmitsAndTheSameClipboardIsNotPromptedAfterRecreation() {
        val api = login()
        copyAndEnter("https://b23.tv/ClipboardCancel" + UUID.randomUUID().toString().take(8))
        assertEquals(0L, count(api))
        compose.onNodeWithTag("clipboard_dismiss").performClick()
        enter()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("home_total").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("clipboard_dialog").assertDoesNotExist()
        assertEquals(0L, count(api))
    }
    @Test fun restoresUnconfirmedSubmissionAndUsesTheSameIdempotencyKey() {
        val api = login()
        val source = "https://b23.tv/ClipboardRestore" + UUID.randomUUID().toString().take(8)
        val pending = PendingClipboardFeed(UUID.randomUUID().toString(), ClipboardPreview(source, "上次的视频", "作者", "b23.tv"))
        val result = runBlocking { api.request("POST", "/api/v1/fragments", JSONObject().put("shareText", source).put("note", ""), pending.key) }
        val context = compose.activity.applicationContext
        ClipboardRequestStore(context.getSharedPreferences("clipboard_requests", Context.MODE_PRIVATE), api.baseUrl, api.userId).save(pending)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("clipboard_dialog").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("确认上次投喂").assertIsDisplayed()
        compose.onNodeWithTag("clipboard_dismiss").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("clipboard_dialog").assertDoesNotExist()
        assertEquals(1L, count(api))
        enter()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("clipboard_dialog").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1L, count(api))
        compose.onNodeWithTag("clipboard_confirm").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("feed_result").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1L, count(api))
        assertEquals(result.getLong("fragmentId"), context.getSharedPreferences("feed_last", Context.MODE_PRIVATE).getLong("${api.baseUrl}:${api.userId}", 0))
        assertNull(ClipboardRequestStore(context.getSharedPreferences("clipboard_requests", Context.MODE_PRIVATE), api.baseUrl, api.userId).load())
    }
    private fun savePreview() {
        val bitmap = compose.onNodeWithTag("clipboard_dialog").captureToImage().asAndroidBitmap()
        val resolver = compose.activity.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "clipboard-confirm.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FragPicker-QA")
        })!!
        resolver.openOutputStream(uri).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it!!) }
    }
}
