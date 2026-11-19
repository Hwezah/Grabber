package com.hwezah.grabber

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as L
import com.hwezah.grabber.design.BottomSheet
import com.hwezah.grabber.design.Button
import com.hwezah.grabber.design.CARD
import com.hwezah.grabber.design.Ink
import com.hwezah.grabber.design.StretchRow
import com.hwezah.grabber.design.Type

/**
 * Downloads started in other browsers. Android never tells another app the address of a file Chrome is
 * downloading, so the copy watcher spots the browser's "Downloading" notification, pauses it with the
 * notification's own button, notes the page from the browser's address bar, and offers to take it.
 */
object BrowserCatch {
    /** Browsers, and the id of their address bar. */
    val BROWSERS = mapOf(
        "com.android.chrome" to "url_bar", "com.chrome.beta" to "url_bar", "com.chrome.dev" to "url_bar", "com.chrome.canary" to "url_bar",
        "com.brave.browser" to "url_bar", "com.microsoft.emmx" to "url_bar", "com.kiwibrowser.browser" to "url_bar",
        "com.vivaldi.browser" to "url_bar", "com.yandex.browser" to "bro_omnibar_address_title_text",
        "com.sec.android.app.sbrowser" to "location_bar_edit_text", "com.opera.browser" to "url_field", "com.opera.mini.native" to "url_field",
        "org.mozilla.firefox" to "mozac_browser_toolbar_url_view", "com.duckduckgo.mobile.android" to "omnibarTextInput",
        "com.mi.globalbrowser" to "url", "com.UCMobile.intl" to "titlebar_url",
    )

    /** The newest notification for each download (key: browser + file), so its buttons can be pressed later. */
    private val latest = HashMap<String, Notification>()
    /** Downloads already offered, so updates to the same notification don't pop up again. */
    private val offered = LinkedHashMap<String, Long>()
    /** The last page seen in each browser's address bar. */
    private val pages = HashMap<String, String>()

    private val PAUSE = listOf("pause", "pausar", "pausieren", "mettre en pause", "pausa", "sitisha")
    private val RESUME = listOf("resume", "reanudar", "reprendre", "fortsetzen", "riprendi", "retomar", "continue", "endelea")
    private val CANCEL = listOf("cancel", "cancelar", "annuler", "abbrechen", "annulla", "ghairi")

    fun key(pkg: String, file: String) = "$pkg|$file"

    /** Notes [pkg]'s current address, read from its address bar. */
    fun notePage(pkg: String, root: AccessibilityNodeInfo?) {
        val id = BROWSERS[pkg] ?: return
        root ?: return
        val text = runCatching { root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull()?.text?.toString() }.getOrNull()?.trim()
        if (text.isNullOrEmpty() || text.contains(' ') || !text.contains('.')) return
        pages[pkg] = if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
    }

    fun page(pkg: String) = pages[pkg]

    /**
     * A browser notification: if it's a download that's running, remembered; returns its file name the first time
     * it's seen (so it's offered once), else null.
     */
    fun onNotification(pkg: String, n: Notification): String? {
        if (pkg !in BROWSERS) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val ex = n.extras ?: return null
        val file = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val channel = n.channelId.orEmpty().lowercase()
        val progress = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 || ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val buttons = n.actions.orEmpty().map { it.title?.toString()?.lowercase().orEmpty() }
        val stoppable = buttons.any { b -> CANCEL.any { it in b } }
        val download = (channel.contains("download") && (progress || stoppable)) || (progress && stoppable)
        if (!download) return null
        val k = key(pkg, file)
        latest[k] = n
        val now = System.currentTimeMillis()
        offered.entries.removeAll { now - it.value > 10 * 60_000L }
        if (k in offered) return null
        offered[k] = now
        return file
    }

    private fun press(k: String, words: List<String>): Boolean {
        val n = latest[k] ?: return false
        val a = n.actions.orEmpty().firstOrNull { a -> val t = a.title?.toString()?.lowercase().orEmpty(); words.any { it in t } } ?: return false
        return runCatching { a.actionIntent.send(); true }.getOrDefault(false)
    }

    fun pause(k: String) = press(k, PAUSE)
    fun resume(k: String) = press(k, RESUME)
    fun cancel(k: String) { if (!press(k, CANCEL)) pause(k); latest.remove(k) }

    /** Whether [url] points straight at a file rather than a web page. */
    fun looksLikeFile(url: String): Boolean {
        val path = runCatching { Uri.parse(url).path }.getOrNull().orEmpty()
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && ext.length <= 5 && ext !in setOf("html", "htm", "php", "asp", "aspx", "jsp", "cgi")
    }

    fun browserName(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("the browser")
}

/**
 * The popup when a browser starts a download: take it into Grabber (the browser's copy stops) or let the browser keep it.
 * Closing the popup lets the browser carry on.
 */
@Composable
fun TakeSheet(file: String, page: String?, browser: String, onGrabber: () -> Unit, onKeep: () -> Unit) {
    BottomSheet("Download with Grabber?", onKeep, icon = L.drawable.lucide_ic_download, iconTint = Ink.red) {
        Column(Modifier.padding(horizontal = CARD)) {
            Text(file, Modifier.basicMarquee(), style = Type.body.copy(color = Ink.text, fontWeight = FontWeight.SemiBold), maxLines = 1)
            val host = page?.let { runCatching { Uri.parse(it).host }.getOrNull() }?.removePrefix("www.")
            Text(
                "$browser started this download" + (host?.let { " on $it" } ?: "") + ". It's paused there until you choose.",
                Modifier.padding(top = 4.dp), style = Type.body,
            )
            if (page != null && !BrowserCatch.looksLikeFile(page)) Text(
                "Grabber opens the page in its browser: tap the download there once more and Grabber takes it.",
                Modifier.padding(top = 6.dp), style = Type.body,
            )
            Spacer(Modifier.height(14.dp))
            StretchRow {
                Button("Keep in $browser", onKeep, compact = true)
                Button("Get it in Grabber", onGrabber, accent = Ink.red, compact = true)
            }
        }
    }
}

/** Opens Grabber's browser on [url]. */
fun openInGrabberBrowser(context: Context, url: String) {
    context.startActivity(
        Intent(context, MainActivity::class.java).putExtra(MainActivity.OPEN_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    )
}
