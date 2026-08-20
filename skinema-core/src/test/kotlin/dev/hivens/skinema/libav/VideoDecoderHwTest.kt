package dev.hivens.skinema.libav

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hardware-decode acceptance -- the manual acceptance the ROADMAP's M11 entry
 * calls for, on a dev box with a working device (VAAPI/NVDEC, D3D11VA,
 * VideoToolbox).
 *
 * Two switches, because they answer different questions.
 * SKINEMA_TEST_HWACCEL=1 runs these at all; SKINEMA_REQUIRE_HWACCEL=1 says
 * this machine decodes on the GPU, so falling back to software is a failure
 * rather than a skip. Splitting them is not ceremony: a hosted macOS runner
 * opens a VideoToolbox device and then decodes every frame on the CPU, so "a
 * device opened" and "the GPU decoded" are separate facts and only the second
 * is worth asserting. Running without the second switch still covers the
 * device open, the negotiation and the fallback on a backend no other machine
 * here has.
 */
class VideoDecoderHwTest {

    private val dir: Path = Files.createTempDirectory("skinema-hwdecode-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun assumeHwAcceptance() {
        Fixtures.assumeDecodeEnvironment()
        assumeTrue(
            System.getenv("SKINEMA_TEST_HWACCEL") == "1",
            "hardware-decode acceptance is opt-in (SKINEMA_TEST_HWACCEL=1) -- a GPU CI cannot run it",
        )
    }

    private fun testsrc(name: String): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "testsrc2=size=128x128:rate=10", "-t", "1",
        "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
    )

    private fun ptsGrid(video: Path, hardware: HwAccel): List<Long> =
        VideoDecoder.open(video, hardware).use { d ->
            generateSequence { d.nextFrame()?.ptsNanos }.toList()
        }

    @Test
    fun `software decode never reports hardware active`() {
        Fixtures.assumeDecodeEnvironment()
        val video = testsrc("software.mp4")
        VideoDecoder.open(video, HwAccel.OFF).use { d ->
            assertFalse(d.hardwareActive(), "OFF is pure software")
            assertEquals(10, generateSequence { d.nextFrame()?.ptsNanos }.count())
        }
    }

    @Test
    fun `AUTO decodes the same pts grid as software, hw or fallback`() {
        assumeHwAcceptance()
        val video = testsrc("auto.mp4")
        val software = ptsGrid(video, HwAccel.OFF)
        val auto = ptsGrid(video, HwAccel.AUTO)
        assertEquals(List(10) { it * 100_000_000L }, software)
        assertEquals(software, auto, "AUTO must decode the same grid as software, whichever backend it took")
    }

    @Test
    fun `AUTO stays pixel-faithful through whichever backend`() {
        assumeHwAcceptance()
        val video = Fixtures.generate(
            dir.resolve("red.mp4"),
            "-f", "lavfi", "-i", "color=c=red:size=64x64:rate=5", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        VideoDecoder.open(video, HwAccel.AUTO).use { d ->
            val frame = d.nextFrame()!!
            val i = (32 * 64 + 32) * 4
            val r = frame.rgba[i].toInt() and 0xFF
            val g = frame.rgba[i + 1].toInt() and 0xFF
            val b = frame.rgba[i + 2].toInt() and 0xFF
            assertTrue(r > 200, "red must dominate through hw or software, got $r")
            assertTrue(g < 60 && b < 60, "green/blue near zero, got g=$g b=$b")
            println("[hw-test] AUTO hardwareActive=${d.hardwareActive()}")
        }
    }

    /**
     * Frames must arrive on the device the decoder opened -- checked against
     * the frame, never against the request, because the request is what every
     * other signal reports: hardware decode was negotiated away on every open
     * for two months (the target lived on the opening thread, and a
     * frame-threaded decoder negotiates on a worker of its own) and nothing in
     * the suite could tell, because nothing looked at a frame.
     *
     * Whether the machine OWES an answer is a separate question from whether
     * the tests may run, exactly as with the audio device: SKINEMA_TEST_HWACCEL
     * runs them, SKINEMA_REQUIRE_HWACCEL says this machine has working
     * hardware decode and a fallback is a failure. Both are needed because a
     * device opening proves nothing: a hosted macOS runner opens a
     * VideoToolbox device (surface 157) and then decodes every frame in
     * software, which is the AUTO fallback working as promised.
     */
    private fun assertDecodedOnDevice(d: VideoDecoder, where: String) {
        val required = System.getenv("SKINEMA_REQUIRE_HWACCEL") == "1"
        val surface = d.negotiatedSurfaceFormat()
        if (surface == LibavAbi.AV_PIX_FMT_NONE) {
            assertFalse(required, "$where: SKINEMA_REQUIRE_HWACCEL is set but no device opened at all")
            return
        }
        val onDevice = d.lastFrameFormat() == surface
        if (required) {
            assertEquals(surface, d.lastFrameFormat(), "$where: a device is open, so frames must be its surfaces")
            return
        }
        assumeTrue(onDevice, "$where: a device opened but the hwaccel did not engage -- nothing on it to check")
    }

    @Test
    fun `an opened device decodes onto its own surfaces`() {
        assumeHwAcceptance()
        VideoDecoder.open(testsrc("surface.mp4"), HwAccel.AUTO).use { d ->
            assertTrue(d.nextFrame() != null, "AUTO must decode")
            assertDecodedOnDevice(d, "AUTO")
        }
    }

    @Test
    fun `a second decoder does not take the first one's surface`() {
        assumeHwAcceptance()
        val video = testsrc("shared.mp4")
        VideoDecoder.open(video, HwAccel.AUTO).use { hw ->
            // A software decoder opened between the first one's open and its
            // first decode -- a thumbnailer beside a player, or two opens in a
            // row on one thread. Nothing about it belongs to the hw decoder.
            VideoDecoder.open(video, HwAccel.OFF).use { software ->
                assertTrue(software.nextFrame() != null, "the software decoder must decode")
                assertEquals(
                    LibavAbi.AV_PIX_FMT_NONE, software.negotiatedSurfaceFormat(),
                    "OFF opens no device",
                )
            }
            assertTrue(hw.nextFrame() != null, "the hw decoder must still decode")
            assertDecodedOnDevice(hw, "AUTO beside a software decoder")
        }
    }

    @Test
    fun `hardwareActive answers for the frames rather than for the request`() {
        // Ungated on purpose: the property holds on any machine, with a
        // device or without one, and it is the property whose absence let a
        // GPU path that never engaged report that it had.
        Fixtures.assumeDecodeEnvironment()
        VideoDecoder.open(testsrc("truth.mp4"), HwAccel.AUTO).use { d ->
            assertTrue(d.nextFrame() != null, "AUTO must decode")
            val onDevice = d.negotiatedSurfaceFormat() != LibavAbi.AV_PIX_FMT_NONE &&
                d.lastFrameFormat() == d.negotiatedSurfaceFormat()
            assertEquals(onDevice, d.hardwareActive(), "the report must match where the frame came from")
        }
    }

    @Test
    fun `a stream the device cannot decode falls back and stops claiming hardware`() {
        assumeHwAcceptance()
        // 4:4:4 H.264: the decoder advertises a VAAPI config, so a device
        // opens, and then no consumer-grade driver can decode the profile --
        // avcodec asks for a format again without the hardware entry and
        // finishes on the CPU. AUTO promises exactly that fallback; what it
        // must not do is go on calling itself hardware.
        val video = Fixtures.generate(
            dir.resolve("high444.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=128x128:rate=10", "-t", "1",
            "-pix_fmt", "yuv444p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        VideoDecoder.open(video, HwAccel.AUTO).use { d ->
            assertTrue(d.nextFrame() != null, "the fallback must still decode")
            assumeTrue(
                d.negotiatedSurfaceFormat() != LibavAbi.AV_PIX_FMT_NONE,
                "no device opened for this stream -- nothing to fall back from",
            )
            assumeTrue(
                d.lastFrameFormat() != d.negotiatedSurfaceFormat(),
                "this device decodes 4:4:4 after all -- no fallback to observe",
            )
            assertFalse(d.hardwareActive(), "a decoder handed software frames is not on the GPU")
        }
    }

    /**
     * REQUIRE promises that a file which cannot decode on the GPU surfaces as
     * a failure, and until now it could only say so at OPEN time -- where a
     * device accepting the stream looks like success. The fallback avcodec
     * takes when the hwaccel cannot initialise for a profile happens on the
     * first frame, past every check the open made, so REQUIRE quietly became
     * AUTO for exactly the streams it exists to refuse.
     */
    @Test
    fun `REQUIRE refuses a stream the device hands back to the CPU`() {
        assumeHwAcceptance()
        val video = Fixtures.generate(
            dir.resolve("high444-require.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=128x128:rate=10", "-t", "1",
            "-pix_fmt", "yuv444p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        // Whether this device falls back at all is a property of the driver,
        // so the AUTO run decides whether there is anything to assert.
        val fellBack = VideoDecoder.open(video, HwAccel.AUTO).use { d ->
            d.nextFrame()
            d.negotiatedSurfaceFormat() != LibavAbi.AV_PIX_FMT_NONE &&
                d.lastFrameFormat() != d.negotiatedSurfaceFormat()
        }
        assumeTrue(fellBack, "this device decodes 4:4:4 -- no fallback for REQUIRE to refuse")

        VideoDecoder.open(video, HwAccel.REQUIRE).use { d ->
            assertFailsWith<LibavException>("REQUIRE must not decode this in software") { d.nextFrame() }
        }
    }

    @Test
    fun `the downloaded picture is the software picture`() {
        assumeHwAcceptance()
        // A whole frame of colour bars, not one pixel of a flat colour: the
        // GPU hands back NV12 and the software decoder YUV420P, so the two
        // reach RGBA through different swscale conversions, and a chroma or
        // matrix mistake in that pair shows up in the picture rather than in
        // any error. Bars keep the two apart cleanly -- large saturated areas
        // a matrix error moves wholesale, and only seven chroma edges, which
        // is where the paths legitimately differ. Measured 0.75 of 255 mean
        // here against the ~18 a wrong matrix costs (the encode side measured
        // that one), so the bar sits between them rather than beside either.
        val video = Fixtures.generate(
            dir.resolve("pattern.mp4"),
            "-f", "lavfi", "-i", "smptebars=size=128x128:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        val software = VideoDecoder.open(video, HwAccel.OFF).use { it.nextFrame()!!.rgba.copyOf() }
        val hardware = VideoDecoder.open(video, HwAccel.AUTO).use { d ->
            val rgba = d.nextFrame()!!.rgba.copyOf()
            assertDecodedOnDevice(d, "picture comparison")
            rgba
        }
        assertEquals(software.size, hardware.size, "both paths must produce the same RGBA buffer")
        var sum = 0L
        var worst = 0
        for (i in software.indices) {
            val diff = kotlin.math.abs((software[i].toInt() and 0xFF) - (hardware[i].toInt() and 0xFF))
            sum += diff
            if (diff > worst) worst = diff
        }
        val mean = sum.toDouble() / software.size
        assertTrue(mean < 4.0, "mean channel difference must be small, got $mean (worst $worst)")
    }

    @Test
    fun `REQUIRE fails closed on a stream no device can decode`() {
        assumeHwAcceptance()
        // A machine WITH a working GPU still has to refuse here, and that is
        // the half nothing exercised: where a device opens, the throw never
        // fires, and where none opens the whole suite skips. A still image
        // has no hardware config for any device type at all, so the refusal
        // is reached without pretending the machine lacks a GPU.
        val png = Fixtures.generate(
            dir.resolve("still.png"),
            "-f", "lavfi", "-i", "color=c=blue:size=64x64", "-frames:v", "1",
        )
        assertFailsWith<LibavException>("REQUIRE must refuse what no device can take") {
            VideoDecoder.open(png, HwAccel.REQUIRE).close()
        }
        // And the same file decodes when nothing was required of it, so the
        // refusal is about the request rather than the file being unreadable.
        VideoDecoder.open(png, HwAccel.AUTO).use { d ->
            assertTrue(d.nextFrame() != null, "AUTO must fall back and decode it")
            assertFalse(d.hardwareActive(), "there is no device for this stream to be on")
        }
    }

    @Test
    fun `REQUIRE either decodes on the GPU or fails closed`() {
        assumeHwAcceptance()
        val video = testsrc("require.mp4")
        val decoder = try {
            VideoDecoder.open(video, HwAccel.REQUIRE)
        } catch (e: LibavException) {
            // No usable device on this machine: REQUIRE fails closed by contract.
            println("[hw-test] REQUIRE unavailable, failed closed: ${e.message}")
            return
        }
        decoder.use { d ->
            // The first frame, not the open, is where a device that accepted
            // the stream can still hand decoding back to the CPU -- so it is
            // the other place REQUIRE fails closed, and macOS runners take it
            // every time: VideoToolbox opens and every frame comes back in
            // software. Asked before hardwareActive is read, because that
            // report is the REQUEST until a frame has come back from
            // somewhere.
            val first = try {
                d.nextFrame()
            } catch (e: LibavException) {
                println("[hw-test] REQUIRE failed closed on the first frame: ${e.message}")
                return@use
            }
            assertTrue(first != null, "hw decode must yield a first frame")
            assertTrue(d.hardwareActive(), "a REQUIRE decoder that kept a frame must be on the GPU")
            // Read while a frame is still held: end of stream releases it, and
            // its format goes with it.
            assertDecodedOnDevice(d, "REQUIRE")
            assertEquals(9, generateSequence { d.nextFrame()?.ptsNanos }.count(), "hw decode must yield every frame")
            println("[hw-test] REQUIRE engaged hardware decode, 10 frames")
        }
    }
}
