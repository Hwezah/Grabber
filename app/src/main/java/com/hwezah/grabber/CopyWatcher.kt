package com.hwezah.grabber

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Watches for "Copy" being tapped (or a "Copied" message appearing) in any app, then opens the capture
 * popup, which reads the clipboard. Android won't hand the clipboard to a background app directly.
 * It reads the words on the button tapped and, in browsers only, the address bar, so a download a browser
 * starts can be offered to Grabber (see [BrowserCatch]).
 */
class CopyWatcher : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var lastFire = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg in BrowserCatch.BROWSERS) browser(pkg, event)
        val words = (event.text.joinToString(" ") + " " + (event.contentDescription ?: "")).lowercase().trim()
        if (words.isEmpty() || words.length > 80) return
        val hit = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> COPY.any { it in words }
            else -> COPIED.any { it in words }
        }
        if (!hit) return
        val now = System.currentTimeMillis()
        if (now - lastFire < 1500) return
        lastFire = now
        // A moment for the app to finish putting it on the clipboard.
        main.postDelayed({
            runCatching {
                startActivity(
                    Intent(this, CaptureActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
                )
            }
        }, 350)
    }

    /** A browser: keeps its address in mind, and catches a download it starts. */
    private fun browser(pkg: String, event: AccessibilityEvent) {
        if (!Prefs.catchBrowser) return
        if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            runCatching { BrowserCatch.notePage(pkg, rootInActiveWindow) }
            return
        }
        val n = event.parcelableData as? android.app.Notification ?: return
        val file = BrowserCatch.onNotification(pkg, n) ?: return
        // The browser is usually still on screen: its address bar has the page the download came from.
        runCatching { BrowserCatch.notePage(pkg, rootInActiveWindow) }
        val key = BrowserCatch.key(pkg, file)
        BrowserCatch.pause(key)
        runCatching {
            startActivity(
                Intent(this, CaptureActivity::class.java).setAction(CaptureActivity.TAKE)
                    .putExtra("file", file).putExtra("pkg", pkg).putExtra("page", BrowserCatch.page(pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
            )
        }
    }

    override fun onInterrupt() {}

    companion object {
        private val COPY = listOf("copy", "copier", "copiar", "kopier", "copia", "nakili", "nakala")
        private val COPIED = listOf("copied", "copié", "copiado", "kopiert", "copiato")
    }
}

/** A Quick Settings tile: tap it to grab whatever is on the clipboard. */
class CaptureTile : android.service.quicksettings.TileService() {
    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        val i = Intent(this, CaptureActivity::class.java)
            .putExtra(CaptureActivity.FROM_TILE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(i)
        }
    }
}
