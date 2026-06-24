package dev.hivens.skinema.libav

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Custom-AVIO input: decode the same file through a [MediaSource] instead
 * of a Path and assert the byte-fed decode matches the file decode
 * frame-for-frame -- proof the read/seek upcalls feed the demuxer
 * correctly. A second case feeds a streamable container through a
 * forward-only (non-seekable) source, the live-stream shape.
 */
class VideoDecoderSourceTest {

    private val dir: Path = Files.createTempDirectory("skinema-source-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    /** Whole-file byte source, seekable -- a downloaded segment in memory. */
    private class ByteArraySource(private val data: ByteArray) : MediaSource {
        private var pos = 0
        override fun read(dst: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, dst, offset, n)
            pos += n
            return n
        }
        override fun seek(position: Long): Long {
            if (position < 0 || position > data.size) return -1
            pos = position.toInt()
            return position
        }
        override fun size(): Long = data.size.toLong()
    }

    /** Forward-only source -- a live stream: no seek, unknown size. */
    private class StreamSource(private val data: ByteArray) : MediaSource {
        private var pos = 0
        override fun read(dst: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, dst, offset, n)
            pos += n
            return n
        }
    }

    /** Serves [data], then raises [IOException] from read once [failAfter] bytes are out -- a network source dropping. */
    private class ThrowingSource(private val data: ByteArray, private val failAfter: Int) : MediaSource {
        private var pos = 0
        override fun read(dst: ByteArray, offset: Int, length: Int): Int {
            if (pos >= failAfter) throw IOException("source dropped at $pos")
            if (pos >= data.size) return -1
            val n = minOf(length, minOf(failAfter, data.size) - pos)
            System.arraycopy(data, pos, dst, offset, n)
            pos += n
            return n
        }
        override fun seek(position: Long): Long {
            if (position < 0 || position > data.size) return -1
            pos = position.toInt()
            return position
        }
        override fun size(): Long = data.size.toLong()
    }

    private fun ptsVia(open: () -> VideoDecoder): List<Long> =
        open().use { generateSequence { it.nextFrame()?.ptsNanos }.toList() }

    @Test
    fun `a file fed through a MediaSource decodes identically to the path`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("src.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        val viaPath = ptsVia { VideoDecoder.open(video) }
        val bytes = Files.readAllBytes(video)
        val viaSource = ptsVia { VideoDecoder.open(ByteArraySource(bytes)) }
        assertTrue(viaPath.isNotEmpty(), "the path decode should yield frames")
        assertEquals(viaPath, viaSource, "the byte-fed decode must match the file decode frame for frame")
    }

    @Test
    fun `a MediaSource that throws fails closed instead of crashing the VM`() {
        Fixtures.assumeDecodeEnvironment()
        // A throwable escaping the read/seek upcall while native FFmpeg frames
        // are on the stack would terminate the JVM; this whole test (the run)
        // reaching its assertions IS the proof it does not. mkv demuxes
        // linearly, so the header is up front and the failure lands mid-decode.
        val video = Fixtures.generate(
            dir.resolve("drop.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "2",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        val bytes = Files.readAllBytes(video)

        // Drops on the very first read: the open cannot read a header and fails
        // closed (any exception, no crash).
        assertFailsWith<Throwable> { VideoDecoder.open(ThrowingSource(bytes, 0)) }

        // Opens, then drops mid-stream: the decode loop resurfaces the source's
        // own IOException rather than ending on a silent EOF.
        assertFailsWith<IOException> {
            VideoDecoder.open(ThrowingSource(bytes, bytes.size * 3 / 4)).use { d ->
                generateSequence { d.nextFrame() }.count()
            }
        }
    }

    @Test
    fun `a streamable container decodes through a forward-only source`() {
        Fixtures.assumeDecodeEnvironment()
        // Matroska/webm demuxes without seeking back; the live-stream shape.
        val video = Fixtures.generate(
            dir.resolve("src.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        val viaPath = ptsVia { VideoDecoder.open(video) }
        val bytes = Files.readAllBytes(video)
        val viaStream = ptsVia { VideoDecoder.open(StreamSource(bytes)) }
        assertTrue(viaStream.isNotEmpty(), "the non-seekable source must still decode frames")
        assertEquals(viaPath, viaStream, "linear demux of a streamable container must match the file decode")
    }
}
