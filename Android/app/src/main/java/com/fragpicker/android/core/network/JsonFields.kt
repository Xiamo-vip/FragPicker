package com.fragpicker.android.core.network

import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
fun JSONObject.optionalObject(key: String): JSONObject? = if (isNull(key)) null else optJSONObject(key)
fun JSONObject.optionalLong(key: String): Long? = if (isNull(key)) null else optLong(key).takeIf { it > 0 }
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun categoryLabel(code: String) = when (code) {
    "LEARNING" -> "学习"; "TECHNOLOGY" -> "科技"; "LIFESTYLE" -> "生活"; "HEALTH" -> "健康"
    "FINANCE" -> "财经"; "ART" -> "艺术"; "ENTERTAINMENT" -> "娱乐"; else -> "其他"
}
fun timestamp(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}
