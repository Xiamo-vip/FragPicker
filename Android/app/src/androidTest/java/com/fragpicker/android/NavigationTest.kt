package com.fragpicker.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateOf
import com.fragpicker.android.core.auth.UserProfile
import com.fragpicker.android.core.theme.*
import com.fragpicker.android.core.ui.AppShell
import com.fragpicker.android.feature.auth.LoginUiState
import com.fragpicker.android.feature.settings.SettingsScreen
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun switchesGlassTabsAndOpensSettings() {
        val user = UserProfile(1, "navigation", "Asia/Shanghai")
        val mode = mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { FragmentsPickerTheme(mode.value) {
            AppShell(user, settings = { SettingsScreen(mode.value, { mode.value = it }, LoginUiState(false, user), {}) })
        } }
        compose.onNodeWithTag("nav_FEED").assertIsSelected()
        compose.onNodeWithTag("nav_HISTORY").performClick().assertIsSelected()
        compose.onNodeWithText("翻阅你的知识日历").assertIsDisplayed()
        compose.onNodeWithTag("nav_CHAT").performClick().assertIsSelected()
        compose.onNodeWithText("和记忆聊一聊").assertIsDisplayed()
        compose.onNodeWithTag("nav_SETTINGS").performClick().assertIsSelected()
        compose.onNodeWithText("主题样式").assertIsDisplayed()
        compose.onNodeWithText("深色").performClick().assertIsSelected()
        compose.onNodeWithTag("nav_FEED").performClick().assertIsSelected()
        compose.onNodeWithTag("nav_HISTORY").performClick().assertIsSelected()
    }
}
