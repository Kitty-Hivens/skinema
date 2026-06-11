package dev.hivens.skinema.player

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FrameQueueTest {

    private fun FrameQueue.put(pts: Long, forced: Boolean = false, size: Int = 4) {
        val cell = writeCell()
        if (cell.rgba.size != size) cell.rgba = ByteArray(size)
        cell.rgba.fill(pts.toByte())
        cell.width = 1
        cell.height = 1
        cell.ptsNanos = pts
        commit(forced)
    }

    @Test
    fun `frames come out in commit order`() {
        val q = FrameQueue(3)
        q.put(10)
        q.put(20)
        q.put(30)
        assertEquals(10L, q.poll(ByteArray(4))!!.ptsNanos)
        assertEquals(20L, q.poll(ByteArray(4))!!.ptsNanos)
        assertEquals(30L, q.poll(ByteArray(4))!!.ptsNanos)
        assertNull(q.poll(ByteArray(4)))
    }

    @Test
    fun `room and emptiness track the committed count`() {
        val q = FrameQueue(2)
        assertTrue(q.isEmpty)
        assertTrue(q.hasRoom)
        q.put(1)
        assertFalse(q.isEmpty)
        assertTrue(q.hasRoom)
        q.put(2)
        assertFalse(q.hasRoom)
        q.dropHead()
        assertTrue(q.hasRoom)
        q.dropHead()
        assertTrue(q.isEmpty)
    }

    @Test
    fun `poll swaps array ownership both ways`() {
        val q = FrameQueue(1)
        q.put(7, size = 8)
        val replacement = ByteArray(8) { 0x55 }
        val frame = assertNotNull(q.poll(replacement))
        assertEquals(7L, frame.ptsNanos)
        assertEquals(7.toByte(), frame.rgba[0], "the caller got the filled array")
        // The replacement became the cell's storage: the next write reuses it.
        assertSame(replacement, q.writeCell().rgba)
    }

    @Test
    fun `cells recycle without growth across many cycles`() {
        val q = FrameQueue(2)
        var carried = ByteArray(4)
        val seen = mutableSetOf<ByteArray>()
        repeat(20) { i ->
            q.put(i.toLong())
            val frame = assertNotNull(q.poll(carried))
            seen += frame.rgba
            carried = frame.rgba
        }
        // 2 cells + 1 carried array = at most 3 distinct arrays in flight.
        assertTrue(seen.size <= 3, "swap must recycle arrays, saw ${seen.size}")
    }

    @Test
    fun `clear empties committed frames and bumps the flush count`() {
        val q = FrameQueue(3)
        q.put(1)
        q.put(2)
        val before = q.flushCount
        q.clear()
        assertTrue(q.isEmpty)
        assertEquals(before + 1, q.flushCount)
        assertNull(q.poll(ByteArray(4)), "a cleared frame must never surface")
        // The ring stays usable after a flush.
        q.put(3)
        assertEquals(3L, q.poll(ByteArray(4))!!.ptsNanos)
    }

    @Test
    fun `forced travels with the frame, not the cell`() {
        val q = FrameQueue(1)
        q.put(1, forced = true)
        assertTrue(q.peekHead()!!.forced)
        assertTrue(q.poll(ByteArray(4))!!.forced)
        // Same physical cell, normal commit: the flag must not stick.
        q.put(2, forced = false)
        assertFalse(q.peekHead()!!.forced)
        assertFalse(q.poll(ByteArray(4))!!.forced)
    }

    @Test
    fun `peek snapshots the head without popping`() {
        val q = FrameQueue(2)
        q.put(5, size = 16)
        val head = assertNotNull(q.peekHead())
        assertEquals(5L, head.ptsNanos)
        assertEquals(16, head.byteCount)
        assertFalse(q.isEmpty, "peek must not consume")
        assertEquals(5L, q.poll(ByteArray(16))!!.ptsNanos)
    }

    @Test
    fun `the write index is stable while the consumer pops`() {
        val q = FrameQueue(3)
        q.put(1)
        q.put(2)
        val cell = q.writeCell()
        cell.rgba = ByteArray(4)
        cell.ptsNanos = 3
        // The consumer pops between writeCell and commit.
        q.poll(ByteArray(4))
        q.commit()
        assertEquals(2L, q.poll(ByteArray(4))!!.ptsNanos)
        assertEquals(3L, q.poll(ByteArray(4))!!.ptsNanos, "the in-progress cell must commit intact")
    }

    @Test
    fun `awaitNonEmpty wakes on commit`() {
        val q = FrameQueue(1)
        val waiter = thread {
            while (q.isEmpty && !q.isClosed) q.awaitNonEmpty(5_000_000_000L)
        }
        Thread.sleep(50)
        assertTrue(waiter.isAlive, "the consumer must be parked")
        q.put(1)
        waiter.join(2_000)
        assertFalse(waiter.isAlive, "a commit must wake the waiter")
    }

    @Test
    fun `close wakes and releases a parked consumer`() {
        val q = FrameQueue(1)
        val waiter = thread {
            while (q.isEmpty && !q.isClosed) q.awaitNonEmpty(5_000_000_000L)
        }
        Thread.sleep(50)
        assertTrue(waiter.isAlive)
        q.close()
        waiter.join(2_000)
        assertFalse(waiter.isAlive, "close must release the waiter")
        assertTrue(q.isClosed)
    }
}
