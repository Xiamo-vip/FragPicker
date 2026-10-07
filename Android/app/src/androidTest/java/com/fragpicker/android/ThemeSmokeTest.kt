package com.fragpicker.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test

class ThemeSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun switchesAppearanceAndRestoresItAfterRecreation() {
        compose.onNodeWithText("FragmentsPicker").assertIsDisplayed()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("深色").performScrollTo().performClick()
        waitForSelected("深色")
        compose.onNodeWithText("深色").assertIsSelected()
        compose.activityRule.scenario.recreate()
        waitForSelected("深色")
        compose.onNodeWithText("深色").assertIsSelected()
        compose.onNodeWithText("浅色").performScrollTo().performClick()
        waitForSelected("浅色")
        compose.onNodeWithText("浅色").assertIsSelected()
        compose.onNodeWithText("跟随系统").performScrollTo().performClick()
        waitForSelected("跟随系统")
    }

    private fun waitForSelected(label: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(label) and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
