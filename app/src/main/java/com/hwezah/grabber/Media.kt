package com.hwezah.grabber

import android.content.Context
import android.net.Uri
import com.hwezah.grabber.engine.Category
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What a page offers: its title, the video heights there are, and whether it's audio-only. */
data class MediaInfo(val title: String, val heights: List<Int>, val duration: Int, val extractor: String?)

/**
 * Video and audio from YouTube and ~1,800 other sites, through yt-dlp (bundled with Python), and
 * FFmpeg to merge streams and convert to MP3 / M4A / Opus.
 */
object Media {
    @Volatile private var ready = false

    /** Unpacks yt-dlp and FFmpeg the first time (a few seconds). Blocking. */
    @Synchronized
    fun ensure(context: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(context.applicationContext)
        FFmpeg.getInstance().init(context.applicationContext)
        ready = true
    }

    private val SITES = listOf(
        "youtube.com", "youtu.be", "tiktok.com", "instagram.com", "facebook.com", "fb.watch", "x.com", "twitter.com",
        "vimeo.com", "dailymotion.com", "reddit.com", "soundcloud.com", "twitch.tv", "bilibili.com", "pinterest.",
    )

    /** Links to pages yt-dlp knows, worth asking before trying a plain download. */
    fun looksLikeSite(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
        return SITES.any { host == it || host.endsWith(".$it") || host.contains(it) }
    }

    fun info(context: Context, url: String): MediaInfo {
        ensure(context)
        val req = YoutubeDLRequest(url).addOption("--no-playlist")
        val v: VideoInfo = YoutubeDL.getInstance().getInfo(req)
        val heights = v.formats.orEmpty().filter { it.vcodec != null && it.vcodec != "none" && it.height > 0 }
            .map { it.height }.distinct().sortedDescending()
        return MediaInfo(v.title ?: v.fulltitle ?: "video", heights, v.duration, v.extractorKey)
    }

    /** Settings: bring yt-dlp up to date when sites change. Returns the version now installed. */
    fun update(context: Context): String {
        ensure(context)
        YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
        return YoutubeDL.getInstance().version(context.applicationContext) ?: "?"
    }

    fun version(context: Context): String? = runCatching { ensure(context); YoutubeDL.getInstance().version(context.applicationContext) }.getOrNull()

    class Progress(val pct: Float, val eta: Long, val line: String)

    /**
     * Downloads [item] with yt-dlp into a work folder (which keeps partial files, so a pause resumes),
     * then moves the result into the download folder. Returns (document URI, file name, size).
     */
    suspend fun run(context: Context, item: Item, onProgress: (Progress) -> Unit): Triple<String, String, Long> {
        ensure(context)
        val work = File(context.filesDir, "work/media/${item.id}").apply { mkdirs() }
        val req = YoutubeDLRequest(item.url)
            .addOption("--no-playlist")
            .addOption("--no-mtime")
            .addOption("-N", 8)
            .addOption("-o", File(work, "%(title).120B.%(ext)s").absolutePath)
        item.headers.forEach { (k, v) -> req.addOption("--add-header", "$k:$v") }
        if (item.url.startsWith("file://")) req.addOption("--enable-file-urls")
        val f = item.format
        when {
            f.startsWith("a:") -> {
                req.addOption("-f", "ba/b").addOption("-x").addOption("--audio-format", f.removePrefix("a:")).addOption("--audio-quality", "0")
            }
            else -> {
                val h = f.removePrefix("v:").toIntOrNull()
                val sel = if (h != null) "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b" else "bv*+ba/b"
                req.addOption("-f", sel).addOption("--merge-output-format", "mp4")
            }
        }
        val pid = "grab-${item.id}"
        suspendCancellableCoroutine<Unit> { cont ->
            val t = thread(name = pid) {
                try {
                    val r = YoutubeDL.getInstance().execute(req, pid) { pct, eta, line -> onProgress(Progress(pct, eta, line)) }
                    if (r.exitCode != 0) throw IllegalStateException(r.err.lineSequence().lastOrNull { it.isNotBlank() } ?: "yt-dlp failed")
                    if (cont.isActive) cont.resume(Unit)
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(cleanError(e.message), e))
                }
            }
            cont.invokeOnCancellation {
                YoutubeDL.getInstance().destroyProcessById(pid)
                t.interrupt()
            }
        }
        val out = work.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !Regex("""\.f\d+\.""").containsMatchIn(it.name) }
            .maxByOrNull { it.length() }
            ?: work.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".part") }.maxByOrNull { it.length() }
            ?: error("yt-dlp finished but no file was made")
        val tree = Prefs.folder ?: error("Choose a download folder in Settings")
        val cat = if (f.startsWith("a:")) Category.Audio else Category.Video
        val doc = Folders.create(context, tree, cat, out.name) ?: error("Can't write to the download folder")
        context.contentResolver.openOutputStream(doc)?.use { o -> out.inputStream().use { it.copyTo(o, 256 * 1024) } }
        val size = out.length()
        val name = doc.lastPathSegment?.substringAfterLast('/') ?: out.name
        work.deleteRecursively()
        return Triple(doc.toString(), name, size)
    }

    /** Copies a downloaded file into the cache so yt-dlp/FFmpeg can read it, for converting to audio. */
    fun stage(context: Context, doc: String, name: String): File {
        val f = File(context.cacheDir, "convert/${System.currentTimeMillis()}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        f.parentFile?.mkdirs()
        context.contentResolver.openInputStream(Uri.parse(doc))?.use { i -> f.outputStream().use { i.copyTo(it, 256 * 1024) } } ?: error("Can't read the file")
        return f
    }

    private fun cleanError(m: String?): String {
        val line = m.orEmpty().lineSequence().lastOrNull { it.contains("ERROR") } ?: m.orEmpty().lineSequence().lastOrNull { it.isNotBlank() } ?: "Download failed"
        return line.substringAfter("ERROR: ").take(200)
    }
}
