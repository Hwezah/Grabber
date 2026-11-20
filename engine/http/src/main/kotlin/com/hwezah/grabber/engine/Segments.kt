package com.hwezah.grabber.engine

/** Bytes [start, end] of the file; [pos] is the next byte to write. Done when pos > end. */
class Segment(val start: Long, @Volatile var end: Long, @Volatile var pos: Long) {
    @Volatile var busy = false
    /** Bytes read but not yet written (a connection batches its writes). */
    @Volatile var pending = 0L
    val remaining get() = (end - pos - pending + 1).coerceAtLeast(0)
    val done get() = pos > end
}

object Segments {
    /** Splits [total] bytes into up to [count] parts of at least [minPart] bytes. */
    fun plan(total: Long, count: Int, minPart: Long): MutableList<Segment> {
        if (total <= 0) return mutableListOf()
        val n = maxOf(1L, minOf(count.toLong(), total / minPart.coerceAtLeast(1))).toInt()
        val size = total / n
        return MutableList(n) { i ->
            val s = i * size
            val e = if (i == n - 1) total - 1 else s + size - 1
            Segment(s, e, s)
        }
    }

    /** How full each of [slots] equal slices of [total] bytes is, given the byte ranges [has] (1DM-style part strips). */
    fun partMap(total: Long, has: List<LongRange>, slots: Int): List<Float> {
        if (slots <= 0) return emptyList()
        if (total <= 0) return List(slots) { 0f }
        return List(slots) { i ->
            val from = total * i / slots
            val to = total * (i + 1) / slots - 1
            val span = (to - from + 1).coerceAtLeast(1)
            val got = has.sumOf { r -> (minOf(r.last, to) - maxOf(r.first, from) + 1).coerceAtLeast(0) }
            (got.toFloat() / span).coerceIn(0f, 1f)
        }
    }

    /** One connection: filled in order from the left, to [ratio]. */
    fun inOrderMap(ratio: Float, slots: Int): List<Float> {
        val r = if (ratio.isNaN()) 0f else ratio.coerceIn(0f, 1f)
        val have = (r * 10_000L).toLong()
        return partMap(10_000L, if (have > 0) listOf(0L until have) else emptyList(), slots)
    }

    /** A saved download's strips: one per part over several connections (at most 48), else 24 filled in order. */
    fun strips(saved: String?, total: Long, done: Long): List<Float> {
        val parts = decode(saved)
        if (parts.size > 1 && total > 0) return partMap(total, parts.filter { it.pos > it.start }.map { it.start until it.pos }, parts.size.coerceAtMost(48))
        return inOrderMap(if (total > 0) done.toFloat() / total else 0f, 24)
    }

    /** "start:end:pos,…" — saved with the download so it resumes after the app is killed. */
    fun encode(list: List<Segment>) = list.joinToString(",") { "${it.start}:${it.end}:${it.pos}" }

    fun decode(text: String?): MutableList<Segment> =
        text.orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { part ->
            val v = part.split(':').mapNotNull { it.toLongOrNull() }
            if (v.size == 3) Segment(v[0], v[1], v[2]) else null
        }.toMutableList()
}
