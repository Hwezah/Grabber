package com.hwezah.grabber

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hwezah.grabber.engine.Segments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeping a download that stopped part-way: a fresh link for the same file found on its page (progress kept),
 * or a playable copy of what's already there, with the download left to finish later. Nothing is thrown away.
 */
object Rescue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    /** The download waiting for a fresh link from Grabber's browser. */
    var target by mutableStateOf<Long?>(null)

    /** Copies being saved, by download id. */
    var saving by mutableStateOf<Set<Long>>(emptySet())

    private fun say(text: String) { main.post { Updater.app?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() } } }

    /** The page the file came from (its Referer), or the site it's on. */
    fun pageOf(item: Item): String? =
        item.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value?.takeIf { it.startsWith("http") }
            ?: item.source.takeIf { it.startsWith("http") }
            ?: runCatching { Uri.parse(item.url).let { "${it.scheme}://${it.host}/" } }.getOrNull()

    /** Opens the file's page in Grabber's browser; the next download tapped there that matches carries this one on. */
    fun findOnPage(item: Item) {
        val page = pageOf(item) ?: return
        target = item.id
        Browser.open(page)
        say("Tap the download for ${item.name} again: Grabber carries on from ${Fmt.bytes(item.done)}")
    }

    /**
     * Called for a link caught in Grabber's browser. While a download is waiting for a fresh link, a link to a file of
     * the same size takes over that download (from where it stopped) and true is returned; otherwise false.
     */
    fun offer(url: String, headers: Map<String, String>, fallback: () -> Unit): Boolean {
        val id = target ?: return false
        val item = Downloads.get(id) ?: run { target = null; return false }
        scope.launch {
            val info = runCatching { com.hwezah.grabber.engine.Http.probe(url, item.headers + headers) }.getOrNull()
            main.post {
                if (info != null && (item.size <= 0 || info.size <= 0 || info.size == item.size)) {
                    Downloads.edit(id) { it.copy(url = info.finalUrl, headers = it.headers + headers, resumable = info.resumable || it.resumable, state = State.Queued, error = null) }
                    Downloads.kick()
                    target = null
                    say("Fresh link found: ${item.name} carries on from ${Fmt.bytes(item.done)}")
                } else {
                    if (info != null) say("That one is ${Fmt.bytes(info.size)}, not ${Fmt.bytes(item.size)}: a different file")
                    fallback()
                }
            }
        }
        return true
    }

    /** How many bytes from the start are all there: what a player can use. */
    fun playable(item: Item): Long {
        val parts = Segments.decode(item.segments).sortedBy { it.start }
        if (parts.isEmpty()) return item.done.coerceAtLeast(0)
        var end = 0L
        for (s in parts) {
            if (s.start > end) break
            end = maxOf(end, s.pos)
            if (s.pos <= s.end) break
        }
        return end
    }

    /**
     * Saves "Name (partial).ext" with the first [playable] bytes, so it can be watched now. The download itself stays
     * as it is, ready to finish when a link works again.
     */
    fun saveCopy(context: Context, item: Item) {
        val app = context.applicationContext
        val doc = item.doc ?: return
        val tree = Prefs.folder ?: return say("Choose a download folder first")
        // Every section downloaded so far, in order; the gaps are left out (players skip over the joins).
        val sections = Segments.sections(item.segments, item.done)
        val n = sections.sumOf { it.last - it.first + 1 }
        if (n <= 0) return say("Nothing to merge yet")
        if (item.id in saving) return
        saving = saving + item.id
        say("Merging…")
        scope.launch {
            val name = item.name.substringBeforeLast('.', item.name) + " (partial)" +
                item.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            val result = runCatching {
                val out = Folders.create(app, tree, item.category, name, browser = item.browser) ?: error("couldn't make the file")
                try {
                    app.contentResolver.openFileDescriptor(Uri.parse(doc), "r")!!.use { pfd ->
                        val input = java.io.FileInputStream(pfd.fileDescriptor).channel
                        val length = input.size()
                        app.contentResolver.openOutputStream(out, "w")!!.buffered(1 shl 16).use { output ->
                            val buf = java.nio.ByteBuffer.allocate(1 shl 16)
                            for (r in sections) {
                                var pos = r.first
                                val end = minOf(r.last + 1, length)
                                while (pos < end) {
                                    buf.clear(); buf.limit(minOf(buf.capacity().toLong(), end - pos).toInt())
                                    val got = input.read(buf, pos)
                                    if (got <= 0) break
                                    output.write(buf.array(), 0, got)
                                    pos += got
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // A half-made copy isn't left behind.
                    runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, out) }
                    throw e
                }
                out
            }
            saving = saving - item.id
            result.onSuccess { out ->
                // Listed as finished, beside the download that carries on.
                main.post { Downloads.addFinished(item.url, name, item.mime, item.category, out.toString(), n) }
                say("Saved $name (${Fmt.bytes(n)}). The download carries on.")
            }
                .onFailure { say("Couldn't merge: ${it.message}") }
        }
    }
}
