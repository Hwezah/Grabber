package com.hwezah.grabber

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A server-side resolver (the Cobalt API) for social videos: the post's public address goes to a helper that fetches
 * it on its own servers and hands back a direct file link, which Grabber then downloads with its own connections.
 * Instagram and the like bounce phones (and VPNs) to a login page; the helper isn't a phone, so no login is needed.
 * Only the post's address leaves the phone: no cookies, nothing of the user's. The helper is set in Settings;
 * cleared, it's off and yt-dlp does it all.
 */
object Resolver {
    /** A community Cobalt instance. Public ones come and go, so it can be changed in Settings. */
    const val DEFAULT = "https://cobalt-api.kwiatekmiki.com"

    /** Not for YouTube (yt-dlp handles it well and offers every quality). */
    private val SKIP = listOf("youtube.com", "youtu.be", "music.youtube.com")

    data class Found(val url: String, val filename: String?)

    fun wanted(url: String): Boolean {
        if (Prefs.resolver.isBlank() || !Media.looksLikeSite(url)) return false
        val host = runCatching { android.net.Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
        return SKIP.none { host == it || host.endsWith(".$it") }
    }

    /** Blocking: call off the main thread. A direct link for [page] at about [height], or null (never throws). */
    fun resolve(page: String, height: Int?): Found? = runCatching {
        val base = Prefs.resolver.trim().ifBlank { return null }
        val body = JSONObject().put("url", page).put("downloadMode", "auto")
            .apply { if (height != null && height > 0) put("videoQuality", height.toString()) }.toString()
        val conn = (URL(base).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (text.isBlank()) return null
        val o = JSONObject(text)
        when (o.optString("status")) {
            "tunnel", "redirect", "stream" -> o.optString("url").takeIf { it.isNotBlank() }?.let { Found(it, o.optString("filename").ifBlank { null }) }
            // A carousel: its first video.
            "picker" -> {
                val arr = o.optJSONArray("picker") ?: return null
                val items = (0 until arr.length()).map { arr.getJSONObject(it) }
                (items.firstOrNull { it.optString("type") == "video" } ?: items.firstOrNull())
                    ?.optString("url")?.takeIf { it.isNotBlank() }?.let { Found(it, null) }
            }
            else -> null
        }
    }.getOrNull()
}
