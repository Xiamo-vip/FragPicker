package com.fragpicker.android.feature.clipboard

import android.content.ClipData
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

data class ClipboardCandidate(val url: String, val fingerprint: String) {
    override fun toString() = "ClipboardCandidate[REDACTED]"
}

/** Plain text only; never coerce content URIs or send captions to the server. */
object ClipboardLinks {
    private val pattern = Regex("https?://[^\\s<>\\\"“”]+", RegexOption.IGNORE_CASE)
    // Mirror the server's default supported platforms; custom deployments can still use manual feeding.
    private val shareHosts = setOf(
        "www.acfun.cn", "tv.cctv.cn", "tv.cctv.com", "doupai.cc", "v.douyin.com", "www.iesdouyin.com",
        "www.douyin.com", "haokan.baidu.com", "haokan.hao123.com", "www.bilibili.com", "b23.tv", "m.bilibili.com",
        "v.huya.com", "v.kuaishou.com", "www.pearvideo.com", "weibo.cn", "meipai.com", "h5.pipigx.com",
        "h5.pipix.com", "xspshare.baidu.com", "kg.qq.com", "6.cn", "tv.sohu.com", "my.tv.sohu.com", "weibo.com",
        "isee.weishi.qq.com", "v.ixigua.com", "www.ixigua.com", "xinpianchang.com", "share.xiaochuankeji.cn",
        "www.xiaohongshu.com", "xhslink.com", "xhslink.cn", "twitter.com", "x.com", "t.co", "mobile.twitter.com",
        "v.qq.com", "m.v.qq.com",
    )
    private const val punctuation = "。；，！、)）]】}〉》.,;!"

    fun fromClip(clip: ClipData?): ClipboardCandidate? {
        if (clip == null || clip.itemCount != 1 ||
            clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return null
        val text = clip.getItemAt(0).text ?: return null
        if (text.length > 4096) return null
        val link = extract(text.toString()) ?: return null
        if (URI(link).host !in shareHosts) return null
        return ClipboardCandidate(link, digest("${clip.description.timestamp}:$link"))
    }

    fun extract(text: String): String? = runCatching {
        if (text.length > 4096) return null
        val matches = pattern.findAll(text).take(2).toList()
        if (matches.size != 1) return null
        val value = matches.single().value.trimEnd { it in punctuation }
        val uri = URI(value)
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in listOf("http", "https") || uri.userInfo != null ||
            uri.port !in listOf(-1, if (scheme == "https") 443 else 80)) return null
        val path = uri.rawPath.ifEmpty { "/" }
        URI("$scheme://$host$path" + (uri.rawQuery?.let { "?$it" } ?: "")).toASCIIString()
    }.getOrNull()

    internal fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}