package dev.hivens.skinema.libav

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading closed captions off decoded frames, which is the only place they
 * exist: CEA-608/708 is SEI inside the video bitstream, so there is no stream
 * to demux and nothing to select until a frame has been through the decoder.
 *
 * These assert the LIBRARY's extraction, where [ClosedCaptionFixtureTest]
 * asserts the fixture through FFmpeg's own path. Both are needed: the first
 * says the file is right, this one says we read it.
 */
class ClosedCaptionExtractionTest {

    private val dir: Path = Files.createTempDirectory("skinema-cc-extract")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a captioned file yields A53 bytes on the frames that carry them`() {
        Fixtures.assumeDecodeEnvironment()
        val file = ClosedCaptionFixture.generate(dir, "captions.mp4", text = "HI")

        FrameSources.open(file, HwAccel.OFF).use { source ->
            var framesWithCaptions = 0
            var firstPayload: ByteArray? = null
            var frames = 0
            while (source.nextFrame(convert = false) != null && frames < 40) {
                frames++
                source.captionBytes()?.let {
                    framesWithCaptions++
                    if (firstPayload == null) firstPayload = it
                }
            }

            assertTrue(frames > 0, "the fixture must decode")
            assertTrue(framesWithCaptions > 0, "no frame carried captions out of $frames decoded")
            val payload = assertNotNull(firstPayload, "the first captioned frame must hand over bytes")
            // The A53 form the decoder hands back: cc_data triplets, whose
            // first byte marks a valid field-one pair. Asserting the SHAPE
            // rather than the exact bytes, because what libavcodec hands over
            // is its own normalisation of what the SEI carried.
            assertTrue(payload.size >= 3, "an A53 payload is triplets, got ${payload.size} bytes")
            assertTrue(payload.size % 3 == 0, "expected whole cc_data triplets, got ${payload.size} bytes")
        }
    }

    /**
     * The other direction, and the one that decides whether this can be asked
     * on every frame of every file: a file with no captions must answer null
     * rather than an empty array or a stale payload from an earlier frame.
     */
    @Test
    fun `a file without captions yields nothing on every frame`() {
        Fixtures.assumeDecodeEnvironment()
        val plain = Fixtures.generate(
            dir.resolve("plain.mp4"),
            "-f", "lavfi", "-i", "color=c=navy:s=320x240:r=25:d=1",
            "-c:v", "libx264", "-preset", "ultrafast",
        )

        FrameSources.open(plain, HwAccel.OFF).use { source ->
            var frames = 0
            while (source.nextFrame(convert = false) != null && frames < 30) {
                frames++
                assertNull(source.captionBytes(), "frame $frames of a plain file reported captions")
            }
            assertTrue(frames > 0, "the plain fixture must decode")
        }
    }
}
