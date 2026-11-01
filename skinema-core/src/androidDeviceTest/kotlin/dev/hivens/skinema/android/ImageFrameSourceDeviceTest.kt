package dev.hivens.skinema.android

import androidx.test.platform.app.InstrumentationRegistry
import dev.hivens.skinema.libav.DeviceFixtures
import dev.hivens.skinema.libav.LibavException
import dev.hivens.skinema.libav.VideoDecoder
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ImageFrameSourceDeviceTest {

    private fun source(name: String) = ImageFrameSource(ImageFrames.read(DeviceFixtures.path(name).toFile().readBytes()))

    private fun centre(f: VideoDecoder.RgbaFrame): IntArray {
        val i = ((f.height / 2) * f.width + f.width / 2) * 4
        return IntArray(4) { f.rgba[i + it].toInt() and 0xFF }
    }

    private fun assertColour(r: Int, g: Int, b: Int, f: VideoDecoder.RgbaFrame, what: String) {
        val c = centre(f)
        assertTrue(abs(c[0] - r) < 24 && abs(c[1] - g) < 24 && abs(c[2] - b) < 24 && c[3] > 250, "$what: got ${c.toList()}")
    }

    @Test
    fun eachAnimatedFormatGivesItsFramesInOrderWithTheirTimes() {
        for (name in listOf("rgb.gif", "rgb.apng", "rgb.webp")) {
            source(name).use { s ->
                assertEquals(300_000_000L, s.durationNanos(), "$name lasts three tenths")
                val expected = listOf(Triple(255, 0, 0, ), Triple(0, 255, 0), Triple(0, 0, 255))
                for ((i, rgb) in expected.withIndex()) {
                    val f = s.nextFrame() ?: fail("$name frame $i")
                    assertEquals(i * 100_000_000L, f.ptsNanos, "$name frame $i time")
                    assertColour(rgb.first, rgb.second, rgb.third, f, "$name frame $i")
                }
                assertNull(s.nextFrame(), "$name has three frames")
            }
        }
    }

    @Test
    fun aSeekLandsOnTheFrameShowingAtTheTarget() {
        for (name in listOf("rgb.gif", "rgb.apng", "rgb.webp")) {
            source(name).use { s ->
                s.seekTo(150_000_000L)
                val f = s.nextFrame() ?: fail("$name after the seek")
                assertEquals(100_000_000L, f.ptsNanos)
                assertColour(0, 255, 0, f, "$name after the seek")
                s.seekBefore(200_000_000L)
                assertEquals(100_000_000L, (s.nextFrame() ?: fail("$name after seekBefore")).ptsNanos)
                s.seekTo(0)
                assertColour(255, 0, 0, s.nextFrame() ?: fail("$name after rewinding"), "$name after rewinding")
            }
        }
    }

    @Test
    fun aRealAnimationHasAllItsFrames() {
        for (name in listOf("anim.gif", "anim.apng", "anim.webp")) {
            source(name).use { s ->
                var count = 0
                while (s.nextFrame() != null) count++
                assertEquals(10, count, "$name frames")
                assertEquals(1_000_000_000L, s.durationNanos(), "$name duration")
                assertEquals(160 to 120, s.videoSize())
            }
        }
    }

    /**
     * The tenth frame of a real animation, against ffmpeg's own composition of
     * it. These files store most frames as changes over the one before, so a
     * composer that gets disposal or blending wrong drifts from the reference.
     */
    @Test
    fun theComposedFrameMatchesFfmpegsComposition() {
        for (format in listOf("gif", "apng", "webp")) {
            val reference = DeviceFixtures.path("anim-$format-last.rgba").toFile().readBytes()
            source("anim.$format").use { s ->
                var last: VideoDecoder.RgbaFrame? = null
                repeat(10) { last = s.nextFrame() ?: fail("$format frame $it") }
                val frame = last!!
                assertEquals(reference.size, frame.rgba.size, "$format frame size")
                var total = 0L
                for (i in reference.indices) total += abs((reference[i].toInt() and 0xFF) - (frame.rgba[i].toInt() and 0xFF))
                val mean = total.toDouble() / reference.size
                assertTrue(mean < 3.0, "$format differs from ffmpeg's composition by $mean per channel")
            }
        }
    }

    @Test
    fun aStillIsOneFrameWithNoDuration() {
        source("still.jpg").use { s ->
            assertNull(s.durationNanos())
            assertColour(255, 0, 0, s.nextFrame() ?: fail("a frame"), "jpeg")
            assertNull(s.nextFrame())
        }
        source("still.png").use { s -> assertColour(0, 0, 255, s.nextFrame() ?: fail("a frame"), "png") }
    }

    @Test
    fun aFileCutShortGivesTheFramesItHoldsWhole() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        for (format in listOf("gif", "apng", "webp")) {
            val whole = DeviceFixtures.path("anim.$format").toFile().readBytes()
            // A download that stopped part way: libavformat reads the frames
            // before the cut, and so does this.
            val cut = dir.resolve("cut.$format").apply { writeBytes(whole.copyOf(whole.size * 6 / 10)) }
            VideoDecoder.open(cut.toPath()).use { d ->
                var count = 0
                while (d.nextFrame() != null) count++
                assertTrue(count in 2..9, "$format cut at 60% gave $count frames")
            }
        }
    }

    @Test
    fun aDamagedOrImpossibleImageIsRefusedAsTheDecoderRefuses() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        val gif = "GIF89a".toByteArray(Charsets.US_ASCII)
        // A canvas of 65535 x 65535, sixteen gigabytes of pixels.
        val huge = dir.resolve("huge.gif").apply { writeBytes(gif + le16(65535) + le16(65535) + byteArrayOf(0, 0, 0, 0x3B)) }
        assertFailsWith<LibavException> { VideoDecoder.open(huge.toPath()) }
        // A graphic control block two bytes long where four are owed.
        val short = dir.resolve("short-gce.gif").apply {
            writeBytes(gif + le16(2) + le16(2) + byteArrayOf(0, 0, 0, 0x21, 0xF9.toByte(), 2, 0, 0, 0, 0x3B))
        }
        assertFailsWith<LibavException> { VideoDecoder.open(short.toPath()) }
    }

    @Test
    fun aLargeSharedChunkIsNotCopiedIntoEveryFrame() {
        // An APNG of two thousand tiny frames and a megabyte colour profile
        // every frame's file needs: copied per frame when the container is
        // read, that is two gigabytes for a one-megabyte file.
        fun chunk(type: String, data: ByteArray) =
            ByteBuffer.allocate(12 + data.size).putInt(data.size).put(type.toByteArray(Charsets.US_ASCII)).put(data).putInt(0).array()
        val frames = 2_000
        val ihdr = ByteBuffer.allocate(13).putInt(1).putInt(1).put(8).put(6).put(0).put(0).put(0).array()
        var file = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            chunk("IHDR", ihdr) + chunk("acTL", ByteBuffer.allocate(8).putInt(frames).putInt(0).array()) +
            chunk("iCCP", ByteArray(1_000_000))
        val out = java.io.ByteArrayOutputStream().apply { write(file) }
        for (i in 0 until frames) {
            val fctl = ByteBuffer.allocate(26).putInt(i * 2).putInt(1).putInt(1).putInt(0).putInt(0).putShort(1).putShort(10).put(0).put(0).array()
            out.write(chunk("fcTL", fctl))
            out.write(chunk("fdAT", ByteBuffer.allocate(8).putInt(i * 2 + 1).putInt(0).array()))
        }
        out.write(chunk("IEND", ByteArray(0)))
        file = out.toByteArray()
        val image = ImageFrames.read(file)
        assertEquals(frames, image.parts.size)
        assertTrue(image.animated)
    }
}
