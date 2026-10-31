package dev.hivens.skinema.libav

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class VideoDecoderDeviceTest {

    private fun decodeAll(decoder: VideoDecoder): List<Long> {
        val pts = mutableListOf<Long>()
        while (true) pts += (decoder.nextFrame(convert = false) ?: break).ptsNanos
        return pts
    }

    private fun assertColour(expected: IntArray, actual: IntArray, tolerance: Int) {
        for (c in 0 until 3) {
            assertTrue(abs(expected[c] - actual[c]) <= tolerance, "channel $c: expected ${expected.toList()}, got ${actual.toList()}")
        }
    }

    @Test
    fun everyFrameComesOutOnTheFilesOwnPtsGrid() {
        VideoDecoder.open(DeviceFixtures.path("red-h264.mp4"), HwAccel.AUTO).use { d ->
            val pts = decodeAll(d)
            assertEquals(30, pts.size)
            pts.forEachIndexed { i, p ->
                assertTrue(abs(p - i * 1_000_000_000L / 30) < 1_000_000, "frame $i at $p ns")
            }
            assertEquals(320 to 240, d.videoSize())
            val duration = d.durationNanos() ?: fail("a declared duration")
            assertTrue(abs(duration - 1_000_000_000L) < 50_000_000, "duration $duration")
        }
    }

    @Test
    fun aSolidColourConvertsToItselfWithinTolerance() {
        VideoDecoder.open(DeviceFixtures.path("red-h264.mp4"), HwAccel.AUTO).use { d ->
            val frame = d.nextFrame() ?: fail("a frame")
            assertEquals(320, frame.width)
            assertEquals(240, frame.height)
            assertEquals(320 * 240 * 4, frame.rgba.size)
            assertColour(intArrayOf(255, 0, 0), centre(frame), 16)
        }
        VideoDecoder.open(DeviceFixtures.path("green-vp9.webm"), HwAccel.AUTO).use { d ->
            assertColour(intArrayOf(0, 255, 0), centre(d.nextFrame() ?: fail("a frame")), 20)
        }
    }

    @Test
    fun hdConvertsThroughBT709WhetherTheStreamSaysSoOrNot() {
        for (name in listOf("blue-hd-tagged.mp4", "blue-hd-untagged.mp4")) {
            VideoDecoder.open(DeviceFixtures.path(name), HwAccel.AUTO).use { d ->
                assertColour(intArrayOf(0x20, 0x50, 0xC0), centre(d.nextFrame() ?: fail("a frame of $name")), 12)
            }
        }
    }

    @Test
    fun aFrameDecodedWithoutConvertingCanBeConvertedAfterwards() {
        VideoDecoder.open(DeviceFixtures.path("red-h264.mp4"), HwAccel.AUTO).use { d ->
            val bare = d.nextFrame(convert = false) ?: fail("a frame")
            assertEquals(0, bare.rgba.size)
            val converted = d.convertLast()
            assertEquals(bare.ptsNanos, converted.ptsNanos)
            assertColour(intArrayOf(255, 0, 0), centre(converted), 16)
        }
    }

    @Test
    fun aSeekLandsOnTheKeyframeAtOrBeforeTheTargetAndSeekBeforeStepsBehindIt() {
        val third = 1_000_000_000L / 3
        VideoDecoder.open(DeviceFixtures.path("red-h264.mp4"), HwAccel.AUTO).use { d ->
            d.seekTo(500_000_000L)
            val landed = d.nextFrame(convert = false) ?: fail("a frame after the seek")
            assertTrue(abs(landed.ptsNanos - third) < 1_000_000, "landed at ${landed.ptsNanos}")
            d.seekBefore(landed.ptsNanos)
            val before = d.nextFrame(convert = false) ?: fail("a frame after seekBefore")
            assertEquals(0L, before.ptsNanos)
            d.seekTo(0)
            assertEquals(30, decodeAll(d).size)
        }
    }

    @Test
    fun aFileWithNoPictureIsRefusedAsHavingNone() {
        assertFailsWith<NoVideoStreamException> {
            VideoDecoder.open(DeviceFixtures.path("sine-aac.m4a"), HwAccel.AUTO).close()
        }
    }

    @Test
    fun aSeekableSourceDecodesTheSameFramesAsTheFile() {
        val fromFile = VideoDecoder.open(DeviceFixtures.path("red-h264.mp4"), HwAccel.AUTO).use { decodeAll(it) }
        val fromSource = VideoDecoder.open(DeviceFixtures.memorySource("red-h264.mp4"), HwAccel.AUTO).use { decodeAll(it) }
        assertEquals(fromFile, fromSource)
    }

    @Test
    fun aSourceThatCannotSeekIsRefusedByName() {
        val e = assertFailsWith<LibavException> {
            VideoDecoder.open(DeviceFixtures.forwardOnlySource("red-h264.mp4"), HwAccel.AUTO).use { decodeAll(it) }
        }
        assertTrue(e.message.orEmpty().isNotEmpty())
    }

    @Test
    fun offDecodesInSoftwareAndREQUIREEitherUsesTheHardwareOrRefuses() {
        VideoDecoder.open(DeviceFixtures.path("red-h264.mp4"), HwAccel.OFF).use { d ->
            assertFalse(d.hardwareActive())
            assertEquals(30, decodeAll(d).size)
        }
        try {
            VideoDecoder.open(DeviceFixtures.path("red-h264.mp4"), HwAccel.REQUIRE).use { d ->
                assertTrue(d.hardwareActive())
                assertEquals(30, decodeAll(d).size)
            }
        } catch (e: LibavException) {
            assertTrue("REQUIRE" in e.message.orEmpty(), "the refusal names the policy: ${e.message}")
        }
    }

    @Test
    fun imagesOpenAsFramesAnimatedOrStill() {
        for (name in listOf("anim.gif", "anim.apng", "anim.webp")) {
            VideoDecoder.open(DeviceFixtures.path(name), HwAccel.AUTO).use { d ->
                assertEquals(10, decodeAll(d).size, "$name frames")
                assertEquals(1_000_000_000L, d.durationNanos(), "$name duration")
                assertFalse(d.hardwareActive())
            }
        }
        VideoDecoder.open(DeviceFixtures.path("still.jpg"), HwAccel.AUTO).use { d ->
            assertEquals(1, decodeAll(d).size)
            assertEquals(64 to 48, d.videoSize())
        }
        VideoDecoder.open(DeviceFixtures.memorySource("anim.webp"), HwAccel.AUTO).use { assertEquals(10, decodeAll(it).size) }
        val e = assertFailsWith<LibavException> { VideoDecoder.open(DeviceFixtures.path("anim.gif"), HwAccel.REQUIRE).close() }
        assertTrue("REQUIRE" in e.message.orEmpty())
    }

    @Test
    fun closeIsIdempotent() {
        val d = VideoDecoder.open(DeviceFixtures.path("red-h264.mp4"), HwAccel.AUTO)
        d.close()
        d.close()
    }
}
