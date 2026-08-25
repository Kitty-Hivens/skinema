package dev.hivens.skinema.player

import dev.hivens.skinema.core.AudioClock
import dev.hivens.skinema.libav.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The backstep gesture, which carried two separate faults that the
 * existing coverage could not reach.
 */
class StepBackwardTest {

    private val dir: Path = Files.createTempDirectory("skinema-stepback-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun awaitTrue(deadlineMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    /**
     * A backstep off a keyframe. Discovery asked the demuxer for the
     * keyframe one NANOSECOND before the shown frame, and every container
     * rounds a seek onto its own timestamp grid -- a millisecond in
     * Matroska, ~98 microseconds here -- so the request landed straight back
     * on the frame it was trying to get behind. The run came out empty, the
     * memo went unwritten, and the step republished what was already on
     * screen.
     *
     * It is not only the press after a scrub, which is what the defect was
     * first reported as: a backstep could not cross a keyframe at all, so on
     * this fixture it could walk at most one group of pictures back from
     * wherever it started, ever. The existing backstep tests use the default
     * key interval over a ten-second clip, which puts the only keyframe at
     * pts 0 where the first-frame guard returns before discovery ever runs --
     * they cannot reach this line. A short -g is what exposes it.
     */
    @Test
    fun `step backward crosses a keyframe boundary`() {
        Fixtures.assumeDecodeEnvironment()
        val dense = Fixtures.generate(
            dir.resolve("gop10.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "20",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-g", "10",
        )
        VideoPlayer(dense, loop = false).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.pause()
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Paused }, "the pause must land")

            // An inexact seek lands ON a keyframe by definition; 4.55s of a
            // one-second key interval resolves to 4.0s.
            player.seek(4_550_000_000L, exact = false)
            assertTrue(
                awaitTrue { player.acquireFrame()?.ptsNanos == 4_000_000_000L },
                "the inexact seek must land on the keyframe",
            )

            for (step in 1..4) {
                val expected = 4_000_000_000L - step * 100_000_000L
                player.stepBackward()
                var landed = -1L
                assertTrue(
                    awaitTrue {
                        player.acquireFrame()?.let { landed = it.ptsNanos }
                        landed == expected
                    },
                    "backstep $step must reach ${expected / 1_000_000}ms, saw ${landed / 1_000_000}ms",
                )
                assertIs<VideoPlayer.State.Paused>(player.state, "a step leaves the player paused")
            }
        }
    }

    /**
     * A burst of presses. A step measures from [VideoPlayer.acquireFrame]'s
     * playhead -- the frame on screen, written by the pacer -- while the seek
     * that serves it returns as soon as the landing is queued. A second press
     * arriving in that window read the pre-step value and computed the same
     * target again, so the picture moved one frame however many times the
     * button was pressed. The forward step has guarded this for as long as it
     * has existed; the backward one never did.
     *
     * Over the scripted source, so every press is a memo hit with no decode
     * between it and the next -- the widest the window gets, and the only
     * shape that fails reliably rather than occasionally.
     */
    @Test
    fun `a burst of backsteps moves one frame per press`() {
        val frames = AtomicLong(0)
        val clock = AudioClock(48_000) { frames.get() }
        val source = ScriptedFrameSource(frameCount = 100, keyframeEvery = 10)
        VideoPlayer(Path.of("scripted"), false, false, clock, null, 1, null, WhenUnwatched.Freeze) { source }.use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            // Media time 2000 ms at the 48 kHz test rate: frame 20, which is
            // a keyframe, so the first press also pays the discovery pass.
            frames.set(2_000L * 48)
            assertTrue(
                awaitTrue { player.acquireFrame()?.ptsNanos == 2_000_000_000L },
                "the playhead must reach frame 20",
            )
            player.pause()
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Paused }, "the pause must land")

            // One press to fill the memo, awaited, so the burst below is
            // measuring the race and not the discovery run.
            player.stepBackward()
            assertTrue(
                awaitTrue { player.acquireFrame()?.ptsNanos == 1_900_000_000L },
                "the first press must land",
            )

            repeat(8) { player.stepBackward() }
            var landed = -1L
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let { landed = it.ptsNanos }
                    landed == 1_100_000_000L
                },
                "eight presses from 1900ms must reach 1100ms, saw ${landed / 1_000_000}ms",
            )
        }
    }
}
