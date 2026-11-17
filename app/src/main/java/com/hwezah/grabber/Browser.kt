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

/**
 * The browser: one WebView kept for the app's life (so a tab switch doesn't reload the page). It watches every
 * request the page makes to find videos, streams, audio and files, and drops requests to ad and tracker networks.
 */
object Browser {
    @SuppressLint("StaticFieldLeak")
    private var web: WebView? = null
    private val main = Handler(Looper.getMainLooper())

    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var loading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var blocked by mutableIntStateOf(0)
    /** What this page has loaded that Grabber can take, newest first. */
    val found = mutableStateListOf<Sniffed>()

    private const val HOME = "https://duckduckgo.com/"

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

    private fun sniff(url: String) {
        val kind = kindOf(url) ?: return
        // Pieces of a stream aren't worth listing; the stream itself is.
        if (Regex("""(seg|chunk|frag|part)[-_]?\d+""", RegexOption.IGNORE_CASE).containsMatchIn(url) && kind != "Stream (HLS)") return
        val page = this.url
        val t = title
        main.post {
            if (found.none { it.url == url }) found.add(0, Sniffed(url, kind, page, t))
        }
    }

    /** The headers a download from this page needs: where it came from, the browser's identity, its cookies. */
    fun headersFor(s: Sniffed): Map<String, String> {
        val h = linkedMapOf<String, String>()
        if (s.page.isNotBlank()) h["Referer"] = s.page
        web?.settings?.userAgentString?.let { h["User-Agent"] = it }
        CookieManager.getInstance().getCookie(s.url)?.takeIf { it.isNotBlank() }?.let { h["Cookie"] = it }
        return h
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun view(context: Context): WebView {
        web?.let { (it.parent as? ViewGroup)?.removeView(it); return it }
        CookieManager.getInstance().setAcceptCookie(true)
        val w = WebView(context)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            // No pop-up windows: most are ads.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (Prefs.blockAds && !request.isForMainFrame && blockedHost(u.host.orEmpty())) {
                    main.post { blocked++ }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                sniff(u.toString())
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                // Links Grabber handles itself: magnets, and pages that are files.
                if (u.scheme == "magnet") { Capture.offer(u.toString(), title); return true }
                if (u.scheme != "http" && u.scheme != "https") return true
                if (Prefs.blockAds && blockedHost(u.host.orEmpty())) { blocked++; return true }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url != this@Browser.url) found.clear()
                this@Browser.url = url
                loading = true
                blocked = 0
                nav(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                this@Browser.url = url
                loading = false
                title = view.title.orEmpty()
                nav(view)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                this@Browser.url = url
                nav(view)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
            override fun onReceivedTitle(view: WebView, t: String?) { title = t.orEmpty() }
        }
        // A file the site hands over: straight to Grabber's download sheet, with this page's headers.
        w.setDownloadListener { url, ua, disposition, mime, _ ->
            val name = URLUtil.guessFileName(url, disposition, mime)
            val s = Sniffed(url, "File", this.url, name)
            Capture.offer(url, name, headersFor(s) + ("User-Agent" to ua))
        }
        w.loadUrl(HOME)
        web = w
        return w
    }

    private fun nav(v: WebView) {
        canBack = v.canGoBack()
        canForward = v.canGoForward()
    }

    /** An address, or words to search for. */
    fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val target = when {
            t.startsWith("http://", true) || t.startsWith("https://", true) -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        }
        web?.loadUrl(target)
    }

    fun back() = web?.takeIf { it.canGoBack() }?.goBack()
    fun forward() = web?.takeIf { it.canGoForward() }?.goForward()
    fun reload() = web?.reload()
}
