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

    /** A source that records what it was asked to do, and can refuse or raise. */
    private class SeekableSource(
        private val total: Long,
        private val seekable: Boolean = true,
        private val raise: Boolean = false,
    ) : MediaSource {
        var lastSeek = Long.MIN_VALUE
            private set
        var seeks = 0
            private set

        override fun read(dst: ByteArray, offset: Int, length: Int): Int {
            val n = minOf(length.toLong(), total).toInt()
            return if (n <= 0) -1 else n
        }

        override fun seek(position: Long): Long {
            seeks++
            lastSeek = position
            if (raise) throw java.io.IOException("the stream went away")
            return if (seekable) position else -1
        }

        override fun size(): Long = total
    }

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

    // -- the seek upcall ------------------------------------------------------
    //
    // Read is what the existing pair above covers. Seek is the other half of
    // the streaming seam and none of it had ever run: the demuxer asks for a
    // size, and the fixtures decoded linearly, so SET, CUR and END, the
    // refusals and the raise had no test at all. An error in this arithmetic
    // misplaces every reposition a consumer's own byte source is asked for.

    @Test
    fun `the size question is answered from the source, not from a seek`() {
        Fixtures.assumeDecodeEnvironment()
        Arena.ofConfined().use { arena ->
            val source = SeekableSource(total = 4096)
            val avio = AvioSource(arena, source)
            assertEquals(4096L, avio.seekPacket(MemorySegment.NULL, 0, LibavAbi.AVSEEK_SIZE))
            assertEquals(0, source.seeks, "asking how big it is must not move it")
        }
    }

    @Test
    fun `SET, CUR and END resolve to the absolute offset the source is handed`() {
        Fixtures.assumeDecodeEnvironment()
        Arena.ofConfined().use { arena ->
            val source = SeekableSource(total = 1000)
            val avio = AvioSource(arena, source)

            assertEquals(120L, avio.seekPacket(MemorySegment.NULL, 120, LibavAbi.SEEK_SET))
            assertEquals(120L, source.lastSeek, "SET is the offset as given")

            // CUR is relative to where the bytes have got to, which is why the
            // source never has to track a position of its own.
            assertEquals(150L, avio.seekPacket(MemorySegment.NULL, 30, LibavAbi.SEEK_CUR))
            assertEquals(150L, source.lastSeek, "CUR counts from where the last seek landed")

            assertEquals(900L, avio.seekPacket(MemorySegment.NULL, -100, LibavAbi.SEEK_END))
            assertEquals(900L, source.lastSeek, "END counts back from the size")
        }
    }

    @Test
    fun `a read moves what CUR counts from`() {
        Fixtures.assumeDecodeEnvironment()
        Arena.ofConfined().use { arena ->
            val source = SeekableSource(total = 1000)
            val avio = AvioSource(arena, source)
            val buf = arena.allocate(64L)
            assertEquals(64, avio.readPacket(MemorySegment.NULL, buf, 64))
            assertEquals(74L, avio.seekPacket(MemorySegment.NULL, 10, LibavAbi.SEEK_CUR), "reads advance the mirror")
        }
    }

    @Test
    fun `an END on a source of unknown size is refused without asking it to move`() {
        Fixtures.assumeDecodeEnvironment()
        Arena.ofConfined().use { arena ->
            // A live stream: no size, so there is no end to count back from.
            val source = SeekableSource(total = -1)
            val avio = AvioSource(arena, source)
            assertEquals(-1L, avio.seekPacket(MemorySegment.NULL, -100, LibavAbi.SEEK_END))
            assertEquals(0, source.seeks, "an offset that cannot be computed must not be guessed")
        }
    }

    @Test
    fun `a whence this does not know is refused rather than guessed`() {
        Fixtures.assumeDecodeEnvironment()
        Arena.ofConfined().use { arena ->
            val source = SeekableSource(total = 1000)
            val avio = AvioSource(arena, source)
            assertEquals(-1L, avio.seekPacket(MemorySegment.NULL, 10, 12345))
            assertEquals(0, source.seeks)
        }
    }

    @Test
    fun `a refused seek leaves the mirror where the bytes actually are`() {
        Fixtures.assumeDecodeEnvironment()
        Arena.ofConfined().use { arena ->
            // Forward-only, the live-stream shape: every seek answers -1 and
            // the demuxer falls back to linear reads. The position it reads
            // from has not moved, so a later CUR must still count from there
            // -- taking the refused target as the new origin would send the
            // next relative seek somewhere the stream never was.
            val source = SeekableSource(total = 1000, seekable = false)
            val avio = AvioSource(arena, source)
            val buf = arena.allocate(64L)
            avio.readPacket(MemorySegment.NULL, buf, 64)
            assertEquals(-1L, avio.seekPacket(MemorySegment.NULL, 500, LibavAbi.SEEK_SET))
            avio.seekPacket(MemorySegment.NULL, 10, LibavAbi.SEEK_CUR)
            assertEquals(74L, source.lastSeek, "the refusal must not become the new origin")
        }
    }

    @Test
    fun `a source that raises inside the seek fails the session closed`() {
        Fixtures.assumeDecodeEnvironment()
        Arena.ofConfined().use { arena ->
            // A network read raising is ordinary for this seam, and a
            // throwable unwinding through native frames takes the VM down.
            val avio = AvioSource(arena, SeekableSource(total = 1000, raise = true))
            assertEquals(-1L, avio.seekPacket(MemorySegment.NULL, 10, LibavAbi.SEEK_SET))
            assertFailsWith<java.io.IOException> { avio.throwIfFailed() }
        }
    }
}
