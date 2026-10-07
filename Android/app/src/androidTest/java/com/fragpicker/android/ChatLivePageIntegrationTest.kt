package com.fragpicker.android

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
import java.util.UUID

class ChatLivePageIntegrationTest {
    @org.junit.After fun cleanup() = cleanupRealSession()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun realDeepSeekAnswersEmptyPersonalLibraryAndPersistsTwoTurns() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("realBackend") == "true" && args.getString("chatLive") == "true")
        val username = "chatlive_" + UUID.randomUUID().toString().take(8)
        val password = "Integration_" + UUID.randomUUID().toString().take(12)
        runBlocking { AuthApi().register(username, password) }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("login_username").performTextInput(username)
        compose.onNodeWithTag("login_password").performTextInput(password)
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav_CHAT").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_CHAT").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("chat_send") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as FragPickerApplication
        val api = JsonApi(app.authRepository, runBlocking { app.authRepository.restore()!!.id })
        for (index in 1..2) {
            compose.onNodeWithTag("chat_question").performTextInput(if (index == 1) "请找出我之前保存的导数学习资料。没有资料请明确说明。" else "再次查询我的导数学习资料，只根据已保存资料回答。")
            compose.onNodeWithTag("chat_send").performClick()
            compose.waitUntil(180_000) { compose.onAllNodes(hasTestTag("chat_send") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat_confirm").assertDoesNotExist()
            val sessions = runBlocking { api.request("GET", "/api/v1/chat/sessions?limit=20").getJSONArray("items") }
            assertEquals(1, sessions.length())
            val session = sessions.getJSONObject(0).getLong("sessionId")
            val turns = runBlocking { api.request("GET", "/api/v1/chat/sessions/$session/messages?limit=10").getJSONArray("items") }
            assertEquals(index, turns.length())
            val saved = turns.getJSONObject(0)
            assertEquals("COMPLETED", saved.getString("state")); assertTrue(saved.getString("answer").isNotBlank())
            assertEquals(0, saved.getJSONArray("cards").length())
            compose.onNodeWithTag("chat_messages").performScrollToNode(hasTestTag("chat_turn_${saved.getLong("turnId")}"))
            compose.onNodeWithText(saved.getString("answer")).assertExists()
        }
    }
}
