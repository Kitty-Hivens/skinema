package dev.hivens.skinema.player

import dev.hivens.skinema.audio.BoundedPcmSink
import dev.hivens.skinema.core.AudioClock
import dev.hivens.skinema.libav.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
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
    fun `duration surfaces once the file opens`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("dur.mp4", "0.5"), loop = false).use { player ->
            assertTrue(awaitTrue { player.durationNanos != null }, "duration must surface")
            val d = player.durationNanos!!
            assertTrue(d in 400_000_000L..800_000_000L, "0.5s of footage, got ${d}ns")
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
            val d = player.durationNanos
            assertTrue(
                d != null && d in 900_000_000L..1_300_000_000L,
                "frameless playback reports the audio side's duration, got ${d}ns",
            )
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
    fun `seekBy accumulates against the pending target, not the lagging clock`() =
        seekByAccumulationScenario(readAheadFrames = 1)

    @Test
    fun `seekBy accumulates against the pending target at depth 4`() =
        seekByAccumulationScenario(readAheadFrames = 4)

    private fun seekByAccumulationScenario(readAheadFrames: Int) {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("accum.mp4", "30"), loop = false, readAheadFrames = readAheadFrames).use { player ->
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
    fun `backward seekBy bursts accumulate monotonically, not against the lagging clock`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("backaccum.mp4", "30"), loop = false).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            // Jump near the end, settle, then crank backward in a burst.
            player.seek(25_000_000_000L)
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Playing && player.positionNanos() >= 25_000_000_000L })

            // Six -3s presses. The earlier bug read the frozen clock during
            // each landing and could even walk the target upward; correct
            // accumulation lands near 25 - 18 = 7s.
            repeat(6) { player.seekBy(-3_000_000_000L) }

            var landedPts = Long.MAX_VALUE
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let { landedPts = it.ptsNanos }
                    landedPts in 6_000_000_000L..8_000_000_000L
                },
                "six -3s presses from 25s must reach ~7s, landed at ${landedPts / 1_000_000}ms",
            )
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
    fun `rapid seeks coalesce into a landing at the final target`() =
        seekCoalescingScenario(readAheadFrames = 1)

    @Test
    fun `rapid seeks coalesce into a landing at depth 4`() =
        seekCoalescingScenario(readAheadFrames = 4)

    private fun seekCoalescingScenario(readAheadFrames: Int) {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("spam.mp4", "3"), loop = false, readAheadFrames = readAheadFrames).use { player ->
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
            // The keyframe preview (pts 0 here -- the fixture's only
            // keyframe) is deliberate extra feedback, not a landing.
            val landings = landed.count { it >= 100_000_000L }
            assertTrue(landings <= 5, "ten queued seeks must coalesce, saw $landings landings: $landed")
        }
    }

    @Test
    fun `a seek during the loop-wrap park repositions the video`() =
        parkSeekScenario(readAheadFrames = 1)

    @Test
    fun `a seek during the loop-wrap park repositions the video at depth 4`() =
        parkSeekScenario(readAheadFrames = 4)

    private fun parkSeekScenario(readAheadFrames: Int) {
        Fixtures.assumeDecodeEnvironment()
        // The audio stream outlives the video: after the video's EOF the
        // audio thread is still mid-stream, blocked in the bounded write
        // below, so the clock cannot wrap and the park stays pinned open
        // for as long as the test needs.
        val av = Fixtures.generate(
            dir.resolve("park.mp4"),
            "-f", "lavfi", "-t", "2", "-i", "testsrc2=size=64x64:rate=10",
            "-f", "lavfi", "-t", "4", "-i", "sine=frequency=440:sample_rate=44100",
            "-map", "0:v", "-map", "1:a",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:a", "aac",
        )
        // Half a second of buffer; the audio thread blocks on write like a
        // real line and the played position is the test's hands.
        val sink = BoundedPcmSink(capacityFrames = 22_050)
        VideoPlayer(av, loop = true, audio = true, sink = sink, readAheadFrames = readAheadFrames).use { player ->
            try {
                // Drive the "DAC" past the video's end (2s), leaving the
                // audio stream unfinished. The gate is the played position,
                // not a published pts: frames late beyond the drop
                // threshold legitimately skip publishing, so the tail of
                // the footage may never surface in the mailbox.
                assertTrue(
                    awaitTrue {
                        sink.consumeAllButTail(2_205)
                        player.acquireFrame()
                        sink.framePosition() >= 88_200L
                    },
                    "the played position must pass the video's end, at ${sink.framePosition()}",
                )
                // Let the decode thread hit EOF and enter the park, then
                // drain anything published on the way -- the assertion
                // below must only be satisfiable by a post-seek publish.
                Thread.sleep(200)
                player.acquireFrame()

                // The bug: a parked seek was handled decoder-less, so the
                // video never repositioned and nothing published until the
                // audio side wrapped. The target must sit above half the
                // last pts: the audio thread re-anchors the clock to it,
                // and a lower target would end the park by itself -- the
                // self-healing flavor of the same bug, just less frozen.
                // The landing frame races the late-frame catch-up in the
                // mailbox, so assert that publishing resumes at all -- the
                // broken path publishes nothing within any deadline.
                player.seek(1_500_000_000L)
                assertTrue(
                    awaitTrue { player.acquireFrame() != null },
                    "a parked player must land a seek instead of freezing",
                )
            } finally {
                sink.release()
            }
        }
    }

    @Test
    fun `a clock jump far ahead is caught up and frames keep flowing`() =
        chaseLivenessScenario(readAheadFrames = 1)

    @Test
    fun `a clock jump far ahead is caught up at depth 4`() =
        chaseLivenessScenario(readAheadFrames = 4)

    private fun chaseLivenessScenario(readAheadFrames: Int) {
        Fixtures.assumeDecodeEnvironment()
        // Liveness for the catch-up path: late frames drop unconverted
        // behind shouldPublishLateFrame, and a broken policy would starve
        // the mailbox forever. An AudioClock over a manual frame counter
        // is a thread-safe controllable clock. The rate must stay
        // realistic: mediaNanos multiplies the frame delta by 1e9 before
        // dividing, so a made-up rate like 1e9 overflows Long.
        val frames = AtomicLong(0)
        val clock = AudioClock(48_000) { frames.get() }
        VideoPlayer(
            shortVideo("chase.mp4", "30"),
            loop = false,
            explicitClock = clock,
            readAheadFrames = readAheadFrames,
        ).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            frames.set(24_000)
            var seen = -1L
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let { seen = it.ptsNanos }
                    seen >= 500_000_000L
                },
                "normal pacing must follow the clock, saw ${seen}ns",
            )

            // The jump: every frame for the next ~19.5s of footage is now
            // late beyond the drop threshold.
            frames.set(960_000)
            assertTrue(
                awaitTrue(deadlineMs = 5_000) {
                    player.acquireFrame()?.let { seen = it.ptsNanos }
                    seen >= 20_000_000_000L
                },
                "the catch-up run must reach the clock, saw ${seen}ns, state=${player.state}",
            )
        }
    }

    @Test
    fun `a clock re-anchored mid-sleep is noticed without a command`() =
        staleClockScenario(readAheadFrames = 1)

    @Test
    fun `a clock re-anchored mid-sleep is noticed at depth 4`() =
        staleClockScenario(readAheadFrames = 4)

    private fun staleClockScenario(readAheadFrames: Int) {
        Fixtures.assumeDecodeEnvironment()
        val av = Fixtures.generate(
            dir.resolve("stale.mp4"),
            "-f", "lavfi", "-t", "30", "-i", "testsrc2=size=64x64:rate=10",
            "-f", "lavfi", "-t", "31", "-i", "sine=frequency=440:sample_rate=44100",
            "-map", "0:v", "-map", "1:a",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:a", "aac",
        )
        val sink = BoundedPcmSink(capacityFrames = 8_820)
        VideoPlayer(av, loop = false, audio = true, sink = sink, readAheadFrames = readAheadFrames).use { player ->
            try {
                assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
                // Nothing is consumed, so the buffer fills and the audio
                // thread parks inside write -- its half of the next seek
                // will run late, which is the race: a fast video landing
                // finishing before the audio side anchors the clock.
                assertTrue(awaitTrue { sink.writerParked }, "the audio thread must park in write")

                player.seek(20_000_000_000L)
                var landed = -1L
                assertTrue(
                    awaitTrue {
                        player.acquireFrame()?.let { landed = it.ptsNanos }
                        landed >= 20_000_000_000L
                    },
                    "the landing must publish against the stale clock, saw ${landed}ns",
                )

                // Release the audio thread: it now anchors the clock at the
                // target and restarts the sink. The pace loop sleeps on a
                // wait computed BEFORE the anchor (~20s of stale distance);
                // it must notice the re-anchor on its own, with no command
                // arriving to wake it.
                var next = -1L
                assertTrue(
                    awaitTrue(deadlineMs = 3_000) {
                        sink.consumeAllButTail(0)
                        player.acquireFrame()?.let { next = it.ptsNanos }
                        next > 20_000_000_000L
                    },
                    "frames past the landing must flow once the clock anchors, saw ${next}ns",
                )
            } finally {
                sink.release()
            }
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
