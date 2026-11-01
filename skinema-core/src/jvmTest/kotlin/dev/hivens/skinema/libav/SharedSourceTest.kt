package dev.hivens.skinema.libav

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedSourceTest {

    /** Bytes 0..255 repeating, seekable, slow enough per read that overlapping callers would meet inside it. */
    private class SlowSource(size: Int, private val lands: (Long) -> Long = { it }) : MediaSource {
        private val data = ByteArray(size) { it.toByte() }
        private var pos = 0
        private val inside = AtomicInteger(0)
        val overlaps = AtomicInteger(0)

        @Volatile
        var closed = false

        override fun read(dst: ByteArray, offset: Int, length: Int): Int {
            if (inside.incrementAndGet() > 1) overlaps.incrementAndGet()
            try {
                Thread.sleep(1)
                if (pos >= data.size) return -1
                val n = minOf(length, 64, data.size - pos)
                System.arraycopy(data, pos, dst, offset, n)
                pos += n
                return n
            } finally {
                inside.decrementAndGet()
            }
        }

        override fun seek(position: Long): Long {
            if (inside.incrementAndGet() > 1) overlaps.incrementAndGet()
            try {
                val landed = lands(position)
                pos = landed.toInt()
                return landed
            } finally {
                inside.decrementAndGet()
            }
        }

        override fun size(): Long = data.size.toLong()

        override fun close() {
            closed = true
        }
    }

    private fun readAll(source: MediaSource, length: Int): ByteArray {
        val out = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = source.read(out, got, length - got)
            if (n <= 0) break
            got += n
        }
        return out.copyOf(got)
    }

    @Test
    fun `each cursor reads from where it left off, whatever the others did`() {
        val shared = SharedSource(SlowSource(1024))
        val a = shared.cursor()
        val b = shared.cursor()
        assertContentEquals(ByteArray(64) { it.toByte() }, readAll(a, 64))
        b.seek(500)
        assertContentEquals(ByteArray(64) { (500 + it).toByte() }, readAll(b, 64))
        assertContentEquals(ByteArray(64) { (64 + it).toByte() }, readAll(a, 64), "a picks up at 64, not where b left the source")
    }

    @Test
    fun `cursors on several threads never call into the source at once`() {
        val source = SlowSource(64 * 1024)
        val shared = SharedSource(source)
        val start = CountDownLatch(1)
        val readers = (0 until 4).map { i ->
            val cursor = shared.cursor()
            cursor.seek(i * 16_000L)
            thread {
                start.await()
                readAll(cursor, 4_000)
            }
        }
        start.countDown()
        readers.forEach { it.join() }
        assertEquals(0, source.overlaps.get(), "two calls were inside the source at once")
    }

    @Test
    fun `a release waits for the last cursor, a close does not`() {
        val source = SlowSource(1024)
        val shared = SharedSource(source)
        val cursor = shared.cursor()
        shared.release()
        assertFalse(source.closed, "a reader still holds it")
        assertEquals(64, readAll(cursor, 64).size, "and it can still read")
        cursor.close()
        assertTrue(source.closed, "the last cursor let go")

        val other = SlowSource(1024)
        val held = SharedSource(other)
        val reader = held.cursor()
        held.close()
        assertTrue(other.closed, "a close does not wait")
        assertFailsWith<IOException> { reader.read(ByteArray(8), 0, 8) }
    }

    @Test
    fun `a source that lands somewhere else fails the read rather than hand over the wrong bytes`() {
        val shared = SharedSource(SlowSource(1024) { if (it == 0L) 0L else it - 1 })
        val cursor = shared.cursor()
        cursor.seek(100)
        assertFailsWith<IOException> { cursor.read(ByteArray(8), 0, 8) }
    }

    @Test
    fun `only a source that answers a seek to zero is taken as seekable`() {
        assertTrue(SharedSource.canSeek(SlowSource(16)))
        assertFalse(SharedSource.canSeek(object : MediaSource {
            override fun read(dst: ByteArray, offset: Int, length: Int): Int = -1
        }))
    }
}
