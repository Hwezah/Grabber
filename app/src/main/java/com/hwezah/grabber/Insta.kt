package com.hwezah.grabber

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import org.json.JSONObject
import java.math.BigInteger

/**
 * A backup for Instagram when yt-dlp can't read a post: asks Instagram's own app API for it, with the
 * signed-in browser's cookies, and returns the video's direct link.
 */
object Insta {
    data class Found(val url: String, val title: String)

    private const val ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** The post's code: /reel/CODE, /p/CODE, /tv/CODE. */
    fun code(url: String): String? =
        Regex("""instagram\.com/(?:[^/]+/)?(?:reels?|p|tv)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)

    private fun mediaId(code: String): String {
        var n = BigInteger.ZERO
        val sixtyFour = BigInteger.valueOf(64)
        code.take(11).forEach { c -> n = n.multiply(sixtyFour).add(BigInteger.valueOf(ABC.indexOf(c).coerceAtLeast(0).toLong())) }
        return n.toString()
    }

    /** Blocking: call off the main thread. Null when Instagram wouldn't say. */
    fun find(context: Context, url: String): Found? = runCatching {
        val code = code(url) ?: return null
        val cookies = CookieManager.getInstance().let { it.getCookie("https://www.instagram.com") ?: it.getCookie("https://instagram.com") }
            ?: return null
        val csrf = Regex("""csrftoken=([^;]+)""").find(cookies)?.groupValues?.get(1).orEmpty()
        val req = okhttp3.Request.Builder()
            .url("https://www.instagram.com/api/v1/media/${mediaId(code)}/info/")
            .header("User-Agent", WebSettings.getDefaultUserAgent(context))
            .header("X-IG-App-ID", "936619743392459")
            .header("X-CSRFToken", csrf)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "https://www.instagram.com/")
            .header("Cookie", cookies)
            .build()
        val body = com.hwezah.grabber.engine.Http.client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return null
        val item = JSONObject(body).getJSONArray("items").getJSONObject(0)
        // A single video, or the first video of a carousel.
        val videos = item.optJSONArray("video_versions")
            ?: item.optJSONArray("carousel_media")?.let { c -> (0 until c.length()).firstNotNullOfOrNull { c.getJSONObject(it).optJSONArray("video_versions") } }
            ?: return null
        val best = (0 until videos.length()).map { videos.getJSONObject(it) }.maxByOrNull { it.optInt("width") * it.optInt("height") } ?: return null
        val user = item.optJSONObject("user")?.optString("username").orEmpty()
        val caption = item.optJSONObject("caption")?.optString("text").orEmpty().lineSequence().firstOrNull().orEmpty().take(60)
        Found(best.getString("url"), listOf(user, caption).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Instagram $code" })
    }.getOrNull()
}
