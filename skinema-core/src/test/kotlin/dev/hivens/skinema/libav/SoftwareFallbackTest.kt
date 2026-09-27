package dev.hivens.skinema.libav

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fallback from a decoder picked for a device to the one software decode
 * uses, reached without a device.
 *
 * FFmpeg's own AV1 decoder has no software path: with no hwaccel behind it,
 * every packet is refused with ENOSYS. That is the same refusal a GPU without
 * AV1 decode produces, or a driver refusing a profile or a size, and the same
 * first-packet shape, measured for each. So opening that decoder as if a
 * device had picked it, with none opened, puts this path on every runner
 * rather than on the one machine that happens to have the right GPU.
 */
class SoftwareFallbackTest {

    private val dir: Path = Files.createTempDirectory("skinema-fallback-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    /** Forward-only: every seek refused, size unknown. A live stream, and MediaSource's default. */
    private class ForwardOnlySource(private val data: ByteArray) : MediaSource {
        private var pos = 0
        override fun read(dst: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, dst, offset, n)
            pos += n
            return n
        }
    }

    /**
     * AV1 in matroska, which demuxes without seeking back, with a keyframe every
     * second, so a seek lands somewhere other than the start. Larger than the
     * 32 KiB I/O buffer, so the file is not simply held whole in memory.
     */
    private fun av1(name: String): Path {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeAv1Fixture()
        val mp4 = Fixtures.av1(dir.resolve("$name.mp4"), seconds = 8, size = "320x240", keyframeEvery = 10)
        return Fixtures.generate(dir.resolve("$name.mkv"), "-i", mp4.toString(), "-c", "copy")
    }

    private fun grid(d: VideoDecoder): List<Long> = generateSequence { d.nextFrame()?.ptsNanos }.toList()

    @Test
    fun `a refused AV1 stream decodes whole on libdav1d under AUTO`() {
        val video = av1("auto")
        val (softwareGrid, softwareFirst) = VideoDecoder.open(video, HwAccel.OFF).use { d ->
            val first = d.nextFrame()!!
            val pixels = first.rgba.copyOf()
            (listOf(first.ptsNanos) + grid(d)) to pixels
        }
        VideoDecoder.openWithoutDevice(video, HwAccel.AUTO, "av1").use { d ->
            assertTrue(d.usesDecoder("av1"), "the open must pick FFmpeg's own decoder, as a device would")
            val first = d.nextFrame()!!
            assertEquals(softwareGrid.first(), first.ptsNanos, "the fallback must start where the stream starts")
            assertContentEquals(softwareFirst, first.rgba, "and show the picture software decode shows")
            assertEquals(softwareGrid, listOf(first.ptsNanos) + grid(d), "every frame must come through the fallback")
            assertTrue(d.usesDecoder("libdav1d"), "the refusal must land on the software decoder")
            assertFalse(d.hardwareActive(), "a decoder moved to software is not on the GPU")
        }
    }

    @Test
    fun `REQUIRE refuses the stream instead of falling back`() {
        val video = av1("require")
        VideoDecoder.openWithoutDevice(video, HwAccel.REQUIRE, "av1").use { d ->
            val refusal = assertFailsWith<LibavException> { d.nextFrame() }
            assertTrue("REQUIRE" in refusal.message.orEmpty(), "the refusal must say why: ${refusal.message}")
        }
    }

    /**
     * A source that cannot seek back loses nothing, because nothing is read
     * twice: the fallback replays the packets the refused decoder was sent.
     *
     * This checks the outcome and does not tell the replay from the seek the
     * first version of the fallback made. The refusal comes on the first
     * packet, and FFmpeg's I/O layer still holds the bytes it was read from, so
     * seeking back to it is served from memory even here. Measured, a seeking
     * fallback passes this at 64x64 and at 640x480 alike. Where it would not
     * (more read before the refusal, a buffer already recycled) the seek either
     * lands past the start or fails outright, which is why the fallback does
     * not seek at all.
     */
    @Test
    fun `the fallback loses nothing on a source that cannot seek`() {
        val video = av1("stream")
        val softwareGrid = VideoDecoder.open(video, HwAccel.OFF).use(::grid)
        val bytes = Files.readAllBytes(video)
        VideoDecoder.openWithoutDevice(ForwardOnlySource(bytes), HwAccel.AUTO, "av1").use { d ->
            assertEquals(softwareGrid, grid(d), "a forward-only source must decode every frame through the fallback")
            assertTrue(d.usesDecoder("libdav1d"))
        }
    }

    /** A player starting at a position seeks before its first frame, so the refusal comes after the seek. */
    @Test
    fun `a refusal after a seek resumes where the seek landed`() {
        val video = av1("seek")
        // Between two keyframes, so a fallback that restarted from the top of
        // the file rather than from the seek's landing shows up in the grid.
        val target = 3_500_000_000L
        val softwareGrid = VideoDecoder.open(video, HwAccel.OFF).use { d ->
            d.seekTo(target)
            grid(d)
        }
        VideoDecoder.openWithoutDevice(video, HwAccel.AUTO, "av1").use { d ->
            d.seekTo(target)
            assertEquals(softwareGrid, grid(d), "the fallback must decode from the seek's landing, as software does")
            assertTrue(d.usesDecoder("libdav1d"))
        }
    }

    /**
     * The negative control: a device-picked decoder that CAN decode in
     * software does so itself. FFmpeg's own VP9 decoder is one, and it must
     * never be swapped out for libvpx behind the caller's back.
     */
    @Test
    fun `a device-picked decoder with a software path keeps decoding`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("libvpx-vp9")
        val video = Fixtures.generate(
            dir.resolve("vp9.webm"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "1",
            "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8",
        )
        val softwareGrid = VideoDecoder.open(video, HwAccel.OFF).use(::grid)
        VideoDecoder.openWithoutDevice(video, HwAccel.AUTO, "vp9").use { d ->
            assertEquals(softwareGrid, grid(d))
            assertTrue(d.usesDecoder("vp9"), "native VP9 decodes in software and must not have been replaced")
        }
    }

    /** The tag the alpha exclusion rests on, read off the container the way the open reads it. */
    @Test
    fun `webm alpha is read off the stream's own tag`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("libvpx-vp9")
        val alpha = Fixtures.generate(
            dir.resolve("alpha.webm"),
            "-f", "lavfi", "-i", "color=c=red@0.5:size=16x16:rate=5,format=yuva420p", "-t", "1",
            "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", "-deadline", "realtime", "-cpu-used", "8",
        )
        val plain = Fixtures.generate(
            dir.resolve("plain.webm"),
            "-f", "lavfi", "-i", "testsrc2=size=16x16:rate=5", "-t", "1",
            "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8",
        )
        assertTrue(videoStreamAlphaTagged(alpha), "a yuva420p webm must carry the alpha tag")
        assertFalse(videoStreamAlphaTagged(plain), "a plain webm must not")
    }

    private fun videoStreamAlphaTagged(file: Path): Boolean = Arena.ofConfined().use { arena ->
        val fmtCtx = openInput(arena, file).fmtCtx
        try {
            Libav.checkAv(Libav.avformatFindStreamInfo(fmtCtx), "avformat_find_stream_info")
            val index = Libav.checkAv(
                Libav.avFindBestStream(fmtCtx, LibavAbi.AVMEDIA_TYPE_VIDEO, MemorySegment.NULL),
                "av_find_best_stream",
            )
            VideoDecoder.alphaTagged(arena, streamAt(fmtCtx, index))
        } finally {
            val ptrPtr = arena.allocate(ADDRESS)
            ptrPtr.set(ADDRESS, 0, fmtCtx)
            Libav.avformatCloseInput(ptrPtr)
        }
    }
}
