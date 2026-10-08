package com.fragpicker.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.fragpicker.android.core.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.hypot

class ThemeRevealTest {
    @get:Rule val compose = createComposeRule()

    @Test fun revealsFromControlThenRetractsOnSamePathWithoutRemountingPage() {
        compose.mainClock.autoAdvance = false
        val mode = mutableStateOf(ThemeMode.LIGHT)
        var mounts = 0
        compose.setContent { FragmentsPickerTheme(mode.value) {
            DisposableEffect(Unit) { mounts++; onDispose { } }
            val reveal = LocalThemeReveal.current!!
            var anchor by remember { mutableStateOf(Offset.Zero) }
            val dark = LocalDarkTheme.current
            Box(Modifier.fillMaxSize().background(if (dark) Color.Black else Color.White)) {
                Button(onClick = { reveal.change(if (dark) ThemeMode.LIGHT else ThemeMode.DARK, anchor) { mode.value = it } },
                    enabled = !reveal.busy, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp).size(56.dp)
                        .onGloballyPositioned { anchor = it.boundsInWindow().center }.testTag("reveal_control")) { Text("切") }
            }
        } }
        compose.mainClock.advanceTimeBy(64)
        val origin = compose.onNodeWithTag("reveal_control").fetchSemanticsNode().boundsInRoot.center
        val root = compose.onNodeWithTag("theme_host").fetchSemanticsNode().boundsInRoot
        val nearY = (origin.y + hypot(root.width, root.height) * .08f).toInt().coerceAtMost(root.height.toInt() - 2)
        compose.onNodeWithTag("reveal_control").performClick()
        awaitOverlay()
        compose.mainClock.advanceTimeBy(200)
        val expanding = compose.onNodeWithTag("theme_host").captureToImage().toPixelMap()
        assertTrue("New dark content is revealed near control", expanding[origin.x.toInt(), nearY].red < .2f)
        assertTrue("Far corner retains previous light content", expanding[2, 2].red > .8f)
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("theme_reveal").assertDoesNotExist()
        compose.onNodeWithTag("reveal_control").performClick()
        awaitOverlay()
        compose.mainClock.advanceTimeBy(200)
        val retracting = compose.onNodeWithTag("theme_host").captureToImage().toPixelMap()
        assertTrue("Old dark content retracts toward control", retracting[origin.x.toInt(), nearY].red < .2f)
        assertTrue("Light theme is restored outside circle", retracting[2, 2].red > .8f)
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("theme_reveal").assertDoesNotExist()
        assertEquals(ThemeMode.LIGHT, mode.value)
        assertEquals("Page is never duplicated or remounted", 1, mounts)
    }

    @Test fun sameVisualThemeChangesPreferenceWithoutMask() {
        var selected: ThemeMode? = null
        compose.setContent { FragmentsPickerTheme(ThemeMode.LIGHT) {
            val reveal = LocalThemeReveal.current!!
            Button(onClick = { reveal.change(ThemeMode.LIGHT, Offset.Zero) { selected = it } }) { Text("保留浅色") }
        } }
        compose.onNodeWithText("保留浅色").performClick()
        compose.runOnIdle { assertEquals(ThemeMode.LIGHT, selected) }
        compose.onNodeWithTag("theme_reveal").assertDoesNotExist()
    }

    private fun awaitOverlay() {
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag("theme_reveal").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
