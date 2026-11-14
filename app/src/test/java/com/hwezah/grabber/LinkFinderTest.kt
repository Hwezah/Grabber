package com.hwezah.grabber

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkFinderTest {
    @Test
    fun findsLinksAndMagnetsInOrder() {
        val text = "Get https://example.com/a.mp4, then magnet:?xt=urn:btih:abc&dn=x and http://x.org/b."
        assertEquals(
            listOf(
                Found.Web("https://example.com/a.mp4"),
                Found.Magnet("magnet:?xt=urn:btih:abc&dn=x"),
                Found.Web("http://x.org/b"),
            ),
            LinkFinder.find(text),
        )
    }

    @Test
    fun dropsRepeatsAndKeepsBalancedBrackets() {
        val text = "(see https://en.wikipedia.org/wiki/Foo_(bar)) and https://en.wikipedia.org/wiki/Foo_(bar)"
        assertEquals(listOf(Found.Web("https://en.wikipedia.org/wiki/Foo_(bar)")), LinkFinder.find(text))
    }

    @Test
    fun nothingInPlainText() {
        assertEquals(emptyList<Found>(), LinkFinder.find("just some words"))
    }
}
