package com.fragpicker.android

import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.fragpicker.android.core.theme.*
import com.fragpicker.android.core.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MarkdownRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun find(view: View): TextView? {
        if (view is TextView && view.tag == "fragpicker_markdown") return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
        return null
    }
    @Test fun rendersActualNativeHeadingListsCodeCitationAndSelectableText() {
        compose.setContent { FragmentsPickerTheme(ThemeMode.LIGHT) {
            MarkdownText("## 学习建议\n\n**先掌握极限。**\n\n- 理解变化率 [资料42]\n- 再练习求导\n\n> 每次只记一个重点。\n\n```kotlin\nval rate = 2\n```", Modifier.padding(20.dp).testTag("markdown"))
        } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("markdown") and hasText("学习建议", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            val view = find(compose.activity.window.decorView)!!
            val text = view.text as Spanned
            assertTrue(view.isTextSelectable)
            assertFalse(text.toString().contains("**")); assertFalse(text.toString().contains("```"))
            assertTrue(text.toString().contains("[资料42]")); assertTrue(text.toString().contains("val rate = 2"))
            assertTrue("Rich native spans exist", text.getSpans(0, text.length, Any::class.java).size >= 6)
        }
    }
    @Test fun partialStreamCompletesAndThemeUpdatesWithoutLosingLatestText() {
        val source = mutableStateOf("**导")
        val mode = mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { FragmentsPickerTheme(mode.value) { MarkdownText(source.value, Modifier.testTag("markdown")) } }
        compose.runOnIdle { source.value = "**导数**表示变化率。\n\n1. 先学极限\n2. 再看切线"; mode.value = ThemeMode.DARK }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("markdown") and hasText("再看切线", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            val view = find(compose.activity.window.decorView)!!
            assertTrue(android.graphics.Color.red(view.currentTextColor) > 180)
            assertTrue(view.text.toString().startsWith("导数表示变化率。"))
        }
    }
    @Test fun linksAcceptWebDestinationsAndRejectExecutableOrLocalSchemes() {
        assertEquals("https://example.com/learn", markdownWebLink("https://example.com/learn"))
        listOf("javascript:alert(1)", "file:///sdcard/private", "intent://example", "https://user:password@example.com", "invalid").forEach {
            assertNull(markdownWebLink(it))
        }
    }
}
