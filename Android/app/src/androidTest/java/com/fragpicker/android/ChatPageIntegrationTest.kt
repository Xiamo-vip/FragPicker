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

class ChatPageIntegrationTest {
    @org.junit.After fun cleanup() = cleanupRealSession()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun failedSendIsConfirmedOnceAndSessionSurvivesRecreation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realBackend") == "true")
        val username = "chatui_" + UUID.randomUUID().toString().take(8)
        val password = "Integration_" + UUID.randomUUID().toString().take(12)
        runBlocking { AuthApi().register(username, password) }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("login_username").performTextInput(username)
        compose.onNodeWithTag("login_password").performTextInput(password)
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav_CHAT").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_CHAT").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("chat_send") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_send").performClick()
        compose.onNodeWithText("请输入1至2000字的问题。").assertExists()
        compose.onNodeWithTag("chat_question").performTextInput("请查找我以前保存的导数学习资料")
        compose.onNodeWithTag("chat_send").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat_confirm").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("chat_confirm").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_confirm").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("chat_send") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_confirm").assertDoesNotExist()
        val app = compose.activity.application as FragPickerApplication
        val api = JsonApi(app.authRepository, runBlocking { app.authRepository.restore()!!.id })
        val sessions = runBlocking { api.request("GET", "/api/v1/chat/sessions?limit=20").getJSONArray("items") }
        assertEquals(1, sessions.length())
        val sessionId = sessions.getJSONObject(0).getLong("sessionId")
        val turns = runBlocking { api.request("GET", "/api/v1/chat/sessions/$sessionId/messages?limit=10").getJSONArray("items") }
        assertEquals(1, turns.length()); assertEquals("CHAT_DISABLED", turns.getJSONObject(0).getString("errorCode"))
        compose.onNodeWithContentDescription("历史对话").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("session_$sessionId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("session_$sessionId").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("chat_turn_${turns.getJSONObject(0).getLong("turnId")}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("新对话").performClick()
        compose.onNodeWithText("和记忆聊一聊。").assertExists()
    }
}
