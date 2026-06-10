package dev.hivens.skinema.player

import dev.hivens.skinema.libav.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Real-time smoke tests: they assert reachable states and frame delivery
 * with generous deadlines, never exact timing -- shared CI runners stall.
 */
class VideoPlayerTest {

    private val dir: Path = Files.createTempDirectory("skinema-player-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun shortVideo(name: String, seconds: String): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", seconds,
        "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
    )

    private fun awaitTrue(deadlineMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test
    fun `plays a non-looping video to Ended and serves frames on the way`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("ended.mp4", "0.5"), loop = false).use { player ->
            var frames = 0
            var lastWidth = 0
            awaitTrue {
                player.acquireFrame()?.let {
                    frames++
                    lastWidth = it.width
                }
                player.state is VideoPlayer.State.Ended
            }
            assertIs<VideoPlayer.State.Ended>(player.state)
            assertTrue(frames >= 1, "at least one frame must be served, got $frames")
            assertEquals(64, lastWidth)
        }
    }

    @Test
    fun `looping playback wraps back to the first frame`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("loop.mp4", "0.3"), loop = true).use { player ->
            var zeroPtsSeen = 0
            awaitTrue {
                player.acquireFrame()?.let { if (it.ptsNanos == 0L) zeroPtsSeen++ }
                zeroPtsSeen >= 2
            }
            assertTrue(zeroPtsSeen >= 2, "pts 0 must come around again on loop, saw it $zeroPtsSeen time(s)")
        }
    }

    @Test
    fun `a missing file surfaces as Failed, not an exception`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(dir.resolve("missing.mp4"), loop = false).use { player ->
            awaitTrue { player.state is VideoPlayer.State.Failed }
            assertIs<VideoPlayer.State.Failed>(player.state)
        }
    }

    @Test
    fun `pause freezes the frame flow and resume restarts it`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("pause.mp4", "10"), loop = true).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")

            player.pause()
            awaitTrue { player.state is VideoPlayer.State.Paused }
            // Drain whatever was published before the pause landed.
            Thread.sleep(100)
            player.acquireFrame()

            val leaked = (1..15).count {
                Thread.sleep(20)
                player.acquireFrame() != null
            }
            assertEquals(0, leaked, "no frames may arrive while paused")

            player.resume()
            assertTrue(awaitTrue { player.acquireFrame() != null }, "frames must flow again after resume")
            assertIs<VideoPlayer.State.Playing>(player.state)
        }
    }

    @Test
    fun `audio-only file plays frameless through the lifecycle`() {
        Fixtures.assumeDecodeEnvironment()
        val tone = Fixtures.generate(
            dir.resolve("tone.flac"),
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "1", "-c:a", "flac",
        )
        val sink = dev.hivens.skinema.audio.FakePcmSink()
        VideoPlayer(tone, loop = false, audio = true, sink = sink).use { player ->
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Ended }, "audio-only playback must reach Ended")
            assertEquals(null, player.acquireFrame(), "frameless mode serves no frames")
            assertEquals(44_100 * 4, sink.totalBytes, "the whole tone reaches the sink")
        }
    }

    @Test
    fun `video frames pace against the audio clock, not the wall`() {
        Fixtures.assumeDecodeEnvironment()
        // 2s of video+audio; the fake sink's play position is manual, so
        // media time moves only when this test says so.
        val av = Fixtures.generate(
            dir.resolve("av.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-map", "0:v", "-map", "1:a", "-t", "2",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:a", "aac", "-shortest",
        )
        val sink = dev.hivens.skinema.audio.FakePcmSink()
        sink.positionFrames.set(0)
        VideoPlayer(av, loop = false, audio = true, sink = sink).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "frame 0 is due at media time 0")

            var leaked: Long = -1
            val frozen = (1..15).none {
                Thread.sleep(20)
                player.acquireFrame()?.let { f -> leaked = f.ptsNanos }
                leaked > 100_000_000L
            }
            assertTrue(frozen, "with the DAC frozen at 0 no frame past 0.1s may appear, saw pts=$leaked")

            // Let the "DAC" consume half a second of samples.
            sink.positionFrames.set(44_100 / 2)
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.ptsNanos?.let { it in 300_000_000L..500_000_000L } == true
                },
                "advancing the device clock must release the frames up to it",
            )
        }
    }

    @Test
    fun `resume re-anchors audio to the frame on screen`() {
        Fixtures.assumeDecodeEnvironment()
        val av = Fixtures.generate(
            dir.resolve("resync.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-map", "0:v", "-map", "1:a", "-t", "2",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:a", "aac", "-shortest",
        )
        val sink = dev.hivens.skinema.audio.FakePcmSink()
        sink.positionFrames.set(0)
        VideoPlayer(av, loop = false, audio = true, sink = sink).use { player ->
            // Frame 0 publishing means the clock is anchored; only then may
            // the test move the device position.
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            // Run the DAC to 300ms and let video follow.
            sink.positionFrames.set(44_100 * 3 / 10)
            var shownPts = -1L
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let { shownPts = it.ptsNanos }
                    shownPts >= 300_000_000L
                },
                "video must follow the device clock to 300ms",
            )

            player.pause()
            awaitTrue { player.state is VideoPlayer.State.Paused }
            // The buffered tail keeps playing after the pause landed: the
            // device consumes another 200ms that video never showed.
            sink.positionFrames.set(44_100 / 2)

            player.resume()
            awaitTrue { player.state is VideoPlayer.State.Playing }
            assertTrue(
                awaitTrue {
                    val pos = player.positionNanos()
                    pos in (shownPts - 1_000_000L)..(shownPts + 100_000_000L)
                },
                "resume must re-anchor time at the shown frame (${shownPts}ns), got ${player.positionNanos()}ns",
            )
        }
    }

    @Test
    fun `seekBy accumulates against the pending target, not the lagging clock`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("accum.mp4", "30"), loop = false).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.pause()
            awaitTrue { player.state is VideoPlayer.State.Paused }

            // Five +2s presses. If seekBy added to the clock (frozen near 0
            // during the landings) every press would compute ~2s; against the
            // pending target they sum to 10s.
            repeat(5) { player.seekBy(2_000_000_000L) }

            var landedPts = -1L
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let { landedPts = it.ptsNanos }
                    landedPts >= 9_500_000_000L
                },
                "five +2s presses must reach ~10s, landed at ${landedPts / 1_000_000}ms",
            )
            assertIs<VideoPlayer.State.Paused>(player.state, "a paused player stays paused at the new frame")
        }
    }

    @Test
    fun `a seek advertises the Seeking state and resolves out of it`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("seekstate.mp4", "10"), loop = false).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.seek(8_000_000_000L)
            // The landing resolves quickly here; assert we leave Seeking and
            // land back in a steady state rather than getting stuck.
            assertTrue(
                awaitTrue { player.state is VideoPlayer.State.Playing },
                "must resolve out of Seeking, stuck at ${player.state}",
            )
        }
    }

    @Test
    fun `rapid seeks coalesce into a landing at the final target`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("spam.mp4", "3"), loop = false).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.pause()
            awaitTrue { player.state is VideoPlayer.State.Paused }
            Thread.sleep(100)
            player.acquireFrame()

            // Ten rapid presses; superseding must collapse the landings
            // instead of replaying ten keyframe runs back to back.
            for (step in 1..10) {
                player.seek(step * 100_000_000L)
            }
            val landed = mutableListOf<Long>()
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let { landed += it.ptsNanos }
                    landed.lastOrNull() == 1_000_000_000L
                },
                "the final target must land, saw $landed",
            )
            assertTrue(landed.size <= 5, "ten queued seeks must coalesce, saw ${landed.size} landings: $landed")
        }
    }

    @Test
    fun `seek revives an Ended player at the requested frame`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("revive.mp4", "0.5"), loop = false).use { player ->
            awaitTrue { player.state is VideoPlayer.State.Ended }
            player.seek(200_000_000L)
            assertTrue(
                awaitTrue { player.acquireFrame()?.ptsNanos == 200_000_000L },
                "the seeked frame must be published",
            )
            assertIs<VideoPlayer.State.Playing>(player.state)
        }
    }
}
