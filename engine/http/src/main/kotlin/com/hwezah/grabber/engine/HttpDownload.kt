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
    suspend fun probe(url: String, headers: Map<String, String> = emptyMap(), client: OkHttpClient = this.client): Info {
        val first = try {
            probeWith(url, headers, client, "bytes=0-")
        } catch (e: HttpException) {
            if (e.code in setOf(400, 403, 405, 416, 501)) probeWith(url, headers, client, null) else throw e
        }
        if (first.resumable) return first
        // Some servers ignore an open "from 0" range but honour an exact one: ask for a single byte.
        val exact = runCatching { probeWith(first.finalUrl, headers, client, "bytes=1-1") }.getOrNull()
        return if (exact != null && exact.resumable) first.copy(resumable = true, size = if (first.size > 0) first.size else exact.size) else first
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
                resumable = r.code == 206 && total > 0 && r.header("Content-Range") != null || r.header("Accept-Ranges")?.contains("bytes") == true && total > 0,
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
 * One file over many range connections, the way IDM/1DM do it, plus:
 * - work stealing: an idle connection takes half of the biggest part still going;
 * - ramp-up: starts with a few connections and adds more while total speed keeps rising, up to [connections];
 * - stall watch: a connection that sends nothing for [stallMs], or crawls far below the others, is dropped and
 *   its part handed on (it reconnects, often to a faster path);
 * - batched writes: each connection writes in 512 KB blocks.
 * Progress lives in [segments], which the caller saves ([onCheckpoint]) and passes back to resume.
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
    private val stallMs: Long = 8_000,
    private val startWith: Int = 4,
) {
    private val lock = Any()

    /** A live connection, for the stall watch. */
    private class Conn(val call: Call) {
        @Volatile var lastByte = System.nanoTime()
        @Volatile var bytes = 0L
        @Volatile var mark = 0L
        @Volatile var speed = 0L
        @Volatile var kicked = false
        val born = System.nanoTime()
    }
    private val conns = java.util.concurrent.ConcurrentHashMap.newKeySet<Conn>()
    private val alive = java.util.concurrent.atomic.AtomicInteger()
    /** A connection was refused: no more ramping up for this download. */
    @Volatile private var refused = false

    /** Bytes written so far (batched bytes count once written). */
    fun downloaded(): Long = synchronized(lock) {
        if (segments.isEmpty()) 0 else segments.sumOf { it.pos - it.start }
    }

    /** Connections open right now. */
    fun active() = conns.size

    suspend fun run() {
        if (!resumable || size <= 0) return single()
        val fresh: Boolean
        synchronized(lock) {
            fresh = segments.isEmpty()
            if (fresh) segments += Segments.plan(size, minOf(connections, startWith), minSplit * 4)
            segments.forEach { it.busy = false; it.pending = 0 }
        }
        coroutineScope {
            val ticker = launch {
                while (isActive) {
                    delay(1000)
                    checkpoint()
                }
            }
            val watch = launch { watchdog() }
            try {
                coroutineScope {
                    val first = minOf(connections, startWith)
                    repeat(first) { spawn(this) }
                    launch { rampUp(this@coroutineScope, first) }
                }
            } finally {
                ticker.cancel()
                watch.cancel()
                checkpoint()
            }
        }
        if (downloaded() < size) throw IOException("Download stopped short")
    }

    private fun spawn(scope: kotlinx.coroutines.CoroutineScope) {
        alive.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            try { worker() } finally { alive.decrementAndGet() }
        }
    }

    /** Adds connections in steps while the total speed keeps climbing; stops when it levels off. */
    private suspend fun rampUp(scope: kotlinx.coroutines.CoroutineScope, first: Int) {
        var running = first
        var last = 0L
        var flat = 0
        while (running < connections) {
            delay(2500)
            if (refused) return
            val nothingToSplit = synchronized(lock) { segments.all { it.done || it.remaining < minSplit * 2 && it.busy } }
            if (alive.get() == 0 || nothingToSplit) return
            val now = conns.sumOf { it.speed }
            if (last > 0 && now < last * 11 / 10) { if (++flat >= 2) return } else flat = 0
            last = now
            val add = minOf(connections - running, maxOf(2, running / 2))
            repeat(add) { spawn(scope) }
            running += add
        }
    }

    /** Every second: drop connections that went silent, or crawl at under a tenth of the typical speed. */
    private suspend fun watchdog() {
        while (true) {
            delay(1000)
            val now = System.nanoTime()
            conns.forEach { c ->
                c.speed = c.bytes - c.mark
                c.mark = c.bytes
            }
            val speeds = conns.filter { now - it.born > 6_000_000_000L }.map { it.speed }.sorted()
            val typical = if (speeds.size >= 4) speeds[speeds.size / 2] else 0L
            conns.forEach { c ->
                val silent = now - c.lastByte > stallMs * 1_000_000L
                val crawling = typical > 64 * 1024 && now - c.born > 10_000_000_000L && c.speed < typical / 10
                if ((silent || crawling) && !c.kicked) {
                    c.kicked = true
                    c.call.cancel()
                }
            }
        }
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
        val mid = big.pos + big.pending + big.remaining / 2
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
            var stalls = 0
            while (!seg.done) {
                coroutineContext.ensureActive()
                var kicked = false
                try {
                    val before = seg.pos
                    kicked = fetch(seg)
                    if (seg.pos > before) failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpException) {
                    // The server refused this connection (too many, a picky CDN node, a rate limit): give the part
                    // back and close this connection while others still work; they pick the part up.
                    if (e.code == 429 || e.code == 503 || e.code in 400..499) {
                        refused = true
                        val othersWorking = synchronized(lock) {
                            seg.busy = false
                            segments.any { it.busy }
                        }
                        if (othersWorking) return
                        synchronized(lock) { seg.busy = true }
                    }
                    if (++failures > maxRetries || e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                    delay(backoff(failures))
                } catch (e: IOException) {
                    // Dropped by the stall watch: straight back with a new connection, not counted as a failure.
                    if (e is StallException && ++stalls <= 30) continue
                    if (++failures > maxRetries) throw e
                    delay(backoff(failures))
                }
                if (kicked) continue
            }
            synchronized(lock) { seg.busy = false }
        }
    }

    private class StallException : IOException("Connection stalled")

    private fun backoff(n: Int) = minOf(30_000L, 500L shl minOf(n, 6))

    /** Reads [seg] over one connection, writing in batches. Returns true if the stall watch dropped it. */
    private suspend fun fetch(seg: Segment): Boolean {
        val call = client.newCall(Http.request(url, headers, "bytes=${seg.pos}-${seg.end}"))
        val conn = Conn(call)
        conns += conn
        try {
            Http.execute(call) { r ->
                if (r.code != 206) {
                    if (r.isSuccessful) throw IOException("The server stopped allowing parts (answered ${r.code})")
                    throw HttpException(r.code, "Server answered ${r.code}")
                }
                val total = Http.totalOf(r)
                if (total > 0 && total != size) throw HttpException(412, "The file changed on the server")
                val source = r.body!!.source()
                val block = ByteArray(BLOCK)
                var fill = 0
                fun flush() {
                    if (fill == 0) return
                    val at = synchronized(lock) { seg.pos }
                    sink.write(at, ByteBuffer.wrap(block, 0, fill))
                    synchronized(lock) {
                        seg.pos += fill
                        seg.pending = 0
                    }
                    fill = 0
                }
                try {
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = source.read(block, fill, minOf(64 * 1024, BLOCK - fill))
                        if (n <= 0) break
                        conn.lastByte = System.nanoTime()
                        conn.bytes += n
                        val take = synchronized(lock) {
                            val room = seg.end - (seg.pos + fill) + 1
                            val t = minOf(n.toLong(), room).toInt().coerceAtLeast(0)
                            seg.pending = (fill + t).toLong()
                            t
                        }
                        limiter.take(take)
                        fill += take
                        val reachedEnd = synchronized(lock) { seg.pos + fill > seg.end }
                        if (fill >= BLOCK - 64 * 1024 || take < n || reachedEnd) flush()
                        if (take < n || reachedEnd) break
                    }
                } finally {
                    // Whatever arrived before a drop is kept.
                    runCatching { flush() }
                    synchronized(lock) { seg.pending = 0 }
                }
            }
        } catch (e: IOException) {
            if (conn.kicked) throw StallException()
            throw e
        } finally {
            conns -= conn
        }
        return conn.kicked
    }

    /**
     * No confirmed ranges (or no size): one connection. Still, on a resume it asks to continue from where it
     * stopped; only a server that sends the whole file again makes it start over.
     */
    private suspend fun single() {
        var failures = 0
        synchronized(lock) {
            if (segments.size != 1 || segments[0].start != 0L) {
                segments.clear()
                segments += Segment(0, Long.MAX_VALUE - 1, 0)
            } else segments[0].end = Long.MAX_VALUE - 1
        }
        coroutineScope {
            val ticker = launch {
                while (isActive) {
                    delay(1000)
                    checkpoint()
                }
            }
            try {
                while (true) {
                    try {
                        val seg = segments[0]
                        val from = seg.pos
                        Http.execute(client.newCall(Http.request(url, headers, if (from > 0) "bytes=$from-" else null))) { r ->
                            if (!r.isSuccessful) throw HttpException(r.code, "Server answered ${r.code}")
                            val continues = from > 0 && r.code == 206 && r.header("Content-Range")?.startsWith("bytes $from-") == true
                            if (!continues) {
                                // The whole file again: start over.
                                sink.truncate(0)
                                synchronized(lock) { seg.pos = 0 }
                            }
                            val source = r.body!!.source()
                            val buf = ByteArray(BLOCK)
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
                        return@coroutineScope
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        if (++failures > 3 || e is HttpException && e.code in 400..499) throw e
                        delay(backoff(failures))
                    }
                }
            } finally {
                ticker.cancel()
                checkpoint()
            }
        }
    }

    companion object {
        /** Each connection's write batch. */
        const val BLOCK = 512 * 1024
    }
}
