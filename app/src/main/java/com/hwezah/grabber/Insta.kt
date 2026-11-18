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

    /** Why the last try failed, for the error line. */
    @Volatile var lastWhy: String = ""

    /** Blocking: call off the main thread. Null when Instagram wouldn't say ([lastWhy] says why). */
    fun find(context: Context, url: String): Found? {
        val code = code(url) ?: return null
        lastWhy = ""
        // Inside a hidden browser page on instagram.com: the same session, cookies and fingerprint as signing in.
        viaPage(context, code)?.let { body -> parse(body, code)?.let { return it } }
        // Then a plain request with the same cookies.
        direct(context, code)?.let { body -> parse(body, code)?.let { return it } }
        return null
    }

    private fun parse(body: String, code: String): Found? = runCatching {
        val item = JSONObject(body).getJSONArray("items").getJSONObject(0)
        // A single video, or the first video of a carousel.
        val videos = item.optJSONArray("video_versions")
            ?: item.optJSONArray("carousel_media")?.let { c -> (0 until c.length()).firstNotNullOfOrNull { c.getJSONObject(it).optJSONArray("video_versions") } }
            ?: run { lastWhy = "no video in this post"; return null }
        val best = (0 until videos.length()).map { videos.getJSONObject(it) }.maxByOrNull { it.optInt("width") * it.optInt("height") } ?: return null
        val user = item.optJSONObject("user")?.optString("username").orEmpty()
        val caption = item.optJSONObject("caption")?.optString("text").orEmpty().lineSequence().firstOrNull().orEmpty().take(60)
        Found(best.getString("url"), listOf(user, caption).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Instagram $code" })
    }.getOrElse { lastWhy = "Instagram answered: " + body.take(80).replace('\n', ' '); null }

    private fun direct(context: Context, code: String): String? = runCatching {
        val cookies = CookieManager.getInstance().let { it.getCookie("https://www.instagram.com") ?: it.getCookie("https://instagram.com") }
            ?: run { lastWhy = "not signed in"; return null }
        val csrf = Regex("""csrftoken=([^;]+)""").find(cookies)?.groupValues?.get(1).orEmpty()
        val req = okhttp3.Request.Builder()
            .url("https://www.instagram.com/api/v1/media/${mediaId(code)}/info/")
            .header("User-Agent", WebSettings.getDefaultUserAgent(context))
            .header("X-IG-App-ID", "936619743392459")
            .header("X-CSRFToken", csrf)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "https://www.instagram.com/reel/$code/")
            .header("Cookie", cookies)
            .build()
        com.hwezah.grabber.engine.Http.client.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else { lastWhy = "Instagram answered ${r.code}"; null }
        }
    }.getOrElse { lastWhy = it.message ?: "no answer"; null }

    /** Opens instagram.com in a hidden page and asks from there, as the website itself would. */
    @android.annotation.SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun viaPage(context: Context, code: String): String? {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: String? = null
        var web: android.webkit.WebView? = null
        val id = mediaId(code)
        main.post {
            runCatching {
                val w = android.webkit.WebView(context.applicationContext)
                web = w
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun done(s: String) {
                        val status = s.substringBefore('\n')
                        if (status == "200") out = s.substringAfter('\n') else lastWhy = "Instagram answered $status"
                        latch.countDown()
                    }
                }, "Grab")
                var asked = false
                w.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, u: String) {
                        if (asked) return
                        asked = true
                        view.evaluateJavascript(
                            """(async()=>{try{const c=(document.cookie.match(/csrftoken=([^;]+)/)||[])[1]||'';""" +
                                """const r=await fetch('/api/v1/media/$id/info/',{credentials:'include',headers:{'X-IG-App-ID':'936619743392459','X-Requested-With':'XMLHttpRequest','X-CSRFToken':c}});""" +
                                """Grab.done(r.status+'\n'+await r.text())}catch(e){Grab.done('0\n'+e)}})()""",
                            null,
                        )
                    }
                }
                w.loadUrl("https://www.instagram.com/robots.txt")
            }.onFailure { latch.countDown() }
        }
        latch.await(25, java.util.concurrent.TimeUnit.SECONDS)
        main.post { runCatching { web?.destroy() } }
        return out
    }
}
