package com.hwezah.grabber.engine

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class HttpDownloadTest {
    private val data = Random(7).nextBytes(5 * 1024 * 1024 + 123)
    private lateinit var server: HttpServer
    private val ranged = AtomicInteger()
    private var allowRanges = true
    private lateinit var url: String

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 64)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.createContext("/file.bin") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            val out = ex.responseBody
            if (range != null && allowRanges) {
                ranged.incrementAndGet()
                val (a, b) = range.removePrefix("bytes=").split("-")
                val s = a.toLong()
                val e = if (b.isEmpty()) data.size - 1L else minOf(b.toLong(), data.size - 1L)
                ex.responseHeaders.add("Content-Range", "bytes $s-$e/${data.size}")
                ex.sendResponseHeaders(206, e - s + 1)
                try {
                    var p = s.toInt()
                    while (p <= e) {
                        val n = minOf(16 * 1024, (e - p + 1).toInt())
                        out.write(data, p, n)
                        p += n
                    }
                } catch (_: Exception) {
                }
            } else {
                ex.sendResponseHeaders(200, data.size.toLong())
                runCatching { out.write(data) }
            }
            runCatching { out.close() }
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}/file.bin"
    }

    @After
    fun stop() = server.stop(0)

    private fun sinkFor(f: File) = ChannelSink(RandomAccessFile(f, "rw").channel)

    @Test
    fun probeSeesSizeAndRanges() = runBlocking {
        val info = Http.probe(url)
        assertEquals(data.size.toLong(), info.size)
        assertTrue(info.resumable)
        assertEquals("file.bin", info.name)
    }

    @Test
    fun manyConnectionsMakeTheSameFile() = runBlocking {
        val f = File.createTempFile("grab", ".bin")
        val segs = mutableListOf<Segment>()
        sinkFor(f).use { HttpDownload(url, emptyMap(), data.size.toLong(), true, it, segs, 32, RateLimiter()).run() }
        assertArrayEquals(data, f.readBytes())
        assertTrue("used several ranged requests", ranged.get() >= 4)
    }

    @Test
    fun resumesFromSavedSegments() = runBlocking {
        val f = File.createTempFile("grab", ".bin")
        var saved = ""
        val limiter = RateLimiter(1024 * 1024)
        val job = launch {
            sinkFor(f).use {
                HttpDownload(url, emptyMap(), data.size.toLong(), true, it, mutableListOf(), 8, limiter, { s -> saved = Segments.encode(s) }).run()
            }
        }
        delay(1500)
        job.cancel()
        job.join()
        val segs = Segments.decode(saved)
        val done = segs.sumOf { it.pos - it.start }
        assertTrue("part done before pause: $done", done in 1 until data.size)
        sinkFor(f).use { HttpDownload(url, emptyMap(), data.size.toLong(), true, it, segs, 8, RateLimiter()).run() }
        assertArrayEquals(data, f.readBytes())
    }

    @Test
    fun stalledConnectionIsReplaced() = runBlocking {
        // The first request for the middle of the file hangs after a little data; the stall watch must move on.
        val hung = java.util.concurrent.atomic.AtomicBoolean(false)
        server.removeContext("/file.bin")
        server.createContext("/file.bin") { ex ->
            val (a, b) = ex.requestHeaders.getFirst("Range").removePrefix("bytes=").split("-")
            val s = a.toLong()
            val e = if (b.isEmpty()) data.size - 1L else minOf(b.toLong(), data.size - 1L)
            ex.responseHeaders.add("Content-Range", "bytes $s-$e/${data.size}")
            ex.sendResponseHeaders(206, e - s + 1)
            val out = ex.responseBody
            try {
                if (s > 0 && hung.compareAndSet(false, true)) {
                    out.write(data, s.toInt(), 1000); out.flush()
                    Thread.sleep(20_000)
                }
                out.write(data, s.toInt(), (e - s + 1).toInt())
            } catch (_: Exception) {
            }
            runCatching { out.close() }
        }
        val f = File.createTempFile("grab", ".bin")
        val t0 = System.currentTimeMillis()
        sinkFor(f).use {
            HttpDownload(url, emptyMap(), data.size.toLong(), true, it, mutableListOf(), 4, RateLimiter(), stallMs = 2_000).run()
        }
        assertArrayEquals(data, f.readBytes())
        assertTrue("finished well before the hung connection would have", System.currentTimeMillis() - t0 < 15_000)
    }

    @Test
    fun noRangesFallsBackToOneConnection() = runBlocking {
        allowRanges = false
        val info = Http.probe(url)
        assertEquals(false, info.resumable)
        val f = File.createTempFile("grab", ".bin")
        sinkFor(f).use { HttpDownload(url, emptyMap(), info.size, false, it, mutableListOf(), 32, RateLimiter()).run() }
        assertArrayEquals(data, f.readBytes())
    }
}
