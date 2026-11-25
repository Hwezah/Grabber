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

/**
 * One browser tab: its own page, history and finds. [incognito]: its pages aren't kept in history, and its cookies,
 * sign-ins, storage and cache live apart from the other tabs' (where the phone's WebView allows) and are forgotten
 * when the last such tab closes.
 */
class BTab(val id: Long, val incognito: Boolean = false) {
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
    /** When the viewer last opened an address here (typed, a bookmark): the page's own redirects on its way in go through. */
    @Volatile var openedAt = 0L
    /** When this tab was last shown: the least recently used give their pages back first. */
    var usedAt = System.currentTimeMillis()
    /** A window a page opened, already loading: taken as this tab's page rather than loaded again. */
    @SuppressLint("StaticFieldLeak")
    var adopt: WebView? = null
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
        // The big weekly list first (about 300,000 sites), then the built-in short list.
        if (AdBlock.blocks(host)) return true
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

    private fun sniff(t0: BTab, url: String, sent: Map<String, String> = emptyMap(), known: String? = null) {
        if (!Prefs.sniffMedia) return
        if (!url.startsWith("http", true)) return
        val kind = known ?: kindOf(url) ?: return
        // Pieces of a stream aren't worth listing; the stream itself is. "init-…mp4" is a stream's header, not a video.
        val file = url.substringBefore('?').substringAfterLast('/').lowercase()
        if (kind != "Stream (HLS)" && (Regex("""(seg|chunk|frag|part)[-_]?\d+""").containsMatchIn(file) || Regex("""^init[-_.]|[-_.]init[-_.]""").containsMatchIn(file) || file.endsWith(".m4s"))) return
        if (url in t0.children) return
        val page = t0.url
        val t = t0.title
        val s = Sniffed(url, kind, page, t, sent)
        main.post {
            if (t0.found.none { it.url == url } && url !in t0.children) t0.found.add(0, s)
        }
        // A stream: read what it holds, like 1DM: every quality with its size, or this one quality's details.
        if (kind == "Stream (HLS)" && expanded.add(url)) qualities(t0, s)
        else if (kind == "Video" && expanded.add(url)) describeFile(t0, s)
    }

    /** A video file's size; a small one is a preview clip (a thumbnail's hover video), not the film. */
    private fun describeFile(t0: BTab, s: Sniffed) = kotlin.concurrent.thread(name = "grabber-size") {
        val size = sizeOf(s.url, headersFor(s, t0))
        if (size <= 0) return@thread
        val detail = if (size < 3L * 1024 * 1024) "Preview clip · " + bytes(size) else bytes(size)
        main.post {
            val i = t0.found.indexOfFirst { it.url == s.url }
            if (i >= 0) t0.found[i] = t0.found[i].copy(detail = detail)
        }
    }

    /* ------------------------------------------------------------ finds without a telling address */

    /** Addresses already asked what they are, so a player's repeated range requests are asked once. */
    private val probed = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Pictures, fonts, scripts and styles: never media. */
    private val NOT_MEDIA = Regex("""\.(jpe?g|png|gif|webp|avif|svg|ico|woff2?|ttf|otf|css|js|json|html?)(\?|#|$)""", RegexOption.IGNORE_CASE)

    /** A Content-Type as one of the Browser's kinds; null when it isn't a video, audio or a stream's playlist. */
    private fun kindOfType(type: String?): String? {
        val t = type.orEmpty().lowercase()
        return when {
            "mpegurl" in t -> "Stream (HLS)"
            "dash+xml" in t -> "Stream (DASH)"
            "mp2t" in t || "iso.segment" in t -> null
            t.startsWith("video/") -> "Video"
            t.startsWith("audio/") -> "Audio"
            else -> null
        }
    }

    /** A range request for an address that doesn't say what it is: one byte of it asked for, its Content-Type says. */
    private fun probe(t0: BTab, url: String, sent: Map<String, String>) = kotlin.concurrent.thread(name = "grabber-probe") {
        runCatching {
            val headers = headersFor(Sniffed(url, "", t0.url, t0.title, sent), t0)
            val b = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-0")
            headers.forEach { (k, v) -> runCatching { b.header(k, v) } }
            com.hwezah.grabber.engine.Http.client.newCall(b.build()).execute().use { r -> kindOfType(r.header("Content-Type"))?.let { sniff(t0, url, sent, it) } }
        }
    }

    /** Told by the page itself (MEDIA_SPY): what its video or audio plays, and the playlists its player fetched. */
    private class MediaSpy(val t0: BTab) {
        @android.webkit.JavascriptInterface
        fun found(url: String, type: String) {
            if (url.length > 4000) return
            val kind = kindOfType(type) ?: kindOf(url) ?: return
            sniff(t0, url, known = kind)
        }
    }

    /** In every page and frame from its start: a media element's real address as it plays, and HLS/DASH playlists fetched. */
    private const val MEDIA_SPY = """(function(){
  if (window.__grMedia || !window.GrabberMedia) return; window.__grMedia = 1;
  var B = window.GrabberMedia;
  function rep(u, t) { try { if (!u) return; u = String(u); if (u.indexOf('blob:') == 0 || u.indexOf('data:') == 0) return; B.found(new URL(u, location.href).href, String(t || '')); } catch (e) {} }
  function playlist(ct) { ct = String(ct || '').toLowerCase(); return ct.indexOf('mpegurl') >= 0 || ct.indexOf('dash+xml') >= 0; }
  function el(e) { var v = e.target; if (v && (v.tagName == 'VIDEO' || v.tagName == 'AUDIO')) rep(v.currentSrc || v.src, v.tagName == 'VIDEO' ? 'video/' : 'audio/'); }
  document.addEventListener('loadedmetadata', el, true);
  document.addEventListener('play', el, true);
  try {
    var o = XMLHttpRequest.prototype.open, s = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(m, u) { this.__gru = u; return o.apply(this, arguments); };
    XMLHttpRequest.prototype.send = function() { var x = this; x.addEventListener('load', function() { try { var ct = x.getResponseHeader('content-type'); if (playlist(ct)) rep(x.responseURL || x.__gru, ct); } catch (e) {} }); return s.apply(this, arguments); };
  } catch (e) {}
  try {
    if (window.fetch) { var f = window.fetch; window.fetch = function() { return f.apply(this, arguments).then(function(r) { try { var ct = r.headers.get('content-type'); if (playlist(ct)) rep(r.url, ct); } catch (e) {} return r; }); }; }
  } catch (e) {}
})();"""

    /** A site's service worker's requests never reach a tab's client: watched here, for the tab in front. */
    private var workersWatched = false

    private fun watchWorkers() {
        if (workersWatched) return
        workersWatched = true
        runCatching {
            val f = androidx.webkit.WebViewFeature::isFeatureSupported
            if (!f(androidx.webkit.WebViewFeature.SERVICE_WORKER_BASIC_USAGE) || !f(androidx.webkit.WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) return
            androidx.webkit.ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : androidx.webkit.ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    runCatching { sniff(tab, request.url.toString(), request.requestHeaders.orEmpty()) }
                    return null
                }
            })
        }
    }

    /* ------------------------------------------------------------ incognito */

    /** The WebView profile incognito tabs share: its own cookies, storage and cache, deleted with the last such tab. */
    private const val PRIVATE = "grabber-incognito"

    /** Whether this phone's WebView keeps incognito tabs' cookies and storage apart. */
    val trulyPrivate: Boolean by lazy { runCatching { androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE) }.getOrDefault(false) }

    private fun cookiesOf(t: BTab): CookieManager =
        if (t.incognito && trulyPrivate) runCatching { androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(PRIVATE).cookieManager }.getOrNull() ?: CookieManager.getInstance()
        else CookieManager.getInstance()

    private fun forgetIncognito() {
        if (!trulyPrivate) return
        main.postDelayed({ runCatching { if (tabs.none { it.incognito }) androidx.webkit.ProfileStore.getInstance().deleteProfile(PRIVATE) } }, 1500)
    }

    /* ------------------------------------------------------------ live pages */

    /** Pages destroyed or found dead: never handed back to a tab, which then gets a new one. */
    private val dead: MutableSet<WebView> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    /** Pages kept alive at once: beyond it the least recently used give theirs back and load again when shown. */
    private const val MAX_LIVE = 4

    private fun kill(t: BTab) {
        val w = t.web ?: return
        t.web = null
        dead += w
        runCatching { (w.parent as? ViewGroup)?.removeView(w) }
        runCatching { w.destroy() }
        t.loading = false
    }

    private fun trimPages(keep: BTab) {
        tabs.filter { it.web != null && it !== keep }.sortedByDescending { it.usedAt }.drop(MAX_LIVE - 1).forEach { kill(it) }
    }

    /** A page engine that stays hung through three reports in a row is ended, so its pages are rebuilt, not left black. */
    private fun watchEngine(w: WebView) {
        val f = androidx.webkit.WebViewFeature::isFeatureSupported
        if (!f(androidx.webkit.WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) return
        runCatching {
            androidx.webkit.WebViewCompat.setWebViewRenderProcessClient(w, object : androidx.webkit.WebViewRenderProcessClient() {
                private var strikes = 0
                override fun onRenderProcessUnresponsive(view: WebView, renderer: androidx.webkit.WebViewRenderProcess?) {
                    if (fullScreen) return
                    if (++strikes < 3) return
                    strikes = 0
                    if (f(androidx.webkit.WebViewFeature.WEB_VIEW_RENDERER_TERMINATE)) runCatching { renderer?.terminate() }
                }
                override fun onRenderProcessResponsive(view: WebView, renderer: androidx.webkit.WebViewRenderProcess?) { strikes = 0 }
            })
        }
    }

    private fun openedByViewer(t: BTab) = System.currentTimeMillis() - t.openedAt < 10_000

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
        cookiesOf(t0).getCookie(s.url)?.takeIf { it.isNotBlank() }?.let { h["Cookie"] = it }
        return h
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun view(context: Context, t: BTab = tab): WebView {
        t.usedAt = System.currentTimeMillis()
        t.web?.takeIf { it !in dead }?.let { return it }
        t.web = null
        trimPages(keep = t)
        // A window the page opened, already on its way: kept as it is, not loaded again from scratch.
        val adopted = t.adopt
        t.adopt = null
        val w = adopted ?: WebView(context).also { prepare(it, t.incognito) }
        finishView(context, w, t)
        if (t.url.isNotEmpty() && adopted == null) w.loadUrl(t.url)
        if (adopted != null) {
            adopted.url?.takeIf { it.isNotBlank() && it != "about:blank" }?.let { t.url = it }
            t.title = adopted.title.orEmpty()
            t.progress = adopted.progress
            t.loading = adopted.progress < 100
            nav(t, adopted)
        }
        return w
    }

    /** A new page's WebView made ready before it loads: the tab's profile, settings, and the shield and media watch. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun prepare(w: WebView, incognito: Boolean) {
        CookieManager.getInstance().setAcceptCookie(true)
        if (incognito) {
            if (trulyPrivate) runCatching {
                androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(PRIVATE)
                androidx.webkit.WebViewCompat.setProfile(w, PRIVATE)
            }
            w.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            // Pop-ups are caught in a window of their own (catchPopup), never loaded over this page.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        darken(w.settings)
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            if (Prefs.blockAds) runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, AdBlock.SHIELD_SCRIPT, setOf("*")) }
            runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, MEDIA_SPY, setOf("*")) }
        }
    }

    /** The tab's own watchers on its WebView: requests, pages, windows, downloads, scrolling. */
    private fun finishView(context: Context, w: WebView, t: BTab) {
        watchEngine(w)
        t.ua = w.settings.userAgentString.orEmpty()
        cookiesOf(t).setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            // Runs on a background thread for every request: it must never throw.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = try {
                val u = request.url
                if (Prefs.blockAds && !request.isForMainFrame && blockedHost(u.host.orEmpty())) {
                    main.post { t.blocked++ }
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                } else {
                    val url = u.toString()
                    val sent = request.requestHeaders.orEmpty()
                    sniff(t, url, sent)
                    // A player asking for a range of an address that doesn't say what it is: asked what it is.
                    if (Prefs.sniffMedia && !request.isForMainFrame && kindOf(url) == null && url.startsWith("http", true) && !NOT_MEDIA.containsMatchIn(url) &&
                        sent.keys.any { it.equals("Range", true) } && probed.add(url)
                    ) probe(t, url, sent)
                    null
                }
            } catch (e: Throwable) {
                null
            }

            // The page's renderer crashed or was killed for memory: rebuild this tab instead of closing the app.
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                if (fullScreen) runCatching { exitFull?.invoke() }
                dead += view
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
                if (u.scheme == "magnet") { Capture.offer(u.toString(), t.title, browser = true); return true }
                // A link straight to a file (video, audio, archive, app, document, torrent): Grabber takes it.
                val k = kindOf(u.toString())
                if (k != null && !k.startsWith("Stream")) {
                    val name = u.lastPathSegment?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                    Capture.offer(u.toString(), name, headersFor(Sniffed(u.toString(), k, t.url, t.title)), browser = true)
                    return true
                }
                if (u.scheme != "http" && u.scheme != "https") return true
                // The block list stops a page too, but says so ("Open anyway"), and never what the viewer just opened.
                if (Prefs.blockAds && !openedByViewer(t) && blockedHost(u.host.orEmpty())) {
                    t.blocked++
                    if (request.isForMainFrame) t.stopped = u.toString()
                    return true
                }
                if (stopJump(t, request)) { t.stopped = u.toString(); return true }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                // Older WebViews without start-of-page scripts: the shield and media watch as early as they allow.
                if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    if (Prefs.blockAds) runCatching { view.evaluateJavascript(AdBlock.SHIELD_SCRIPT, null) }
                    runCatching { view.evaluateJavascript(MEDIA_SPY, null) }
                }
                val u = if (url == "about:blank") "" else url
                if (u != t.url) { t.found.clear(); t.children.clear() }
                t.url = u
                t.loading = true
                t.blocked = 0
                t.stopped = null
                nav(t, view)
            }

            // Leftover ad boxes hidden as soon as the page shows, and again when it's done.
            override fun onPageCommitVisible(view: WebView, url: String) {
                if (Prefs.blockAds) runCatching { view.evaluateJavascript(AdBlock.HIDE_SCRIPT, null) }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (Prefs.blockAds) runCatching { view.evaluateJavascript(AdBlock.HIDE_SCRIPT, null) }
                t.url = if (url == "about:blank") "" else url
                t.loading = false
                t.title = view.title.orEmpty()
                nav(t, view)
                // Incognito keeps no history.
                if (!t.incognito) Places.visited(t.url, t.title)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                t.url = if (url == "about:blank") "" else url
                nav(t, view)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { t.progress = newProgress }
            // A page opening a window: caught here, never loaded over this page.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean =
                catchPopup(view, t, isUserGesture, resultMsg)
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
        // A file the site hands over: straight to Grabber's download sheet, with this page's headers.
        w.setDownloadListener { url, ua, disposition, mime, _ ->
            val name = URLUtil.guessFileName(url, disposition, mime)
            // A WireGuard config goes straight into the VPN (Settings › VPN), read in the page when it made it itself.
            if (name.endsWith(".conf", true)) {
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    w.evaluateJavascript("fetch('" + url.replace("'", "\\'") + "').then(r=>r.text()).then(t=>GrabberConf.take(t))", null)
                } else kotlin.concurrent.thread(name = "grabber-conf") {
                    val text = fetchText(url, headersFor(Sniffed(url, "File", t.url, name)) + ("User-Agent" to ua))
                    if (text != null && "[Interface]" in text) takeConf(context, text)
                    else main.post { Capture.offer(url, name, headersFor(Sniffed(url, "File", t.url, name)) + ("User-Agent" to ua), browser = true) }
                }
                return@setDownloadListener
            }
            val s = Sniffed(url, "File", t.url, name)
            Capture.offer(url, name, headersFor(s) + ("User-Agent" to ua), browser = true)
        }
        w.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface fun take(text: String) { takeConf(context, text) }
        }, "GrabberConf")
        w.addJavascriptInterface(MediaSpy(t), "GrabberMedia")
        watchWorkers()
        // Which way the page is being read: the address bar hides going down and comes back going up.
        var travel = 0
        val step = (24 * context.resources.displayMetrics.density).toInt()
        w.setOnScrollChangeListener { _, _, y, _, oldY ->
            if (y != oldY) scrollTick++
            val d = y - oldY
            travel = if (travel != 0 && (d > 0) != (travel > 0)) d else travel + d
            if (travel <= -step) { goingUp++; travel = 0 } else if (travel >= step) { goingDown++; travel = 0 }
        }
        t.web = w
    }

    /**
     * A window a page opens, read in an unseen WebView until its address is known: an ad, betting or pop-under site,
     * or a window opened without a tap (with pop-up blocking on), is dropped; the same site's click-through page is
     * followed in this tab; a tapped link to a real page becomes a new tab.
     */
    private fun catchPopup(opener: WebView, t: BTab, tapped: Boolean, msg: android.os.Message): Boolean {
        if (Prefs.blockPopups && !tapped) { main.post { t.blocked++ }; return false }
        val probe = WebView(opener.context)
        prepare(probe, t.incognito)
        var kept = false
        probe.webViewClient = object : WebViewClient() {
            private var done: Boolean? = null

            private fun decide(url: String): Boolean {
                done?.let { return it }
                val host = android.net.Uri.parse(url).host.orEmpty().lowercase()
                val ad = url.startsWith("about:") || host.isEmpty() || (Prefs.blockAds && blockedHost(host)) || BAD_JUMP.containsMatchIn(host)
                val sameSite = !ad && siteOf(host).isNotEmpty() && siteOf(host) == siteOf(android.net.Uri.parse(t.url).host)
                done = ad || sameSite
                main.post {
                    if (ad || sameSite) {
                        dead += probe
                        runCatching { probe.stopLoading(); probe.destroy() }
                        if (ad) t.blocked++
                        else {
                            t.openedAt = System.currentTimeMillis()
                            t.pass = url
                            t.web?.loadUrl(url, mapOf("Referer" to t.url))
                        }
                    } else {
                        kept = true
                        newTab(incognito = t.incognito)
                        tab.url = url
                        tab.openedAt = System.currentTimeMillis()
                        if (probe !in dead) tab.adopt = probe
                    }
                }
                return ad || sameSite
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = decide(request.url.toString())
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { if (url != "about:blank" && decide(url)) view.stopLoading() }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                dead += view
                runCatching { view.destroy() }
                return true
            }
        }
        (msg.obj as? WebView.WebViewTransport)?.webView = probe
        msg.sendToTarget()
        // Nothing came (a window that stays blank): let go after a moment, unless it became a tab.
        main.postDelayed({ if (!kept) { dead += probe; runCatching { probe.destroy() } } }, 8_000)
        return true
    }

    /** A new tab, blank, made current. */
    /** Counted up as the page scrolls up or down, for the address bar. */
    var goingUp by mutableIntStateOf(0)
    var goingDown by mutableIntStateOf(0)

    /** A WireGuard config downloaded in the browser, into the VPN. */
    private fun takeConf(context: Context, text: String) {
        val problem = Vpn.import(text)
        main.post {
            android.widget.Toast.makeText(context, problem ?: "VPN config added. Turn the VPN on in ⋮ or Settings › VPN.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Set when a link should open in the browser: the app switches to the Browser tab and clears it. */
    var openNow by mutableStateOf(false)

    /** Opens [url] in a new tab (the page loads when the tab's view is made). */
    fun open(url: String) {
        newTab()
        tab.url = url
        openNow = true
    }

    fun newTab(incognito: Boolean = false) {
        tabs.add(BTab((tabs.maxOfOrNull { it.id } ?: 0) + 1, incognito))
        current = tabs.lastIndex
    }

    fun select(i: Int) {
        current = i.coerceIn(0, tabs.lastIndex)
        tab.usedAt = System.currentTimeMillis()
    }

    fun close(i: Int) {
        val t = tabs.getOrNull(i) ?: return
        t.web?.apply {
            if (t.incognito) { clearHistory(); clearCache(true) }
            dead += this
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        tabs.removeAt(i)
        if (t.incognito && tabs.none { it.incognito }) forgetIncognito()
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
        tab.openedAt = System.currentTimeMillis()
        tab.web?.loadUrl(target)
    }

    fun applySettings() {
        tabs.forEach { t ->
            t.web?.settings?.let { s ->
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
