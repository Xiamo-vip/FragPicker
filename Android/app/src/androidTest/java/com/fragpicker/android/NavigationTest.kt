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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry

class NavigationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun switchesGlassTabsAndOpensSettings() {
        val user = UserProfile(1, "navigation", "Asia/Shanghai")
        val mode = mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { FragmentsPickerTheme(mode.value) {
            AppShell(user, settings = { SettingsScreen(mode.value, { mode.value = it }, LoginUiState(false, user), {}) })
        } }
        compose.onNodeWithTag("nav_HOME").assertIsSelected()
        compose.onNodeWithTag("nav_HISTORY").performClick().assertIsSelected()
        compose.onNodeWithText("翻阅你的知识日历").assertIsDisplayed()
        compose.onNodeWithTag("nav_CHAT").performClick().assertIsSelected()
        compose.onNodeWithText("和记忆聊一聊").assertIsDisplayed()
        compose.onNodeWithTag("nav_SETTINGS").performClick().assertIsSelected()
        compose.onNodeWithText("主题样式").assertIsDisplayed()
        compose.onNodeWithText("深色").performClick().assertIsSelected()
        compose.onNodeWithTag("nav_FEED").performClick().assertIsSelected()
        compose.onNodeWithTag("nav_HISTORY").performClick().assertIsSelected()
        savePreview("liquid-dark.png")
    }
    @Test fun dragsTheLiquidIndicatorInBothLayoutDirections() {
        val direction = mutableStateOf(LayoutDirection.Ltr)
        compose.setContent { FragmentsPickerTheme(ThemeMode.LIGHT) {
            CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                AppShell(UserProfile(1, "navigation", "Asia/Shanghai"), settings = {})
            }
        } }
        val first = compose.onNodeWithTag("nav_HOME").fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithTag("nav_CHAT").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { swipe(first.center, third.center, 600) }
        compose.onNodeWithTag("nav_CHAT").assertIsSelected()
        compose.onNodeWithTag("nav_FEED").performClick().assertIsSelected()
        savePreview("liquid-light.png")
        compose.runOnIdle { direction.value = LayoutDirection.Rtl }
        val rtlFirst = compose.onNodeWithTag("nav_FEED").fetchSemanticsNode().boundsInRoot
        val rtlLast = compose.onNodeWithTag("nav_SETTINGS").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { swipe(rtlFirst.center, rtlLast.center, 600) }
        compose.onNodeWithTag("nav_SETTINGS").assertIsSelected()
    }
    private fun savePreview(name: String) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FragPicker-QA")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Unable to save QA screenshot")
        resolver.openOutputStream(uri).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it!!) }
    }
}
