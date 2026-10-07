package com.fragpicker.android.feature.detail

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fragpicker.android.core.network.timestamp
import kotlinx.coroutines.delay

@Composable
fun VideoPlayer(url: String, seekMs: Long, seekVersion: Int, onRefresh: () -> Unit, revision: Int = 0) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    var prepared by remember(url, revision) { mutableStateOf(false) }
    var failed by remember(url, revision) { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by rememberSaveable { mutableIntStateOf(0) }
    var duration by remember(url, revision) { mutableIntStateOf(0) }
    var foreground by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                position = view?.currentPosition ?: position; view?.pause(); playing = false; foreground = false
            } else if (event == Lifecycle.Event.ON_RESUME) foreground = true
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); position = view?.currentPosition ?: position; view?.stopPlayback() }
    }
    LaunchedEffect(url, revision) { playing = false }
    LaunchedEffect(seekVersion) {
        if (seekVersion > 0) { position = seekMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(); view?.seekTo(position) }
    }
    LaunchedEffect(playing, prepared) {
        while (playing && prepared) { position = view?.currentPosition ?: position; delay(500) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        key(url, revision) {
            AndroidView(factory = { context -> VideoView(context).apply {
                view = this
                setOnPreparedListener { media ->
                    prepared = true; duration = media.duration.coerceAtLeast(0)
                    seekTo(position.coerceAtMost(duration))
                }
                setOnCompletionListener { playing = false; position = duration }
                setOnErrorListener { _, _, _ -> failed = true; prepared = false; playing = false; true }
                // Signed media URLs receive no backend Authorization header.
                setVideoURI(Uri.parse(url))
            } }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black).testTag("video_surface"),
                onRelease = { it.stopPlayback(); if (view === it) view = null })
        }
        if (failed) Text("视频暂时无法播放，地址可能已过期或网络中断。", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("video_error"))
        else if (!prepared) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FilledTonalIconButton(enabled = prepared && foreground, onClick = {
                if (playing) view?.pause() else { if (position >= duration) { position = 0; view?.seekTo(0) }; view?.start() }
                playing = !playing
            }, modifier = Modifier.testTag("video_toggle")) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暂停" else "播放")
            }
            Text("${timestamp(position.toLong())} / ${timestamp(duration.toLong())}", Modifier.padding(top = 12.dp))
            TextButton(onClick = onRefresh) { Text("刷新播放地址") }
        }
        Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()), onValueChange = {
            position = it.toInt(); view?.seekTo(position)
        }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = prepared, modifier = Modifier.testTag("video_seek"))
    }
}
