package com.fragpicker.android.feature.detail

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fragpicker.android.core.network.timestamp
import kotlinx.coroutines.delay

@Composable
fun VideoPlayer(url: String, seekMs: Long, seekVersion: Int, onRefresh: () -> Unit, revision: Int = 0,
                chapters: List<VideoChapter> = emptyList(), knownDurationMs: Long = 0) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    var media by remember { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(url, revision) { mutableStateOf(false) }
    var failed by remember(url, revision) { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by rememberSaveable { mutableLongStateOf(0) }
    var duration by remember(url, revision) { mutableLongStateOf(knownDurationMs) }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var speedMenu by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(true) }
    var playerMessage by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun seek(value: Long) {
        position = value.coerceIn(0, duration.coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong())
        if (prepared) view?.seekTo(position.toInt())
    }
    fun pause() {
        if (prepared) { position = view?.currentPosition?.toLong() ?: position; view?.pause() }
        playing = false
    }
    fun leaveFullscreen() { pause(); fullscreen = false; prepared = false }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) { pause(); foreground = false }
            else if (event == Lifecycle.Event.ON_RESUME) foreground = true
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); view?.stopPlayback() }
    }
    LaunchedEffect(url, revision) { playing = false; playerMessage = null }
    LaunchedEffect(seekVersion) {
        if (seekVersion > 0) {
            // Preserve requested position while media metadata is still loading.
            position = seekMs.coerceIn(0, Int.MAX_VALUE.toLong())
            if (prepared) seek(position)
        }
    }
    LaunchedEffect(playing, prepared, fullscreen) {
        while (playing && prepared && foreground) { position = view?.currentPosition?.toLong() ?: position; delay(250) }
    }
    val content: @Composable () -> Unit = {
        Column(Modifier.then(if (fullscreen) Modifier.fillMaxSize().padding(16.dp) else Modifier.fillMaxWidth()),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            key(url, revision, fullscreen) {
                AndroidView(factory = { context -> VideoView(context).apply {
                    view = this
                    setOnPreparedListener { player ->
                        if (view !== this) return@setOnPreparedListener
                        media = player; prepared = true; failed = false
                        duration = player.duration.toLong().coerceAtLeast(0)
                        position = position.coerceIn(0, duration)
                        seekTo(position.toInt())
                    }
                    setOnCompletionListener { playing = false; position = duration }
                    setOnErrorListener { _, _, _ -> failed = true; prepared = false; playing = false; true }
                    // Signed OSS media receives no backend Authorization header.
                    setVideoURI(Uri.parse(url))
                } }, modifier = Modifier.fillMaxWidth().then(if (fullscreen) Modifier.weight(1f) else Modifier.aspectRatio(16f / 9f))
                    .background(Color.Black).testTag("video_surface"),
                    onRelease = {
                        if (prepared) position = it.currentPosition.toLong()
                        it.stopPlayback()
                        if (view === it) { view = null; media = null }
                    })
            }
            if (failed) Text("视频暂时无法播放，请刷新地址重试。", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("video_error"))
            else if (!prepared) LinearProgressIndicator(Modifier.fillMaxWidth())
            ChapterProgress(position, duration, chapters, prepared, ::seek)
            val current = timelineChapters(chapters, duration).lastOrNull { it.startMs <= position }
            current?.let {
                Text("${timestamp(it.startMs)}  ${it.text}", style = MaterialTheme.typography.labelLarge, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("video_current_chapter"))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(enabled = prepared, onClick = { seek(position - 10_000) }, modifier = Modifier.testTag("video_rewind")) { Icon(Icons.Rounded.Replay10, "后退10秒") }
                FilledTonalIconButton(enabled = prepared && foreground, onClick = {
                    if (playing) pause() else {
                        if (position >= duration) seek(0)
                        runCatching { media?.playbackParams = PlaybackParams().setSpeed(speed) }.onFailure { speed = 1f }
                        view?.start(); playing = true
                    }
                }, modifier = Modifier.testTag("video_toggle")) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暂停" else "播放") }
                IconButton(enabled = prepared, onClick = { seek(position + 10_000) }, modifier = Modifier.testTag("video_forward")) { Icon(Icons.Rounded.Forward10, "前进10秒") }
                Box {
                    TextButton(enabled = prepared, onClick = { speedMenu = true }, modifier = Modifier.testTag("video_speed")) { Text("${speed}×") }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEach { option -> DropdownMenuItem(text = { Text("${option}×") }, onClick = {
                            speedMenu = false
                            runCatching {
                                media?.playbackParams = PlaybackParams().setSpeed(option)
                                if (!playing) view?.pause()
                            }.onSuccess { speed = option; playerMessage = null }.onFailure { playerMessage = "此视频暂不支持该倍速。" }
                        }, modifier = Modifier.testTag("video_speed_$option")) }
                    }
                }
                IconButton(onClick = { if (fullscreen) leaveFullscreen() else { pause(); fullscreen = true; prepared = false } }, modifier = Modifier.testTag("video_fullscreen")) {
                    Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (fullscreen) "退出全屏" else "全屏")
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${timestamp(position)} / ${timestamp(duration)}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("video_time"))
                TextButton(onClick = onRefresh) { Text("刷新播放地址") }
            }
            playerMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
    if (fullscreen) Dialog(onDismissRequest = ::leaveFullscreen, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("video_fullscreen_panel"), color = MaterialTheme.colorScheme.surface) { content() }
    } else content()
}