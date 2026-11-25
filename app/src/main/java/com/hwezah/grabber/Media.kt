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
    @Volatile private var aria2c = false

    /** Unpacks yt-dlp and FFmpeg the first time (a few seconds). Blocking. */
    @Synchronized
    fun ensure(context: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(context.applicationContext)
        FFmpeg.getInstance().init(context.applicationContext)
        // aria2c is optional: if it won't start, yt-dlp downloads by itself.
        aria2c = runCatching {
            val c = Class.forName("com.yausername.aria2c.Aria2c")
            val inst = c.getMethod("getInstance").invoke(null)
            c.getMethod("init", Context::class.java).invoke(inst, context.applicationContext)
            true
        }.getOrDefault(false)
        ready = true
        // Sites (Instagram most) change often: keep yt-dlp on its nightly build, checked once a day.
        val sp = context.getSharedPreferences("media", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - sp.getLong("updated", 0) > 24L * 3600 * 1000) {
            thread(name = "yt-dlp-update") {
                runCatching {
                    YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
                    sp.edit().putLong("updated", System.currentTimeMillis()).apply()
                }
            }
        }
    }

    private val SITES = listOf(
        "youtube.com", "youtu.be", "tiktok.com", "instagram.com", "facebook.com", "fb.watch", "x.com", "twitter.com",
        "vimeo.com", "dailymotion.com", "reddit.com", "redd.it", "soundcloud.com", "twitch.tv", "bilibili.com", "pinterest.", "pin.it",
        "threads.net", "snapchat.com", "linkedin.com", "tumblr.com", "vk.com", "likee.video", "kwai.com", "bsky.app", "rumble.com", "streamable.com",
    )

    /** Links to pages yt-dlp knows, worth asking before trying a plain download. */
    fun looksLikeSite(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
        return SITES.any { host == it || host.endsWith(".$it") || host.contains(it) }
    }

    fun info(context: Context, url: String): MediaInfo {
        ensure(context)
        fun ask(cookies: File?): VideoInfo {
            val req = YoutubeDLRequest(url).addOption("--no-playlist").addOption("--no-update")
            cookies?.let { req.addOption("--cookies", it.absolutePath) }
            return try { YoutubeDL.getInstance().getInfo(req) } finally { cookies?.delete() }
        }
        // Public posts need no sign-in; only when the site asks for one is the browser's sign-in used, once.
        val v: VideoInfo = try {
            ask(null)
        } catch (e: Exception) {
            val cookies = if (needsSignIn(cleanError(e.message))) SignIns.cookiesFile(context, url) else null
            if (cookies == null) throw IllegalStateException(cleanError(e.message), e)
            try { ask(cookies) } catch (e2: Exception) { throw IllegalStateException(cleanError(e2.message), e2) }
        }
        val heights = v.formats.orEmpty().filter { it.vcodec != null && it.vcodec != "none" && it.height > 0 }
            .map { it.height }.distinct().sortedDescending()
        return MediaInfo(v.title ?: v.fulltitle ?: "video", heights, v.duration, v.extractorKey)
    }

    /** Settings: bring yt-dlp up to date when sites change. Returns the version now installed. */
    fun update(context: Context): String {
        ensure(context)
        YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
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
        // The name the user gave (without a media extension), else the site's title.
        val base = item.fixedName?.takeIf { it.isNotBlank() }?.let {
            com.hwezah.grabber.engine.Names.clean(it).replace(Regex("""\.(mp4|mkv|ts|webm|mov|mp3|m4a|opus|m3u8)$""", RegexOption.IGNORE_CASE), "").replace("%", "%%")
        }?.takeIf { it.isNotBlank() }
        val req = YoutubeDLRequest(item.url)
            .addOption("--no-playlist")
            .addOption("--no-mtime")
            .addOption("--no-update")
            .addOption("-N", if (item.kind == Kind.Hls) Prefs.connections.coerceIn(4, 16) else 8)
            .addOption("-o", File(work, (base ?: "%(title).120B") + ".%(ext)s").absolutePath)
        item.headers.forEach { (k, v) -> req.addOption("--add-header", "$k:$v") }
        // Many connections per file through aria2c, sized like Grabber's own downloads.
        if (aria2c && !item.url.startsWith("file://") && item.kind != Kind.Hls) {
            val n = Prefs.connections.coerceIn(4, 16)
            req.addOption("--downloader", "libaria2c.so")
            req.addOption("--downloader-args", "aria2c:-x $n -s $n -k 1M --summary-interval=1 --console-log-level=warn --file-allocation=none")
        }
        if (item.url.startsWith("file://")) req.addOption("--enable-file-urls")
        val f = item.format
        when {
            f.startsWith("a:") -> {
                req.addOption("-f", "ba/b").addOption("-x").addOption("--audio-format", f.removePrefix("a:")).addOption("--audio-quality", "0")
            }
            else -> {
                val h = f.removePrefix("v:").toIntOrNull()
                val sel = if (h != null) "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b" else "bv*+ba/b"
                // At the chosen size, H.264 + AAC first: the codecs every phone plays (TikTok often sends HEVC).
                req.addOption("-f", sel).addOption("--merge-output-format", "mp4")
                    .addOption("-S", (if (h != null) "res:$h," else "") + "vcodec:h264,acodec:aac,ext:mp4:m4a")
            }
        }
        val pid = "grab-${item.id}"
        suspendCancellableCoroutine<Unit> { cont ->
            val t = thread(name = pid) {
                try {
                    fun go(): String? {
                        val r = YoutubeDL.getInstance().execute(req, pid) { pct, eta, line -> onProgress(Progress(pct, eta, line)) }
                        return if (r.exitCode != 0) r.err.lineSequence().lastOrNull { it.isNotBlank() } ?: "yt-dlp failed" else null
                    }
                    // Signed out first; the browser's sign-in only if the site asks for one.
                    val failed = runCatching { go() }.fold({ it }, { it.message ?: "yt-dlp failed" })
                    if (failed != null) {
                        val cookies = if (needsSignIn(cleanError(failed))) SignIns.cookiesFile(context, item.url) else null
                        if (cookies == null) throw IllegalStateException(failed)
                        req.addOption("--cookies", cookies.absolutePath)
                        try { go()?.let { throw IllegalStateException(it) } } finally { cookies.delete() }
                    }
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
        // Copied in as a .part, named properly once it's all there.
        val part = Folders.create(context, tree, cat, out.name, part = true, browser = item.browser) ?: error("Can't write to the download folder")
        context.contentResolver.openOutputStream(part)?.use { o -> out.inputStream().use { it.copyTo(o, 256 * 1024) } }
        val size = out.length()
        val (doc, name) = Folders.finish(context, part.toString(), out.name, youtube = cat == com.hwezah.grabber.engine.Category.Video && item.kind == Kind.Media && looksLikeSite(item.url))
        work.deleteRecursively()
        return Triple(doc, name, size)
    }

    /** Copies a downloaded file into the cache so yt-dlp/FFmpeg can read it, for converting to audio. */
    fun stage(context: Context, doc: String, name: String): File {
        val f = File(context.cacheDir, "convert/${System.currentTimeMillis()}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        f.parentFile?.mkdirs()
        context.contentResolver.openInputStream(Uri.parse(doc))?.use { i -> f.outputStream().use { i.copyTo(it, 256 * 1024) } } ?: error("Can't read the file")
        return f
    }

    /** Errors that mean "sign in and it will work". */
    fun needsSignIn(message: String?): Boolean {
        val m = message.orEmpty().lowercase()
        return listOf("login", "log in", "sign in", "signed in", "available to everyone", "certain audiences", "private", "age", "cookies", "rate-limit", "inappropriate").any { it in m }
    }

    /** yt-dlp's last ERROR line, without warnings, its [extractor] tag or the Python cause. */
    fun cleanError(m: String?): String {
        val lines = m.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("WARNING") }.toList()
        val line = lines.lastOrNull { it.contains("ERROR") } ?: lines.lastOrNull() ?: "Download failed"
        return line.substringAfter("ERROR: ").replace(Regex("""^\[[^\]]+\]\s*"""), "").replace(Regex("""\s*\(caused by.*$"""), "").take(200)
    }
}
