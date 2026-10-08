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

class ChatSourcePageIntegrationTest {
    @org.junit.After fun cleanup() = cleanupRealSession()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun realModelRetrievesOwnedOnnxSourceShowsCardAndOpensTranscript() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("realBackend") == "true" && args.getString("chatLive") == "true" && args.getString("knowledgeFixture") == "true")
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("login_username").performTextInput("android_fixture")
        compose.onNodeWithTag("login_password").performTextInput("Android-fixture-123!")
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav_CHAT").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_CHAT").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("chat_send") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_question").performTextInput("请帮我找一下我之前想要学习的数学资源有关导数的。")
        compose.onNodeWithTag("chat_send").performClick()
        compose.waitUntil(180_000) { compose.onAllNodes(hasTestTag("chat_send") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_confirm").assertDoesNotExist()
        val app = compose.activity.application as FragPickerApplication
        val api = JsonApi(app.authRepository, runBlocking { app.authRepository.restore()!!.id })
        val session = runBlocking { api.request("GET", "/api/v1/chat/sessions?limit=20").getJSONArray("items").getJSONObject(0).getLong("sessionId") }
        val saved = runBlocking { api.request("GET", "/api/v1/chat/sessions/$session/messages?limit=10").getJSONArray("items").getJSONObject(0) }
        assertEquals("COMPLETED", saved.getString("state")); assertTrue(saved.getString("answer").contains("导数"))
        assertFalse(saved.getString("answer").contains("其他人的秘密"))
        val cards = saved.getJSONArray("cards"); assertEquals(1, cards.length())
        val id = cards.getJSONObject(0).getLong("fragmentId")
        assertEquals("导数与瞬时变化率课程", cards.getJSONObject(0).getString("title"))
        compose.onNodeWithTag("chat_messages").performScrollToNode(hasTestTag("fragment_$id"))
        compose.onNodeWithTag("fragment_$id").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("detail_play").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_page").performScrollToNode(hasTestTag("detail_tab_2"))
        compose.onNodeWithTag("detail_tab_2").performClick()
        compose.onNodeWithTag("detail_page").performScrollToNode(hasTestTag("transcript_seek"))
        compose.onNodeWithTag("transcript_seek").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("chat_question").assertExists()
    }
}
