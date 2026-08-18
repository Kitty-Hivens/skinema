package dev.hivens.skinema.libav

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The upcall boundary, tested where it lives. Everything on the other side
 * of it is a consumer's [MediaSource]: the seam is public, the calls come
 * from native frames, and a Kotlin method that trusts what a consumer
 * returns is writing into FFmpeg's own allocations on that trust.
 */
class AvioSourceTest {

    private class Source(
        private val data: ByteArray,
        /** What to answer, given what was asked for and what was copied. */
        private val report: (asked: Int, copied: Int) -> Int,
    ) : MediaSource {
        private var pos = 0
        override fun read(dst: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, dst, offset, n)
            pos += n
            return report(length, n)
        }
        override fun size(): Long = data.size.toLong()
    }

    @Test
    fun `a read that answers with more than it was asked for fails closed`() {
        Fixtures.assumeDecodeEnvironment()
        Arena.ofConfined().use { arena ->
            val avio = AvioSource(arena, Source(ByteArray(64 * 1024)) { asked, _ -> asked + 1 })
            // A short request, which is what makes this reachable: the
            // scratch buffer is bigger, so the copy itself raises nothing and
            // the extra byte lands past the end of FFmpeg's buffer.
            val bufSize = 1024
            val buf = arena.allocate(bufSize.toLong())
            val rc = avio.readPacket(MemorySegment.NULL, buf, bufSize)
            assertEquals(LibavAbi.AVERROR_EOF, rc, "the upcall must stop the demuxer, not hand back the lie")
            assertFailsWith<Throwable>("the session must fail closed, not carry on") { avio.throwIfFailed() }
        }
    }

    @Test
    fun `an honest short read is copied and reported as it stands`() {
        Fixtures.assumeDecodeEnvironment()
        Arena.ofConfined().use { arena ->
            val data = ByteArray(300) { (it % 251).toByte() }
            val avio = AvioSource(arena, Source(data) { _, copied -> copied })
            val buf = arena.allocate(1024L)
            val rc = avio.readPacket(MemorySegment.NULL, buf, 1024)
            assertEquals(300, rc, "a source with less than was asked for reports what it had")
            assertTrue((0 until 300).all { buf.get(JAVA_BYTE, it.toLong()) == data[it] }, "the bytes must arrive")
            avio.throwIfFailed()
        }
    }
}
