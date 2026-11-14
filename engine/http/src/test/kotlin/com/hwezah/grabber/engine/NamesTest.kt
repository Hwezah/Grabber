package com.hwezah.grabber.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class NamesTest {
    @Test
    fun names() {
        assertEquals("My Video.mp4", Names.fileName("https://x.com/a/My%20Video.mp4?token=1", null, null))
        assertEquals("report.pdf", Names.fileName("https://x.com/dl", "attachment; filename=\"report.pdf\"", null))
        assertEquals("naïve.txt", Names.fileName("https://x.com/dl", "attachment; filename*=UTF-8''na%C3%AFve.txt", null))
        assertEquals("download.mp4", Names.fileName("https://x.com/", null, "video/mp4"))
        assertEquals("a_b.zip", Names.fileName("https://x.com/a%2Fb.zip", null, null))
    }

    @Test
    fun categories() {
        assertEquals(Category.Video, Names.category("a.mkv", null))
        assertEquals(Category.Audio, Names.category("a", "audio/mpeg"))
        assertEquals(Category.Torrents, Names.category("x.torrent", null))
        assertEquals(Category.Archives, Names.category("x.7z", null))
        assertEquals(Category.Other, Names.category("x.bin", null))
    }

    @Test
    fun segmentsRoundTrip() {
        val s = Segments.plan(1000, 4, 100)
        assertEquals(4, s.size)
        assertEquals(999, s.last().end)
        assertEquals(Segments.encode(s), Segments.encode(Segments.decode(Segments.encode(s))))
    }
}
