package com.fragpicker.android.core.ui

import android.text.Spanned
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.*
import io.noties.markwon.core.MarkwonTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

internal fun markdownWebLink(value: String): String? = runCatching {
    val uri = URI(value)
    value.takeIf { uri.scheme?.lowercase() in listOf("http", "https") && uri.host != null && uri.userInfo == null }
}.getOrNull()

/** Native selectable Markdown with no HTML execution or automatic remote image loading. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyMedium
    val fontPx = with(density) { style.fontSize.toPx() }
    val linePx = with(density) { style.lineHeight.toPx() }
    val indent = with(density) { 20.dp.roundToPx() }
    val uriHandler by rememberUpdatedState(LocalUriHandler.current)
    val markwon = remember(context, colors, indent) {
        Markwon.builder(context).usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.linkColor(colors.primary.toArgb()).blockQuoteColor(colors.primary.toArgb())
                    .codeTextColor(colors.onSurface.toArgb()).codeBackgroundColor(colors.surfaceContainerHigh.toArgb())
                    .codeBlockTextColor(colors.onSurface.toArgb()).codeBlockBackgroundColor(colors.surfaceContainerHigh.toArgb())
                    .headingBreakHeight(0).headingTextSizeMultipliers(floatArrayOf(1.35f, 1.2f, 1.1f, 1f, 1f, 1f))
                    .blockMargin(indent)
            }
            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder.linkResolver { _, link -> markdownWebLink(link)?.let { runCatching { uriHandler.openUri(it) } } }
            }
        }).build()
    }
    // Parse away from the UI thread. Cancellation prevents older stream chunks replacing newer ones.
    val rendered by produceState<Spanned?>(null, markdown, markwon) {
        value = withContext(Dispatchers.Default) { markwon.toMarkdown(markdown) }
    }
    AndroidView(factory = { TextView(it).apply {
        setTextIsSelectable(true); includeFontPadding = false
        tag = "fragpicker_markdown"
    } }, modifier = modifier.fillMaxWidth().semantics { text = AnnotatedString(rendered?.toString() ?: markdown) },
        update = { view ->
            view.setTextColor(colors.onSurface.toArgb()); view.setLinkTextColor(colors.primary.toArgb())
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx)
            view.setLineSpacing((linePx - fontPx).coerceAtLeast(0f) * .55f, 1f)
            rendered?.let { markwon.setParsedMarkdown(view, it) }
        })
}
