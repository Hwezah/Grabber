package com.hwezah.grabber.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class StripsTest {
    @Test fun halves() {
        assertEquals(listOf(1f, 0f), Segments.partMap(100, listOf(0L until 50L), 2))
    }

    @Test fun inOrder() {
        assertEquals(listOf(1f, 0.5f, 0f, 0f), Segments.inOrderMap(0.375f, 4))
    }

    @Test fun nothingYet() {
        assertEquals(List(3) { 0f }, Segments.partMap(0, emptyList(), 3))
    }

    @Test fun savedParts() {
        val saved = Segments.encode(listOf(Segment(0, 49, 25), Segment(50, 99, 100)))
        assertEquals(listOf(0.5f, 1f), Segments.strips(saved, 100, 75))
    }

    @Test fun sectionsJoin() {
        assertEquals(listOf(0L..9L, 20L..29L), Segments.mergedSections(listOf(20L..29L, 0L..4L, 5L..9L, 7L..3L)))
    }

    @Test fun linkSections() {
        val saved = Segments.encode(listOf(Segment(0, 99, 50), Segment(100, 299, 150), Segment(300, 399, 350), Segment(400, 499, 400)))
        assertEquals(listOf(0L..49L, 100L..149L, 300L..349L), Segments.sections(saved, 150))
        val touching = Segments.encode(listOf(Segment(0, 99, 100), Segment(100, 199, 150)))
        assertEquals(listOf(0L..149L), Segments.sections(touching, 150))
    }
}
