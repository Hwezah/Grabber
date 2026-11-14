package com.hwezah.grabber.engine

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class HlsTest {
    @Test
    fun parsesMasterAndMedia() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            https://cdn.x/hi.m3u8
        """.trimIndent()
        val v = Hls.variants("https://site.com/v/master.m3u8", master)
        assertEquals(listOf("1080p", "360p"), v.map { it.label })
        assertEquals("https://site.com/v/low/index.m3u8", v[1].url)

        val media = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:5
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:4.0,
            s0.ts
            #EXTINF:4.0,
            s1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val m = Hls.media("https://site.com/v/low/index.m3u8", media)
        assertEquals(2, m.segments.size)
        assertEquals(6L, m.segments[1].sequence)
        assertEquals("https://site.com/v/low/key.bin", m.segments[0].key?.url)
        assertEquals("ts", m.extension)
    }

    @Test
    fun downloadsAndDecryptsAStream() = runBlocking {
        val key = Random(1).nextBytes(16)
        val plain = List(12) { Random(it).nextBytes(10_000 + it) }
        fun enc(b: ByteArray, seq: Long): ByteArray {
            val iv = java.nio.ByteBuffer.allocate(16).putLong(8, seq).array()
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return c.doFinal(b)
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 32)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        fun serve(path: String, body: ByteArray) = server.createContext(path) { ex ->
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        val playlist = buildString {
            append("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-KEY:METHOD=AES-128,URI=\"/key\"\n")
            plain.indices.forEach { append("#EXTINF:2.0,\n/s$it.ts\n") }
            append("#EXT-X-ENDLIST\n")
        }
        serve("/key", key)
        plain.forEachIndexed { i, b -> serve("/s$i.ts", enc(b, i.toLong())) }
        serve("/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n/media.m3u8\n".toByteArray())
        serve("/media.m3u8", playlist.toByteArray())
        server.start()
        try {
            val out = File.createTempFile("hls", ".ts")
            val work = File(out.parentFile, "hls-work-" + System.nanoTime())
            ChannelSink(RandomAccessFile(out, "rw").channel).use {
                HlsDownload("http://127.0.0.1:${server.address.port}/master.m3u8", emptyMap(), it, work, 6, RateLimiter()).run()
            }
            assertArrayEquals(plain.reduce { a, b -> a + b }, out.readBytes())
        } finally {
            server.stop(0)
        }
    }
}
