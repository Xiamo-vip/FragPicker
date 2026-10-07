package com.fragpicker.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.auth.AuthApi
import com.fragpicker.android.core.auth.AuthApiFailure
import com.fragpicker.android.core.auth.SessionVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class LogoutIntegrationTest {
    @org.junit.After fun cleanup() = cleanupRealSession()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun revokesOtherDeviceSessionAndClearsLocalCredentials() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realBackend") == "true")
        val username = "logout_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val password = "Integration_" + UUID.randomUUID().toString().take(12)
        val api = AuthApi()
        val otherDevice = runBlocking { api.register(username, password); api.login(username, password) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("login_username").performTextInput(username)
            compose.onNodeWithTag("login_password").performTextInput(password)
            compose.onNodeWithTag("login_submit").performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("欢迎回来，$username").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithText("退出登录").performScrollTo().performClick()
            compose.onNodeWithText("确认退出").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            assertNull(SessionVault(context).consume())
            val error = assertThrows(AuthApiFailure::class.java) { runBlocking { api.currentUser(otherDevice.accessToken) } }
            assertEquals(401, error.status)
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        } finally { SessionVault(context).clear() }
    }
}
