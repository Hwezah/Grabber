package com.hwezah.grabber

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A server-side resolver (the Cobalt API) for social videos: the post's public address goes to a helper that fetches
 * it on its own servers and hands back a direct file link, which Grabber then downloads with its own connections.
 * Instagram and the like bounce phones (and VPNs) to a login page; the helper isn't a phone, so no login is needed.
 * Only the post's address leaves the phone: no cookies, nothing of the user's. Several public helpers are tried in turn;
 * the owner's own can be set in Settings.
 */
object Resolver {
    /**
     * Known community Cobalt instances, tried in order (the Cobalt project no longer runs a public one). These come and
     * go, so the one that worked is remembered and tried first next time. The owner's own instance (Settings) overrides.
     */
    private val INSTANCES = listOf(
        "https://cobalt-api.kwiatekmiki.com",
        "https://cobalt-api.meowing.de",
        "https://cobalt.255x.ru",
        "https://capi.3kh0.net",
        "https://cobalt.cjs.nz",
    )

    /** A real browser's identity: some instances turn away anything else. */
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"

    /** Not for YouTube (yt-dlp handles it well and offers every quality). */
    private val SKIP = listOf("youtube.com", "youtu.be", "music.youtube.com")

    data class Found(val url: String, val filename: String?)

    private fun sp() = Updater.app?.getSharedPreferences("resolver", android.content.Context.MODE_PRIVATE)

    /** The pinned instance alone, else the last that worked followed by the rest. */
    private fun candidates(): List<String> {
        Prefs.resolver.trim().ifBlank { null }?.let { return listOf(it) }
        val ok = sp()?.getString("ok", null)?.ifBlank { null }
        return (listOfNotNull(ok) + INSTANCES).distinct()
    }

    /** Social sites other than YouTube. */
    fun wanted(url: String): Boolean {
        if (!Media.looksLikeSite(url)) return false
        val host = runCatching { android.net.Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
        return SKIP.none { host == it || host.endsWith(".$it") }
    }

    /** Blocking: call off the main thread. A direct link for [page] at about [height] from the first instance that answers, or null. */
    fun resolve(page: String, height: Int?): Found? {
        for (base in candidates()) {
            val r = runCatching { ask(base, page, height) }.getOrNull() ?: continue
            if (Prefs.resolver.isBlank()) runCatching { sp()?.edit()?.putString("ok", base)?.apply() }
            return r
        }
        return null
    }

    /** One instance asked; null when it can't (dead, needs a key, rate-limited, won't resolve). */
    private fun ask(base: String, page: String, height: Int?): Found? {
        val body = JSONObject().put("url", page).put("downloadMode", "auto")
            .apply { if (height != null && height > 0) put("videoQuality", height.toString()) }.toString()
        val conn = (URL(base).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", UA)
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299 || text.isBlank()) return null
        val o = JSONObject(text)
        return when (o.optString("status")) {
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
    }
}
