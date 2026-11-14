package com.hwezah.grabber.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** What a server says about a link before downloading it. */
data class Info(
    val finalUrl: String,
    val size: Long,
    val resumable: Boolean,
    val name: String,
    val mime: String?,
    val validator: String?,
)

class HttpException(val code: Int, message: String) : IOException(message)

object Http {
    /**
     * HTTP/1.1 only: on HTTP/2 OkHttp would carry every "connection" as a stream over one TCP socket,
     * and the point of many connections is many sockets.
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    /** The user's own User-Agent, or blank for [UA]. */
    @Volatile var userAgent: String = ""

    fun request(url: String, headers: Map<String, String>, range: String? = null): Request {
        val b = Request.Builder().url(url).header("User-Agent", userAgent.ifBlank { UA }).header("Accept-Encoding", "identity")
        headers.forEach { (k, v) -> b.header(k, v) }
        range?.let { b.header("Range", it) }
        return b.build()
    }

    /** Runs [call] and hands its response to [block]; cancelling the coroutine aborts the socket at once. */
    suspend fun <T> execute(call: Call, block: suspend (Response) -> T): T = coroutineScope {
        val watcher = launch(Dispatchers.IO) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) { call.execute().use { block(it) } }
        } finally {
            watcher.cancel()
        }
    }

    /** One small ranged GET: size, whether ranges work, the name, the type. Servers that reject the range get a plain GET. */
    suspend fun probe(url: String, headers: Map<String, String> = emptyMap(), client: OkHttpClient = this.client): Info =
        try {
            probeWith(url, headers, client, "bytes=0-")
        } catch (e: HttpException) {
            if (e.code in setOf(400, 403, 405, 416, 501)) probeWith(url, headers, client, null) else throw e
        }

    private suspend fun probeWith(url: String, headers: Map<String, String>, client: OkHttpClient, range: String?): Info =
        execute(client.newCall(request(url, headers, range))) { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "Server answered ${r.code} ${r.message}".trim())
            val total = totalOf(r)
            val mime = r.header("Content-Type")?.substringBefore(';')?.trim()
            val finalUrl = r.request.url.toString()
            Info(
                finalUrl = finalUrl,
                size = total,
                resumable = r.code == 206 && total > 0 || r.header("Accept-Ranges")?.contains("bytes") == true && total > 0,
                name = Names.fileName(finalUrl, r.header("Content-Disposition"), mime),
                mime = mime,
                validator = validatorOf(r.headers),
            )
        }

    fun totalOf(r: Response): Long {
        if (r.code == 206) {
            r.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()?.let { return it }
        }
        return r.header("Content-Length")?.toLongOrNull() ?: -1
    }

    fun validatorOf(h: Headers) = h["ETag"] ?: h["Last-Modified"]
}

/**
 * One file, fetched over up to [connections] parallel range requests. When a connection finishes its part it
 * takes half of the largest part still going, so all of them stay busy to the end. Progress lives in [segments],
 * which the caller saves ([onCheckpoint]) and passes back to resume.
 */
class HttpDownload(
    private val url: String,
    private val headers: Map<String, String>,
    private val size: Long,
    private val resumable: Boolean,
    private val sink: Sink,
    val segments: MutableList<Segment>,
    private val connections: Int,
    private val limiter: RateLimiter,
    private val onCheckpoint: (List<Segment>) -> Unit = {},
    private val client: OkHttpClient = Http.client,
    private val minSplit: Long = 256 * 1024,
    private val maxRetries: Int = 10,
) {
    private val lock = Any()

    /** Bytes written so far. */
    fun downloaded(): Long = synchronized(lock) {
        if (segments.isEmpty()) 0 else segments.sumOf { it.pos - it.start }
    }

    suspend fun run() {
        if (!resumable || size <= 0) return single()
        synchronized(lock) {
            if (segments.isEmpty()) segments += Segments.plan(size, connections, minSplit * 4)
            segments.forEach { it.busy = false }
        }
        coroutineScope {
            val ticker = launch {
                while (isActive) {
                    delay(1000)
                    checkpoint()
                }
            }
            try {
                coroutineScope { repeat(connections) { launch(Dispatchers.IO) { worker() } } }
            } finally {
                ticker.cancel()
                checkpoint()
            }
        }
        if (downloaded() < size) throw IOException("Download stopped short")
    }

    private fun checkpoint() {
        val copy = synchronized(lock) { segments.map { Segment(it.start, it.end, it.pos) } }
        onCheckpoint(copy)
    }

    /** The next part for an idle connection: one nobody is on, else half of the biggest. */
    private fun take(): Segment? = synchronized(lock) {
        segments.firstOrNull { !it.busy && !it.done }?.let {
            it.busy = true
            return it
        }
        val big = segments.filter { it.busy && !it.done }.maxByOrNull { it.remaining } ?: return null
        if (big.remaining < minSplit * 2) return null
        val mid = big.pos + big.remaining / 2
        val part = Segment(mid, big.end, mid).also { it.busy = true }
        big.end = mid - 1
        segments += part
        segments.sortBy { it.start }
        part
    }

    private suspend fun worker() {
        while (true) {
            val seg = take() ?: return
            var failures = 0
            while (!seg.done) {
                coroutineContext.ensureActive()
                try {
                    val before = seg.pos
                    fetch(seg)
                    if (seg.pos > before) failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpException) {
                    // Too many connections for this server: give the part back, this connection stops.
                    if (e.code == 429 || e.code == 503) {
                        synchronized(lock) { seg.busy = false }
                        if (segments.count { it.busy } > 0) return
                    }
                    if (++failures > maxRetries || e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                    delay(backoff(failures))
                } catch (e: IOException) {
                    if (++failures > maxRetries) throw e
                    delay(backoff(failures))
                }
            }
            synchronized(lock) { seg.busy = false }
        }
    }

    private fun backoff(n: Int) = minOf(30_000L, 500L shl minOf(n, 6))

    private suspend fun fetch(seg: Segment) {
        val call = client.newCall(Http.request(url, headers, "bytes=${seg.pos}-${seg.end}"))
        Http.execute(call) { r ->
            if (r.code != 206) {
                if (r.isSuccessful) throw IOException("The server stopped allowing parts (answered ${r.code})")
                throw HttpException(r.code, "Server answered ${r.code}")
            }
            val total = Http.totalOf(r)
            if (total > 0 && total != size) throw HttpException(412, "The file changed on the server")
            val source = r.body!!.source()
            val buf = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = source.read(buf)
                if (n <= 0) break
                val (at, take) = synchronized(lock) { seg.pos to minOf(n.toLong(), seg.remaining).toInt() }
                if (take <= 0) break
                limiter.take(take)
                sink.write(at, ByteBuffer.wrap(buf, 0, take))
                synchronized(lock) { seg.pos += take }
                if (take < n || seg.done) break
            }
        }
    }

    /** No ranges (or no size): one connection from the start; a failure starts over. */
    private suspend fun single() {
        var failures = 0
        while (true) {
            try {
                sink.truncate(0)
                synchronized(lock) {
                    segments.clear()
                    segments += Segment(0, Long.MAX_VALUE - 1, 0)
                }
                Http.execute(client.newCall(Http.request(url, headers))) { r ->
                    if (!r.isSuccessful) throw HttpException(r.code, "Server answered ${r.code}")
                    val source = r.body!!.source()
                    val buf = ByteArray(64 * 1024)
                    val seg = segments[0]
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = source.read(buf)
                        if (n <= 0) break
                        limiter.take(n)
                        sink.write(seg.pos, ByteBuffer.wrap(buf, 0, n))
                        synchronized(lock) { seg.pos += n }
                    }
                    synchronized(lock) { seg.end = seg.pos - 1 }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (++failures > 3 || e is HttpException && e.code in 400..499) throw e
                delay(backoff(failures))
            }
        }
    }
}
