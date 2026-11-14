package com.hwezah.grabber

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hwezah.grabber.design.Button
import com.hwezah.grabber.design.CARD
import com.hwezah.grabber.design.Ink
import com.hwezah.grabber.design.Type
import java.io.File

/** Sites you can sign in to, so yt-dlp can fetch what they only show to signed-in accounts. */
enum class Site(val label: String, val login: String, val domains: List<String>) {
    Instagram("Instagram", "https://www.instagram.com/accounts/login/", listOf("instagram.com")),
    TikTok("TikTok", "https://www.tiktok.com/login", listOf("tiktok.com")),
    Facebook("Facebook", "https://m.facebook.com/login", listOf("facebook.com")),
    X("X (Twitter)", "https://x.com/i/flow/login", listOf("x.com", "twitter.com")),
    YouTube("YouTube", "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://m.youtube.com/", listOf("youtube.com", "google.com")),
}

/**
 * Sign-ins: each site's cookies, written in the cookies.txt format yt-dlp reads, kept in the app's private
 * storage. Only the cookies of that site's own domains are kept.
 */
object SignIns {
    private fun dir(c: Context) = File(c.filesDir, "signins").apply { mkdirs() }
    private fun file(c: Context, s: Site) = File(dir(c), "${s.name}.txt")

    fun signedIn(c: Context, s: Site) = file(c, s).let { it.exists() && it.length() > 0 }

    /** Saves what the browser page holds for [s] after signing in. Returns whether there was anything. */
    fun save(c: Context, s: Site): Boolean {
        val cm = CookieManager.getInstance()
        cm.flush()
        val far = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val lines = s.domains.flatMap { d ->
            val raw = cm.getCookie("https://www.$d") ?: cm.getCookie("https://$d") ?: return@flatMap emptyList()
            raw.split(';').mapNotNull { kv ->
                val k = kv.substringBefore('=').trim()
                val v = kv.substringAfter('=', "").trim()
                if (k.isEmpty()) null else ".$d\tTRUE\t/\tTRUE\t$far\t$k\t$v"
            }
        }
        if (lines.isEmpty()) return false
        file(c, s).writeText("# Netscape HTTP Cookie File\n" + lines.joinToString("\n") + "\n")
        return true
    }

    fun signOut(c: Context, s: Site) {
        file(c, s).delete()
        val cm = CookieManager.getInstance()
        s.domains.forEach { d ->
            cm.getCookie("https://www.$d")?.split(';')?.forEach { kv ->
                val k = kv.substringBefore('=').trim()
                cm.setCookie("https://www.$d", "$k=; Max-Age=0; Domain=.$d; Path=/")
            }
        }
        cm.flush()
    }

    /** One cookies.txt with every sign-in, for yt-dlp; null when there are none. */
    fun cookiesFile(c: Context): File? {
        val parts = Site.entries.map { file(c, it) }.filter { it.exists() && it.length() > 0 }
        if (parts.isEmpty()) return null
        val out = File(c.filesDir, "work/cookies.txt").apply { parentFile?.mkdirs() }
        out.writeText("# Netscape HTTP Cookie File\n" + parts.joinToString("\n") { f -> f.readLines().filterNot { it.startsWith("#") }.joinToString("\n") } + "\n")
        return out
    }

    /** The site a link belongs to, if it's one you can sign in to. */
    fun siteFor(url: String): Site? {
        val host = runCatching { android.net.Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
        return Site.entries.firstOrNull { s -> s.domains.any { host == it || host.endsWith(".$it") } }
            ?: if (host == "youtu.be") Site.YouTube else null
    }

    fun open(c: Context, s: Site) = c.startActivity(Intent(c, SignInActivity::class.java).putExtra("site", s.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** A plain browser page for signing in; "Done" keeps the sign-in. */
class SignInActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val site = runCatching { Site.valueOf(intent.getStringExtra("site") ?: "") }.getOrNull() ?: return finish()
        CookieManager.getInstance().setAcceptCookie(true)
        setContent {
            Column(Modifier.fillMaxSize().background(Ink.bg)) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = CARD, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sign in to ${site.label}", style = Type.title)
                        Text("Then tap Done. Grabber keeps only ${site.label}'s cookies.", style = Type.meta)
                    }
                    Button("Done", {
                        val ok = SignIns.save(this@SignInActivity, site)
                        android.widget.Toast.makeText(this@SignInActivity, if (ok) "Signed in to ${site.label}" else "Not signed in yet", android.widget.Toast.LENGTH_SHORT).show()
                        if (ok) finish()
                    }, accent = Ink.green, compact = true)
                }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = WebViewClient()
                            loadUrl(site.login)
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}
