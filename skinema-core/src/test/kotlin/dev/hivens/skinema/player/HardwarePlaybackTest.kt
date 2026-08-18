package dev.hivens.skinema.player

import dev.hivens.skinema.libav.Fixtures
import dev.hivens.skinema.libav.HwAccel
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The player on the GPU path, which is a different set of code from the
 * decoder's own acceptance: the drop-run a seek makes reads pts and geometry
 * off a raw hardware frame with no transfer, and the read-ahead queue holds
 * frames that were downloaded rather than decoded in place. None of it ran at
 * all while the negotiation was falling back to software, so it is smoke-
 * tested here as well as at the decoder.
 *
 * Opt-in like the rest of the hardware suites (SKINEMA_TEST_HWACCEL=1): a
 * runner without a GPU decodes this in software and would assert nothing.
 */
class HardwarePlaybackTest {

    private val dir: Path = Files.createTempDirectory("skinema-hwplayer-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun assumeHwAcceptance() {
        Fixtures.assumeDecodeEnvironment()
        assumeTrue(
            System.getenv("SKINEMA_TEST_HWACCEL") == "1",
            "hardware-decode acceptance is opt-in (SKINEMA_TEST_HWACCEL=1) -- a GPU-less CI cannot run it",
        )
    }

    private fun video(name: String, seconds: String): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "testsrc2=size=128x128:rate=10", "-t", seconds,
        "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-g", "5",
    )

    private fun awaitTrue(deadlineMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test
    fun `a hardware player runs a file to its end and hands out pictures`() {
        assumeHwAcceptance()
        VideoPlayer(video("play.mp4", "1"), loop = false, hardware = HwAccel.AUTO).use { player ->
            var frames = 0
            var pixels = 0
            val ended = awaitTrue {
                player.acquireFrame()?.let {
                    frames++
                    pixels = it.rgba.size
                }
                player.state is VideoPlayer.State.Ended
            }
            assertTrue(ended, "the file must end, state was ${player.state}")
            assertTrue(frames >= 3, "expected pictures on the way, got $frames")
            assertTrue(pixels == 128 * 128 * 4, "a downloaded frame must carry a full RGBA picture, got $pixels")
        }
    }

    @Test
    fun `a seek on the hardware path lands where it was sent`() {
        assumeHwAcceptance()
        // The drop-run toward an exact landing asks for frames with convert
        // off, and on this path that reads a GPU frame's header without
        // downloading it -- the cheap seek the hw path is supposed to keep.
        VideoPlayer(video("seek.mp4", "3"), loop = false, hardware = HwAccel.AUTO).use { player ->
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Playing }, "must start playing")
            player.pause()
            player.seek(2_000_000_000L)
            val landed = awaitTrue {
                player.acquireFrame()
                player.state is VideoPlayer.State.Paused && player.positionNanos() >= 1_900_000_000L
            }
            assertTrue(landed, "seek must land, state=${player.state} pos=${player.positionNanos()}")
        }
    }

    @Test
    fun `a step backward crosses a keyframe on the hardware path`() {
        assumeHwAcceptance()
        VideoPlayer(video("step.mp4", "3"), loop = false, hardware = HwAccel.AUTO).use { player ->
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Playing }, "must start playing")
            player.pause()
            player.seek(1_500_000_000L)
            assertTrue(
                awaitTrue { player.acquireFrame(); player.positionNanos() >= 1_400_000_000L },
                "must land before stepping",
            )
            val from = player.positionNanos()
            player.stepBackward()
            val moved = awaitTrue {
                player.acquireFrame()
                player.positionNanos() < from
            }
            assertTrue(moved, "a backstep must move back, from=$from now=${player.positionNanos()}")
        }
    }
}
