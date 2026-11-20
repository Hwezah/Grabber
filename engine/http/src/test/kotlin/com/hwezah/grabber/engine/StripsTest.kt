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
}
