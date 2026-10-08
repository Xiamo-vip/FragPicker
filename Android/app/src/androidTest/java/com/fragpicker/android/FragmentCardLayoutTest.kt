package com.fragpicker.android

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.network.JsonApi
import com.fragpicker.android.core.theme.*
import com.fragpicker.android.core.ui.FragmentCard
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FragmentCardLayoutTest {
    @get:Rule val compose = createComposeRule()
    private fun api(): JsonApi {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FragPickerApplication
        return JsonApi(app.authRepository, 1)
    }
    @Test fun legacyLongCopyIsBoundedAndWholeCardOpensDetail() {
        var opened = 0L
        val item = JSONObject().put("fragmentId", 42).put("title", "很长的视频标题".repeat(100))
            .put("summary", "需要在详情慢慢阅读的内容。".repeat(200)).put("businessDate", "2026-10-08")
        compose.setContent { FragmentsPickerTheme(ThemeMode.LIGHT) {
            Box(Modifier.width(340.dp)) { FragmentCard(api(), item, { opened = it }) }
        } }
        val title = compose.onNodeWithTag("fragment_title_42", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val intro = compose.onNodeWithTag("fragment_intro_42", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("Long titles occupy at most two lines", title.height / density < 60)
        assertTrue("Long introductions occupy at most three lines", intro.height / density < 80)
        compose.onNodeWithTag("fragment_42").performClick()
        compose.runOnIdle { assertEquals(42L, opened) }
    }
    @Test fun conciseCopyWinsAndLargeFontDoesNotClipActions() {
        val item = JSONObject().put("fragmentId", 7).put("title", "旧标题").put("summary", "完整摘要")
            .put("displayTitle", "用切线理解导数").put("introduction", "从切线斜率掌握变化率。")
        compose.setContent { FragmentsPickerTheme(ThemeMode.DARK) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                FragmentCard(api(), item, {})
            }
        } }
        compose.onNodeWithText("用切线理解导数", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("从切线斜率掌握变化率。", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("查看内容与视频 →", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("旧标题", useUnmergedTree = true).assertDoesNotExist()
    }
}
