package dev.hivens.skinema.libav

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hardware-decode acceptance. A headless CI runner has no GPU, so the real
 * hw assertions are gated behind SKINEMA_TEST_HWACCEL=1 and run on a dev
 * box with a working device (VAAPI/NVDEC, D3D11VA, VideoToolbox) -- the
 * manual acceptance the ROADMAP's M11 entry calls for. Only the software
 * default is asserted unconditionally, so CI proves nothing GPU-shaped on a
 * platform this change cannot see (macOS always has VideoToolbox).
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
     * A device was opened, so frames must arrive on it. Asserted against the
     * frame rather than against the request, because the request is what
     * every other signal here reports: hardware decode was negotiated away on
     * every open for two months -- the format the decoder aims for lived on
     * the opening thread, and a frame-threaded decoder negotiates on a worker
     * of its own, where a thread-scoped value is simply absent -- and nothing
     * in the suite could tell, because nothing looked at a frame.
     */
    private fun assertDecodedOnDevice(d: VideoDecoder, where: String) {
        val surface = d.negotiatedSurfaceFormat()
        if (surface == LibavAbi.AV_PIX_FMT_NONE) return
        assertEquals(surface, d.lastFrameFormat(), "$where: a device is open, so frames must be its surfaces")
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
            assertTrue(d.hardwareActive(), "a REQUIRE decoder that opened must be on the GPU")
            assertTrue(d.nextFrame() != null, "hw decode must yield a first frame")
            // Read while a frame is still held: end of stream releases it, and
            // its format goes with it.
            assertDecodedOnDevice(d, "REQUIRE")
            assertEquals(9, generateSequence { d.nextFrame()?.ptsNanos }.count(), "hw decode must yield every frame")
            println("[hw-test] REQUIRE engaged hardware decode, 10 frames")
        }
    }
}
