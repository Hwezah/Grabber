package com.hwezah.grabber.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/** One quality of an HLS stream (from a master playlist). */
data class Variant(val url: String, val bandwidth: Long, val resolution: String?, val name: String?) {
    val label get() = resolution?.substringAfter('x')?.let { "${it}p" } ?: name ?: "${bandwidth / 1000} kbps"
}

data class Key(val method: String, val url: String?, val iv: ByteArray?)

data class Seg(val url: String, val key: Key?, val sequence: Long)

data class MediaPlaylist(val segments: List<Seg>, val init: String?, val duration: Double) {
    /** fMP4 segments join into an .mp4; transport-stream ones into a .ts. */
    val extension get() = if (init != null) "mp4" else "ts"
}

object Hls {
    fun isPlaylist(url: String, mime: String?) =
        mime?.contains("mpegurl", true) == true || url.substringBefore('?').endsWith(".m3u8", true)

    private fun resolve(base: String, ref: String) = runCatching { base.toHttpUrl().resolve(ref)?.toString() }.getOrNull() ?: ref

    /** Attributes of a tag line: KEY=value,KEY="quoted, value". */
    fun attrs(line: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""").findAll(line.substringAfter(':')).forEach {
            out[it.groupValues[1]] = it.groupValues[2].trim('"')
        }
        return out
    }

    /** The qualities in a master playlist, best first; empty if [text] is a media playlist. */
    fun variants(base: String, text: String): List<Variant> {
        val lines = text.lines().map { it.trim() }
        val out = ArrayList<Variant>()
        lines.forEachIndexed { i, l ->
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val a = attrs(l)
                val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
                out += Variant(resolve(base, uri), a["BANDWIDTH"]?.toLongOrNull() ?: 0, a["RESOLUTION"], a["NAME"])
            }
        }
        return out.distinctBy { it.url }.sortedByDescending { it.bandwidth }
    }

    fun media(base: String, text: String): MediaPlaylist {
        var key: Key? = null
        var seq = 0L
        var init: String? = null
        var duration = 0.0
        var pendingDuration = 0.0
        val segs = ArrayList<Seg>()
        text.lines().map { it.trim() }.forEach { l ->
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE") -> seq = l.substringAfter(':').trim().toLongOrNull() ?: 0
                l.startsWith("#EXT-X-KEY") -> {
                    val a = attrs(l)
                    val method = a["METHOD"] ?: "NONE"
                    key = if (method == "NONE") null else Key(method, a["URI"]?.let { resolve(base, it) }, a["IV"]?.let { hex(it) })
                }
                l.startsWith("#EXT-X-MAP") -> init = attrs(l)["URI"]?.let { resolve(base, it) }
                l.startsWith("#EXTINF") -> pendingDuration = l.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                l.isNotEmpty() && !l.startsWith("#") -> {
                    segs += Seg(resolve(base, l), key, seq + segs.size)
                    duration += pendingDuration
                    pendingDuration = 0.0
                }
            }
        }
        return MediaPlaylist(segs, init, duration)
    }

    private fun hex(s: String): ByteArray {
        val h = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun ivFor(seg: Seg): ByteArray = seg.key?.iv ?: ByteBuffer.allocate(16).putLong(8, seg.sequence).array()

    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    suspend fun text(url: String, headers: Map<String, String>, client: OkHttpClient = Http.client): String =
        Http.execute(client.newCall(Http.request(url, headers))) { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "Server answered ${r.code}")
            r.body!!.string()
        }

    suspend fun bytes(url: String, headers: Map<String, String>, client: OkHttpClient = Http.client): ByteArray =
        Http.execute(client.newCall(Http.request(url, headers))) { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "Server answered ${r.code}")
            r.body!!.bytes()
        }
}

/**
 * A whole HLS stream into one file: segments fetched [connections] at a time into [work] (finished ones
 * survive a pause or the app being killed), decrypted if AES-128, then joined in order into [sink].
 */
class HlsDownload(
    private val url: String,
    private val headers: Map<String, String>,
    private val sink: Sink,
    private val work: File,
    private val connections: Int,
    private val limiter: RateLimiter,
    private val client: OkHttpClient = Http.client,
) {
    private val bytes = AtomicLong()
    private val doneSegs = AtomicInteger()
    @Volatile var total = 0
        private set

    fun downloaded() = bytes.get()
    fun segmentsDone() = doneSegs.get()

    suspend fun run() {
        work.mkdirs()
        var playlistUrl = url
        var text = Hls.text(url, headers, client)
        Hls.variants(url, text).firstOrNull()?.let {
            playlistUrl = it.url
            text = Hls.text(it.url, headers, client)
        }
        val list = Hls.media(playlistUrl, text)
        if (list.segments.isEmpty()) throw IOException("The stream has no segments")
        total = list.segments.size
        val keys = HashMap<String, ByteArray>()
        val keyLock = Mutex()
        suspend fun keyFor(k: Key): ByteArray = keyLock.withLock {
            keys.getOrPut(k.url!!) { Hls.bytes(k.url, headers, client) }
        }
        list.segments.indices.forEach { i ->
            val f = File(work, "%06d".format(i))
            if (f.exists()) { doneSegs.incrementAndGet(); bytes.addAndGet(f.length()) }
        }
        val next = AtomicInteger(0)
        coroutineScope {
            repeat(connections.coerceIn(1, 32)) {
                launch(Dispatchers.IO) {
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= list.segments.size) break
                        val out = File(work, "%06d".format(i))
                        if (out.exists()) continue
                        val seg = list.segments[i]
                        var failures = 0
                        while (true) {
                            coroutineContext.ensureActive()
                            try {
                                var data = Hls.bytes(seg.url, headers, client)
                                limiter.take(data.size)
                                val k = seg.key
                                if (k != null) {
                                    if (k.method != "AES-128" || k.url == null) throw IOException("This stream uses ${k.method} encryption, which can't be downloaded")
                                    data = Hls.decrypt(data, keyFor(k), Hls.ivFor(seg))
                                }
                                val part = File(work, "%06d.part".format(i))
                                part.writeBytes(data)
                                part.renameTo(out)
                                bytes.addAndGet(data.size.toLong())
                                doneSegs.incrementAndGet()
                                break
                            } catch (e: IOException) {
                                if (++failures > 8 || e is HttpException && e.code in 400..499 && e.code != 429) throw e
                                delay(minOf(20_000L, 500L shl minOf(failures, 6)))
                            }
                        }
                    }
                }
            }
        }
        // Join: the init section first for fMP4, then every segment in order.
        sink.truncate(0)
        var pos = 0L
        list.init?.let {
            val b = Hls.bytes(it, headers, client)
            sink.write(pos, ByteBuffer.wrap(b)); pos += b.size
        }
        list.segments.indices.forEach { i ->
            coroutineContext.ensureActive()
            val b = File(work, "%06d".format(i)).readBytes()
            sink.write(pos, ByteBuffer.wrap(b)); pos += b.size
        }
        work.deleteRecursively()
    }
}
