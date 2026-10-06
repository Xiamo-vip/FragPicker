package com.fragpicker.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.auth.AuthApi
import com.fragpicker.android.core.auth.AuthRepository
import com.fragpicker.android.core.auth.SessionVault
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/** Opt in only against the isolated Spring Boot + MySQL harness. No mock server. */
class LoginIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun logsInAgainstRealBackendAndRestoresRotatedSession() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realBackend") == "true")
        val username = "android_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val password = "Integration_Test_" + UUID.randomUUID().toString().take(12)
        val connection = URI(BuildConfig.API_BASE_URL + "/api/v1/auth/register").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(JSONObject().put("username", username).put("password", password)
                .toString().toByteArray(Charsets.UTF_8)) }
            assertEquals(201, connection.responseCode)
            connection.inputStream.close()
        } finally { connection.disconnect() }

        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("login_username") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("login_username").performTextInput(username)
        compose.onNodeWithTag("login_password").performTextInput("Wrong_Password_123")
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("用户名或密码不正确。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("login_password").performTextInput(password)
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("欢迎回来，$username").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("欢迎回来，$username").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("欢迎回来，$username").assertIsDisplayed()

        // A fresh repository represents process restart, reads only the encrypted refresh token,
        // rotates it through the real endpoint and checks the authenticated /users/me response.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val restored = runBlocking { AuthRepository(AuthApi(), SessionVault(context)).restore() }
        assertEquals(username, restored?.username)
        runBlocking {
            val restoredAgain = AuthRepository(AuthApi(), SessionVault(context)).restore()
            assertEquals(restored?.id, restoredAgain?.id)
        }
        SessionVault(context).clear()
    }
}
