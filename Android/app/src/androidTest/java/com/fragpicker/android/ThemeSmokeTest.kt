package com.fragpicker.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import com.fragpicker.android.core.theme.ThemeRepository
import com.fragpicker.android.core.theme.ThemeMode
import kotlinx.coroutines.runBlocking

class ThemeSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun switchesAppearanceAndRestoresItAfterRecreation() {
        runBlocking { ThemeRepository(compose.activity).setMode(ThemeMode.LIGHT) }
        compose.onNodeWithText("FragmentsPicker").assertIsDisplayed()
        compose.onNodeWithContentDescription("设置").performClick()
        waitForSwitch(false)
        compose.onNodeWithTag("theme_switch").performScrollTo().performClick()
        waitForSwitch(true)
        compose.activityRule.scenario.recreate()
        waitForSwitch(true)
        compose.onNodeWithTag("theme_switch").performScrollTo().performClick()
        waitForSwitch(false)
        compose.onNodeWithTag("theme_system").performScrollTo().performClick()
        waitForSelected("跟随系统")
    }
    private fun waitForSwitch(on: Boolean) {
        compose.waitUntil(10_000) { runCatching {
            val node = compose.onNodeWithTag("theme_switch").assertIsEnabled()
            if (on) node.assertIsOn() else node.assertIsOff()
        }.isSuccess }
    }

    private fun waitForSelected(label: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(label) and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
