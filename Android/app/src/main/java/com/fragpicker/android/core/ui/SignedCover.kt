package com.fragpicker.android.core.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.fragpicker.android.core.network.JsonApi
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val coverSlots = Semaphore(4)

@Composable
fun SignedCover(api: JsonApi, id: Long, available: Boolean, modifier: Modifier = Modifier) {
    var bitmap by remember(api.userId, id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(api.userId, id, available) {
        if (available) try { coverSlots.withPermit {
            val media = api.request("GET", "/api/v1/fragments/$id/media?kind=COVER")
            bitmap = loadCover(media.getString("url"))
        } } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { bitmap = null }
    }
    Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, "视频封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Rounded.PlayCircle, "视频", tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

private suspend fun loadCover(url: String): ImageBitmap = suspendCancellableCoroutine { continuation ->
    val uri = URI(url)
    require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
    val connection = uri.toURL().openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = false
    connection.connectTimeout = 15_000; connection.readTimeout = 15_000
    continuation.invokeOnCancellation { connection.disconnect() }
    Dispatchers.IO.asExecutor().execute {
        try {
            if (!continuation.isActive) return@execute
            require(connection.responseCode == 200)
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break
                    require(output.size() + n <= 8 * 1024 * 1024); output.write(buffer, 0, n) }
                output.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768)
            var sample = 1
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
            val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("Invalid image")
            continuation.resume(image.asImageBitmap())
        } catch (failure: Exception) { continuation.resumeWithException(failure) }
        finally { connection.disconnect() }
    }
}
