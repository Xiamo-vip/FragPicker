package com.fragpicker.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.auth.AuthApi
import com.fragpicker.android.core.auth.SessionVault
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class RegistrationIntegrationTest {
    @org.junit.After fun cleanup() = cleanupRealSession()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun validatesConfirmationHandlesDuplicateAndLogsInAfterRegistration() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realBackend") == "true")
        val existing = "taken_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val username = "new_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val password = "Integration_" + UUID.randomUUID().toString().take(12)
        runBlocking { AuthApi().register(existing, password) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("创建账号").performScrollTo().performClick()
            compose.onNodeWithTag("register_username").performTextInput(existing.uppercase())
            compose.onNodeWithTag("register_password").performTextInput(password)
            compose.onNodeWithTag("register_confirmation").performTextInput("Different_Password")
            compose.onNodeWithTag("register_submit").performScrollTo().performClick()
            compose.onNodeWithText("两次输入的密码不一致。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("register_confirmation").performTextReplacement(password)
            compose.onNodeWithTag("register_submit").performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("这个用户名已被使用，请换一个。").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("register_username").performTextReplacement(username)
            compose.onNodeWithTag("register_password").performTextInput(password)
            compose.onNodeWithTag("register_confirmation").performTextInput(password)
            compose.onNodeWithTag("register_submit").performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("账号已创建").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("前往登录").performScrollTo().performClick()
            compose.onNodeWithTag("login_username").assertTextContains(username)
            compose.onNodeWithTag("login_password").performTextInput(password)
            compose.onNodeWithTag("login_submit").performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("欢迎回来，$username").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("欢迎回来，$username").assertIsDisplayed()
        } finally { SessionVault(context).clear() }
    }
}
