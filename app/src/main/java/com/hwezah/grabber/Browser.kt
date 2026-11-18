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
data class Sniffed(
    val url: String, val kind: String, val page: String, val title: String,
    /** The headers the page itself sent for it (Referer, Origin, cookies): a player in an embed needs its own. */
    val headers: Map<String, String> = emptyMap(),
    /** Second line: resolution, bitrate, length, size, when known. */
    val detail: String = "",
)

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
    /** Quality playlists already listed under their stream, so they aren't listed twice. */
    val children: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    @Volatile var ua: String = ""
    /** A jump to another site that was stopped, offered with "Open anyway". */
    var stopped by mutableStateOf<String?>(null)
    /** One address let through after "Open anyway". */
    var pass: String? = null
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

    /** Sites whose redirects are allowed (from the menu), for this run of the app. */
    val allowJumps = mutableStateListOf<String>()

    /** Where pop-under and betting jumps go: stopped even after a tap, since a tap on a player is often hijacked. */
    private val BAD_JUMP = Regex(
        """(^|[.-])(1x[a-z0-9-]*|bet(way|win|365|ika|pawa|winner|king)|sportybet|melbet|mostbet|22bet|linebet|parimatch|pin-?up|[a-z]*casino[a-z]*|""" +
            """adsterra|popads|propeller[a-z]*|clickadu|hilltopads|exoclick|trafficjunky|juicyads|popcash|galaksion|monetag|onclick[a-z]*|""" +
            """[a-z]*push[a-z]*notif[a-z]*)([.-]|$)""",
    )

    /** The site part of a host: "www.movies2watch.tv" → "movies2watch.tv". */
    fun siteOf(host: String?): String {
        val parts = host.orEmpty().lowercase().split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        val two = parts.takeLast(2)
        // co.uk, com.ng and the like keep one more part.
        return if (two[0].length <= 3 && two[1].length == 2) parts.takeLast(3).joinToString(".") else two.joinToString(".")
    }

    /** Whether this page jump should be stopped: to another site, and either untapped or to an ad or betting site. */
    private fun stopJump(t: BTab, request: WebResourceRequest): Boolean {
        if (!Prefs.blockRedirects || !request.isForMainFrame) return false
        val to = request.url.toString()
        if (t.pass == to) { t.pass = null; return false }
        val from = siteOf(android.net.Uri.parse(t.url).host)
        val dest = siteOf(request.url.host)
        if (from.isEmpty() || from == dest || from in allowJumps) return false
        val bad = BAD_JUMP.containsMatchIn(request.url.host.orEmpty().lowercase())
        // A server's own redirect (sign-ins, short links) and a real tap go through, unless it's to an ad or betting site.
        return bad || (!request.hasGesture() && !request.isRedirect)
    }

    /** Opens a jump that was stopped. */
    fun openStopped() {
        val t = tab
        val u = t.stopped ?: return
        t.stopped = null
        t.pass = u
        t.web?.loadUrl(u)
    }

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

    private fun sniff(t0: BTab, url: String, sent: Map<String, String> = emptyMap()) {
        if (!Prefs.sniffMedia) return
        val kind = kindOf(url) ?: return
        // Pieces of a stream aren't worth listing; the stream itself is.
        if (Regex("""(seg|chunk|frag|part)[-_]?\d+""", RegexOption.IGNORE_CASE).containsMatchIn(url) && kind != "Stream (HLS)") return
        if (url in t0.children) return
        val page = t0.url
        val t = t0.title
        val s = Sniffed(url, kind, page, t, sent)
        main.post {
            if (t0.found.none { it.url == url } && url !in t0.children) t0.found.add(0, s)
        }
        // A stream: read what it holds, like 1DM: every quality with its size, or this one quality's details.
        if (kind == "Stream (HLS)" && expanded.add(url)) qualities(t0, s)
    }

    private fun fetchText(url: String, headers: Map<String, String>): String? = runCatching {
        val b = okhttp3.Request.Builder().url(url)
        headers.forEach { (k, v) -> runCatching { b.header(k, v) } }
        com.hwezah.grabber.engine.Http.client.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    private fun sizeOf(url: String, headers: Map<String, String>): Long = runCatching {
        val b = okhttp3.Request.Builder().url(url).head()
        headers.forEach { (k, v) -> runCatching { b.header(k, v) } }
        com.hwezah.grabber.engine.Http.client.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull() ?: -1 else -1 }
    }.getOrDefault(-1)

    private fun length(sec: Double): String {
        val m = (sec / 60).toInt()
        return if (m >= 60) "${m / 60} h ${m % 60} min" else if (m > 0) "$m min" else "${sec.toInt()} s"
    }

    private fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
        else -> "%.0f KB".format(b / 1024.0)
    }

    /** One quality's playlist: how long it runs and, from its first piece, roughly how big it is. */
    private fun measure(url: String, headers: Map<String, String>): Pair<Double, Long>? {
        val text = fetchText(url, headers) ?: return null
        val m = com.hwezah.grabber.engine.Hls.media(url, text)
        if (m.segments.isEmpty()) return null
        val first = sizeOf(m.segments.first().url, headers)
        val est = if (first > 0) first * m.segments.size else -1
        return m.duration to est
    }

    /** Fetches a stream's playlist (with the page's own headers) and lists each quality it offers, best first, with sizes. */
    private fun qualities(t0: BTab, s: Sniffed) {
        val found = t0.found
        val headers = headersFor(s, t0)
        kotlin.concurrent.thread(name = "grabber-hls-qualities") {
            val text = fetchText(s.url, headers) ?: return@thread
            val variants = com.hwezah.grabber.engine.Hls.variants(s.url, text)
            if (variants.isEmpty()) {
                // Already a single quality: say how long and how big.
                val m = com.hwezah.grabber.engine.Hls.media(s.url, text)
                if (m.segments.isEmpty()) return@thread
                val first = sizeOf(m.segments.first().url, headers)
                val parts = listOfNotNull(
                    "One quality",
                    m.duration.takeIf { it > 0 }?.let { length(it) },
                    if (first > 0) "~" + bytes(first * m.segments.size) else null,
                )
                main.post {
                    val i = found.indexOfFirst { it.url == s.url }
                    if (i >= 0) found[i] = found[i].copy(detail = parts.joinToString(" · "))
                }
                return@thread
            }
            t0.children.addAll(variants.map { it.url })
            // Every quality runs as long as the others: measure one, then size each by its bitrate.
            val dur = measure(variants.last().url, headers)?.first ?: 0.0
            main.post {
                val at = found.indexOfFirst { it.url == s.url }.coerceAtLeast(0)
                found.removeAll { it.url == s.url || it.url in t0.children }
                val best = variants.first()
                val rows = listOf(s.copy(kind = "Best quality · ${best.label}", detail = "Picks the best when downloading" + (if (dur > 0) " · " + length(dur) else ""))) +
                    variants.map { v ->
                        val bits = listOfNotNull(
                            v.resolution?.replace('x', '×'),
                            v.bandwidth.takeIf { it > 0 }?.let { "%.1f Mbps".format(it / 1_000_000.0) },
                            if (dur > 0 && v.bandwidth > 0) "~" + bytes((v.bandwidth * dur / 8).toLong()) else null,
                        )
                        Sniffed(v.url, "${v.label} · HLS", s.page, s.title, s.headers, bits.joinToString(" · "))
                    }
                found.addAll(at.coerceAtMost(found.size), rows)
            }
        }
    }

    /** The headers a download from this page needs: where it came from, the browser's identity, its cookies. */
    fun headersFor(s: Sniffed, t0: BTab = tab): Map<String, String> {
        val h = linkedMapOf<String, String>()
        if (s.page.isNotBlank()) h["Referer"] = s.page
        t0.ua.takeIf { it.isNotBlank() }?.let { h["User-Agent"] = it }
        // What the page itself sent wins: an embedded player checks its own Referer and Origin.
        s.headers.forEach { (k, v) -> if (k.lowercase() !in setOf("range", "accept-encoding", "host")) h[k] = v }
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
        darken(w.settings)
        t.ua = w.settings.userAgentString.orEmpty()
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            // Runs on a background thread for every request: it must never throw.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = try {
                val u = request.url
                if (Prefs.blockAds && !request.isForMainFrame && blockedHost(u.host.orEmpty())) {
                    main.post { t.blocked++ }
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                } else {
                    sniff(t, u.toString(), request.requestHeaders.orEmpty())
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
                if (stopJump(t, request)) { t.stopped = u.toString(); return true }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                val u = if (url == "about:blank") "" else url
                if (u != t.url) t.found.clear()
                t.url = u
                t.loading = true
                t.blocked = 0
                t.stopped = null
                nav(t, view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                t.url = if (url == "about:blank") "" else url
                t.loading = false
                t.title = view.title.orEmpty()
                nav(t, view)
                Places.visited(t.url, t.title)
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
    /** Set when a link should open in the browser: the app switches to the Browser tab and clears it. */
    var openNow by mutableStateOf(false)

    /** Opens [url] in a new tab (the page loads when the tab's view is made). */
    fun open(url: String) {
        newTab()
        tab.url = url
        openNow = true
    }

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
        tabs.forEach { t ->
            t.web?.settings?.let { s ->
                s.javaScriptCanOpenWindowsAutomatically = !Prefs.blockPopups
                darken(s)
            }
        }
    }

    /** Dark pages: Android's own darkening of light sites (where the phone's WebView supports it). */
    @Suppress("DEPRECATION")
    private fun darken(s: android.webkit.WebSettings) {
        runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, Prefs.darkPages)
            } else if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.FORCE_DARK)) {
                androidx.webkit.WebSettingsCompat.setForceDark(
                    s, if (Prefs.darkPages) androidx.webkit.WebSettingsCompat.FORCE_DARK_ON else androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF,
                )
            }
        }
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
