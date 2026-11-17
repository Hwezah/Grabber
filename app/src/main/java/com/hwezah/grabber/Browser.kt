package com.hwezah.grabber

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayInputStream

/** Something playable or downloadable the page loaded. */
data class Sniffed(val url: String, val kind: String, val page: String, val title: String)

/** One browser tab: its own page, history and finds. */
class BTab(val id: Long) {
    @SuppressLint("StaticFieldLeak")
    var web: WebView? = null
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var loading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var blocked by mutableIntStateOf(0)
    val found = mutableStateListOf<Sniffed>()
}

/**
 * The browser: tabs, each a WebView kept for the app's life (so a tab switch doesn't reload the page). It watches
 * every request a page makes to find videos, streams, audio and files, and drops requests to ad and tracker networks.
 */
object Browser {
    private val main = Handler(Looper.getMainLooper())

    val tabs = mutableStateListOf(BTab(1))
    var current by mutableIntStateOf(0)
    val tab: BTab get() = tabs[current.coerceIn(0, tabs.lastIndex)]

    // The current tab's state, as the screen reads it.
    val url: String get() = tab.url
    val title: String get() = tab.title
    val loading: Boolean get() = tab.loading
    val progress: Int get() = tab.progress
    val canBack: Boolean get() = tab.canBack
    val canForward: Boolean get() = tab.canForward
    val blocked: Int get() = tab.blocked
    val found: androidx.compose.runtime.snapshots.SnapshotStateList<Sniffed> get() = tab.found
    /** Goes up each time a page scrolls: the tab bar shows while it does. */
    var scrollTick by mutableIntStateOf(0)
    /** A page's video is full screen; [exitFull] leaves it (Back does). */
    var fullScreen by mutableStateOf(false)
    var exitFull: (() -> Unit)? = null

    /** Goes up when a tab's page had to be rebuilt (its renderer crashed): the screen puts the new one in. */
    var rebuilt by mutableIntStateOf(0)

    /** Search engines: name to the address words are added to. */
    val ENGINES = listOf(
        "DuckDuckGo" to "https://duckduckgo.com/?q=",
        "Google" to "https://www.google.com/search?q=",
        "Bing" to "https://www.bing.com/search?q=",
        "Brave" to "https://search.brave.com/search?q=",
        "Startpage" to "https://www.startpage.com/do/search?q=",
    )

    /** Ad and tracker networks: a request to any of these hosts (or their subdomains) gets an empty answer. */
    private val AD_HOSTS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com", "googletagmanager.com",
        "googletagservices.com", "adservice.google.com", "pagead2.googlesyndication.com", "amazon-adsystem.com", "adnxs.com",
        "criteo.com", "criteo.net", "rubiconproject.com", "pubmatic.com", "openx.net", "taboola.com", "outbrain.com",
        "scorecardresearch.com", "quantserve.com", "hotjar.com", "moatads.com", "adsrvr.org", "bidswitch.net", "casalemedia.com",
        "smartadserver.com", "yieldmo.com", "media.net", "zedo.com", "popads.net", "popcash.net", "propellerads.com",
        "propellerclick.com", "exoclick.com", "exosrv.com", "adsterra.com", "adsterratech.com", "hilltopads.net", "juicyads.com",
        "trafficjunky.net", "trafficstars.com", "clickadu.com", "adcash.com", "admaven.com", "ad-maven.com", "a-ads.com",
        "onclickads.net", "onclkds.com", "mgid.com", "revcontent.com", "adskeeper.com", "yandexadexchange.net", "adform.net",
        "teads.tv", "spotxchange.com", "springserve.com", "lijit.com", "sharethrough.com", "33across.com", "indexww.com",
        "3lift.com", "contextweb.com", "advertising.com", "adtech.de", "serving-sys.com", "flashtalking.com", "zemanta.com",
        "facebook.net", "connect.facebook.net", "analytics.tiktok.com", "ads.tiktok.com", "ads-twitter.com", "static.ads-twitter.com",
        "adtrafficquality.google", "fundingchoicesmessages.google.com", "imasdk.googleapis.com", "ads.yahoo.com",
        "bat.bing.com", "clarity.ms", "mc.yandex.ru", "an.yandex.ru", "realsrv.com", "tsyndicate.com", "dtscout.com",
        "acint.net", "bebi.com", "pemsrv.com", "a.realsrv.com", "syndication.realsrv.com", "displayvertising.com",
        "highperformanceformat.com", "profitablegatecpm.com", "effectivegatecpm.com", "whos.amung.us", "histats.com",
    )

    private val MEDIA = Regex("""\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|avi|flv|mp3|m4a|aac|ogg|opus|flac|wav|zip|rar|7z|apk|pdf|torrent)(\?|#|$)""", RegexOption.IGNORE_CASE)

    private fun blockedHost(host: String): Boolean {
        var h = host.lowercase()
        while (true) {
            if (h in AD_HOSTS) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    private fun kindOf(url: String): String? {
        val u = url.lowercase()
        if ("googlevideo.com/videoplayback" in u) return null // YouTube's pieces: "Grab this page" does YouTube.
        val ext = MEDIA.find(u)?.groupValues?.get(1) ?: return null
        return when (ext) {
            "m3u8" -> "Stream (HLS)"
            "mpd" -> "Stream (DASH)"
            "mp4", "m4v", "webm", "mkv", "mov", "avi", "flv" -> "Video"
            "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav" -> "Audio"
            "torrent" -> "Torrent"
            else -> "File"
        }
    }

    private val expanded = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun sniff(t0: BTab, url: String) {
        if (!Prefs.sniffMedia) return
        val kind = kindOf(url) ?: return
        // Pieces of a stream aren't worth listing; the stream itself is.
        if (Regex("""(seg|chunk|frag|part)[-_]?\d+""", RegexOption.IGNORE_CASE).containsMatchIn(url) && kind != "Stream (HLS)") return
        val page = t0.url
        val t = t0.title
        main.post {
            if (t0.found.none { it.url == url }) t0.found.add(0, Sniffed(url, kind, page, t))
        }
        // A stream: read its list of qualities, like 1DM, and offer each one.
        if (kind == "Stream (HLS)" && expanded.add(url)) qualities(t0, Sniffed(url, kind, page, t))
    }

    /** Fetches an HLS master playlist (with the page's headers) and lists each quality it offers, best first. */
    private fun qualities(t0: BTab, s: Sniffed) {
        val found = t0.found
        val headers = headersFor(s)
        kotlin.concurrent.thread(name = "grabber-hls-qualities") {
            val text = runCatching {
                val b = okhttp3.Request.Builder().url(s.url)
                headers.forEach { (k, v) -> b.header(k, v) }
                com.hwezah.grabber.engine.Http.client.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }.getOrNull() ?: return@thread
            val variants = com.hwezah.grabber.engine.Hls.variants(s.url, text)
            if (variants.isEmpty()) return@thread
            main.post {
                val at = found.indexOfFirst { it.url == s.url }.coerceAtLeast(0)
                // The master stays as "best quality"; each quality follows it.
                found.removeAll { it.url == s.url }
                val rows = listOf(s.copy(kind = "Best quality · HLS")) + variants.mapNotNull { v ->
                    if (found.any { it.url == v.url }) found.removeAll { it.url == v.url }
                    val rate = if (v.bandwidth > 0) " · ${"%.1f".format(v.bandwidth / 1_000_000.0)} Mbps" else ""
                    Sniffed(v.url, "${v.label} · HLS$rate", s.page, s.title)
                }
                found.addAll(at.coerceAtMost(found.size), rows)
            }
        }
    }

    /** The headers a download from this page needs: where it came from, the browser's identity, its cookies. */
    fun headersFor(s: Sniffed): Map<String, String> {
        val h = linkedMapOf<String, String>()
        if (s.page.isNotBlank()) h["Referer"] = s.page
        tab.web?.settings?.userAgentString?.let { h["User-Agent"] = it }
        CookieManager.getInstance().getCookie(s.url)?.takeIf { it.isNotBlank() }?.let { h["Cookie"] = it }
        return h
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun view(context: Context, t: BTab = tab): WebView {
        t.web?.let { return it }
        CookieManager.getInstance().setAcceptCookie(true)
        val w = WebView(context)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            // No pop-up windows (most are ads), unless turned off in Settings.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = !Prefs.blockPopups
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            // Runs on a background thread for every request: it must never throw.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = try {
                val u = request.url
                if (Prefs.blockAds && !request.isForMainFrame && blockedHost(u.host.orEmpty())) {
                    main.post { t.blocked++ }
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                } else {
                    sniff(t, u.toString())
                    null
                }
            } catch (e: Throwable) {
                null
            }

            // The page's renderer crashed or was killed for memory: rebuild this tab instead of closing the app.
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                runCatching { (view.parent as? ViewGroup)?.removeView(view) }
                runCatching { view.destroy() }
                if (t.web === view) t.web = null
                t.loading = false
                main.post { rebuilt++ }
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                // Links Grabber handles itself: magnets, and pages that are files.
                if (u.scheme == "magnet") { Capture.offer(u.toString(), t.title); return true }
                // A link straight to a file (video, audio, archive, app, document, torrent): Grabber takes it.
                val k = kindOf(u.toString())
                if (k != null && !k.startsWith("Stream")) {
                    val name = u.lastPathSegment?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                    Capture.offer(u.toString(), name, headersFor(Sniffed(u.toString(), k, t.url, t.title)))
                    return true
                }
                if (u.scheme != "http" && u.scheme != "https") return true
                if (Prefs.blockAds && blockedHost(u.host.orEmpty())) { t.blocked++; return true }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                val u = if (url == "about:blank") "" else url
                if (u != t.url) t.found.clear()
                t.url = u
                t.loading = true
                t.blocked = 0
                nav(t, view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                t.url = if (url == "about:blank") "" else url
                t.loading = false
                t.title = view.title.orEmpty()
                nav(t, view)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                t.url = if (url == "about:blank") "" else url
                nav(t, view)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { t.progress = newProgress }
            override fun onReceivedTitle(view: WebView, title: String?) { t.title = title.orEmpty() }

            // Full screen for a page's video player: its view laid over the whole window, back out with Back.
            private var full: android.view.View? = null
            private var fullDone: CustomViewCallback? = null

            override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
                val activity = w.context as? android.app.Activity ?: return callback.onCustomViewHidden()
                if (full != null) { callback.onCustomViewHidden(); return }
                full = view
                fullDone = callback
                runCatching {
                    (activity.window.decorView as ViewGroup).addView(view, ViewGroup.LayoutParams(-1, -1))
                    view.setBackgroundColor(android.graphics.Color.BLACK)
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
                }
                fullScreen = true
                exitFull = { onHideCustomView() }
            }

            override fun onHideCustomView() {
                val activity = w.context as? android.app.Activity
                full?.let { v -> runCatching { (activity?.window?.decorView as? ViewGroup)?.removeView(v) } }
                full = null
                runCatching { fullDone?.onCustomViewHidden() }
                fullDone = null
                activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                fullScreen = false
                exitFull = null
            }
        }
        w.setOnScrollChangeListener { _, _, y, _, oldY -> if (y != oldY) scrollTick++ }
        // A file the site hands over: straight to Grabber's download sheet, with this page's headers.
        w.setDownloadListener { url, ua, disposition, mime, _ ->
            val name = URLUtil.guessFileName(url, disposition, mime)
            val s = Sniffed(url, "File", t.url, name)
            Capture.offer(url, name, headersFor(s) + ("User-Agent" to ua))
        }
        // Nothing loaded until something is searched or typed: a blank start. A rebuilt tab reloads its page.
        t.web = w
        if (t.url.isNotEmpty()) w.loadUrl(t.url)
        return w
    }

    /** A new tab, blank, made current. */
    fun newTab() {
        tabs.add(BTab((tabs.maxOfOrNull { it.id } ?: 0) + 1))
        current = tabs.lastIndex
    }

    fun select(i: Int) { current = i.coerceIn(0, tabs.lastIndex) }

    fun close(i: Int) {
        val t = tabs.getOrNull(i) ?: return
        t.web?.apply { (parent as? ViewGroup)?.removeView(this); destroy() }
        tabs.removeAt(i)
        if (tabs.isEmpty()) tabs.add(BTab(1))
        current = when {
            i < current -> current - 1
            current > tabs.lastIndex -> tabs.lastIndex
            else -> current
        }
    }

    private fun nav(t: BTab, v: WebView) {
        t.canBack = v.canGoBack()
        t.canForward = v.canGoForward()
    }

    /** An address, or words to search for. */
    fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val target = when {
            t.startsWith("http://", true) || t.startsWith("https://", true) -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> (ENGINES.firstOrNull { it.first == Prefs.searchEngine } ?: ENGINES.first()).second + java.net.URLEncoder.encode(t, "UTF-8")
        }
        tab.web?.loadUrl(target)
    }

    fun applySettings() {
        tabs.forEach { it.web?.settings?.javaScriptCanOpenWindowsAutomatically = !Prefs.blockPopups }
    }

    /** Settings: forget cookies, history and the cache, and go back to the blank start. */
    fun clearData() {
        CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
        tabs.forEach { t ->
            t.web?.apply {
                clearHistory()
                clearCache(true)
                loadUrl("about:blank")
            }
            t.url = ""
            t.title = ""
            t.found.clear()
            t.canBack = false
            t.canForward = false
        }
    }

    fun back() = tab.web?.takeIf { it.canGoBack() }?.goBack()
    fun forward() = tab.web?.takeIf { it.canGoForward() }?.goForward()
    fun reload() = tab.web?.reload()
}
