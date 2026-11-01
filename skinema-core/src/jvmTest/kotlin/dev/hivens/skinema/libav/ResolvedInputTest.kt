package dev.hivens.skinema.libav

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResolvedInputTest {

    private class Forward : MediaSource {
        var closes = 0
        override fun read(dst: ByteArray, offset: Int, length: Int): Int = -1
        override fun close() {
            closes++
        }
    }

    @Test
    fun `a failed open is not tried again on a stream it has already read`() {
        val live = ResolvedInput.Live(Forward())
        var opens = 0
        val first = assertFailsWith<IOException> {
            live.attach<AutoCloseable> {
                opens++
                throw IOException("no header")
            }
        }
        val second = assertFailsWith<IOException> {
            live.attach<AutoCloseable> {
                opens++
                AutoCloseable {}
            }
        }
        assertSame(first, second, "the second side gets the first side's reason")
        assertEquals(1, opens, "the open ran once")
    }

    @Test
    fun `the stream is closed once however many paths reach it`() {
        val source = Forward()
        val live = ResolvedInput.Live(source)
        live.interrupt()
        live.close()
        live.release()
        assertEquals(1, source.closes)
    }

    @Test
    fun `a stream that throws when asked whether it can seek is closed`() {
        var closed = false
        val broken = object : MediaSource {
            override fun read(dst: ByteArray, offset: Int, length: Int): Int = -1
            override fun seek(position: Long): Long = throw IOException("no answer")
            override fun close() {
                closed = true
            }
        }
        assertFailsWith<IOException> { MediaInput.Stream(broken).resolve() }
        assertTrue(closed)
    }
}
