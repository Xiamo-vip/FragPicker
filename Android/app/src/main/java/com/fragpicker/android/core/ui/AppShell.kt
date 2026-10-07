package com.fragpicker.android.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.fragpicker.android.core.auth.UserProfile
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens

enum class Destination(val label: String, val icon: ImageVector) {
    FEED("投喂", Icons.Rounded.AddLink), HISTORY("回顾", Icons.Rounded.CalendarMonth),
    CHAT("对话", Icons.AutoMirrored.Rounded.Chat), SETTINGS("设置", Icons.Rounded.Settings)
}

@Composable
fun AppShell(user: UserProfile, settings: @Composable () -> Unit,
             feed: @Composable () -> Unit = { Overview("投喂", "欢迎回来，${user.username}", "让值得记住的内容，在这里沉淀。") },
             history: @Composable () -> Unit = { Overview("回顾", "翻阅你的知识日历", "按日期找回曾经收藏的灵感。") },
             chat: @Composable () -> Unit = { Overview("对话", "和记忆聊一聊", "描述你要找的内容，连接零散的知识。") }) {
    var destination by rememberSaveable { mutableStateOf(Destination.FEED) }
    val holder = rememberSaveableStateHolder()
    val store = remember(user.id) { ViewModelStore() }
    val owner = remember(store) { object : ViewModelStoreOwner { override val viewModelStore = store } }
    DisposableEffect(store) { onDispose { store.clear() } }
    val backdrop = rememberLayerBackdrop()
    val colors = MaterialTheme.colorScheme
    BackHandler(enabled = destination != Destination.FEED) { destination = Destination.FEED }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(
                Brush.verticalGradient(listOf(colors.surface, colors.primaryContainer.copy(alpha = .5f), colors.surface)))) {
                Crossfade(destination, label = "page") { page ->
                    holder.SaveableStateProvider(page.name) {
                        when (page) {
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

@Composable
private fun GlassNavigation(selected: Destination, select: (Destination) -> Unit, backdrop: Backdrop, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val position by animateFloatAsState(selected.ordinal.toFloat(), spring(dampingRatio = .78f), label = "glass selection")
    BoxWithConstraints(modifier.widthIn(max = 560.dp).fillMaxWidth().height(72.dp)) {
        val itemWidth = maxWidth / Destination.entries.size
        val tint = colors.surface.copy(alpha = .78f)
        Box(Modifier.fillMaxSize().drawBackdrop(backdrop, shape = { RoundedCornerShape(36.dp) },
            effects = { blur(10.dp.toPx()); lens(18.dp.toPx(), 28.dp.toPx()) },
            onDrawSurface = { drawRect(tint) }))
        Box(Modifier.padding(5.dp).offset(x = itemWidth * position)
            .width(itemWidth - 10.dp).height(62.dp).drawBackdrop(backdrop,
                shape = { RoundedCornerShape(32.dp) }, effects = { blur(4.dp.toPx()); lens(14.dp.toPx(), 22.dp.toPx()) },
                onDrawSurface = { drawRect(colors.primaryContainer.copy(alpha = .7f)) }))
        Row(Modifier.fillMaxSize()) {
            Destination.entries.forEach { destination ->
                Column(Modifier.weight(1f).fillMaxHeight().testTag("nav_${destination.name}")
                    .semantics { contentDescription = destination.label }
                    .selectable(selected == destination, role = Role.Tab, onClick = { select(destination) }),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    val color = if (selected == destination) colors.onPrimaryContainer else colors.onSurfaceVariant
                    Icon(destination.icon, null, tint = color)
                    Spacer(Modifier.height(4.dp))
                    Text(destination.label, style = MaterialTheme.typography.labelMedium, color = color)
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
