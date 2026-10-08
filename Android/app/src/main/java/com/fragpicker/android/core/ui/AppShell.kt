package com.fragpicker.android.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.fragpicker.android.core.auth.UserProfile
import com.fragpicker.android.core.theme.appBackground
import com.fragpicker.android.core.ui.liquid.LiquidBottomTabs
import com.fragpicker.android.core.ui.liquid.LiquidBottomTab
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

enum class Destination(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Rounded.Home), FEED("投喂", Icons.Rounded.AddLink), HISTORY("回顾", Icons.Rounded.CalendarMonth),
    CHAT("对话", Icons.AutoMirrored.Rounded.Chat), SETTINGS("设置", Icons.Rounded.Settings)
}

val LocalOpenDestination = staticCompositionLocalOf<(Destination) -> Unit> { {} }
val LocalOpenFragment = staticCompositionLocalOf<(Long) -> Unit> { {} }
val LocalFragmentDeleted = staticCompositionLocalOf<() -> Unit> { {} }
val LocalNavigationInset = staticCompositionLocalOf { 0.dp }

@Composable
fun AppShell(user: UserProfile, settings: @Composable () -> Unit,
             openFeedRequest: String? = null,
             detail: (@Composable (Long, () -> Unit) -> Unit)? = null,
             home: @Composable () -> Unit = { Overview("首页", "今天，拾起了什么？", "欢迎回来，${user.username}") },
             feed: @Composable () -> Unit = { Overview("投喂", "欢迎回来，${user.username}", "让值得记住的内容，在这里沉淀。") },
             history: @Composable () -> Unit = { Overview("回顾", "翻阅你的知识日历", "按日期找回曾经收藏的灵感。") },
             chat: @Composable () -> Unit = { Overview("对话", "和记忆聊一聊", "描述你要找的内容，连接零散的知识。") }) {
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(openFeedRequest) { if (openFeedRequest != null) { detailId = null; destination = Destination.FEED } }
    val holder = rememberSaveableStateHolder()
    val store = remember(user.id) { ViewModelStore() }
    val owner = remember(store) { object : ViewModelStoreOwner { override val viewModelStore = store } }
    DisposableEffect(store) { onDispose { store.clear() } }
    val backdrop = rememberLayerBackdrop()
    BackHandler(enabled = detailId != null || destination != Destination.HOME) {
        if (detailId != null) detailId = null else destination = Destination.HOME
    }
    CompositionLocalProvider(LocalOpenDestination provides { detailId = null; destination = it }, LocalViewModelStoreOwner provides owner, LocalOpenFragment provides { detailId = it },
        LocalFragmentDeleted provides { detailId = null; destination = Destination.HISTORY },
        LocalNavigationInset provides if (WindowInsets.ime.getBottom(LocalDensity.current) == 0) 100.dp else 0.dp) {
        if (detailId != null && detail != null) {
            detail(detailId!!) { detailId = null }
        } else {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).appBackground()) {
                Crossfade(destination, label = "page") { page ->
                    holder.SaveableStateProvider(page.name) {
                        when (page) {
                            Destination.HOME -> home()
                            Destination.FEED -> feed()
                            Destination.HISTORY -> history()
                            Destination.CHAT -> chat()
                            Destination.SETTINGS -> settings()
                        }
                    }
                }
            }
            if (WindowInsets.ime.getBottom(LocalDensity.current) == 0) {
                GlassNavigation(destination, { destination = it }, backdrop,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
        }
    }
}

@Composable
private fun GlassNavigation(selected: Destination, select: (Destination) -> Unit, backdrop: Backdrop, modifier: Modifier) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        LiquidBottomTabs(selectedTabIndex = { selected.ordinal }, onTabSelected = { select(Destination.entries[it]) },
            backdrop = backdrop, tabsCount = Destination.entries.size,
            modifier = modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("liquid_tabs")) {
            Destination.entries.forEach { destination ->
                LiquidBottomTab(onClick = { select(destination) }, modifier = Modifier.testTag("nav_${destination.name}")
                    .semantics { contentDescription = destination.label; this.selected = selected == destination }) {
                    Icon(destination.icon, null, modifier = Modifier.size(26.dp))
                    Text(destination.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Overview(title: String, headline: String, subtitle: String) {
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text(title) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(headline, style = MaterialTheme.typography.headlineMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(100.dp))
        }
    }
}
