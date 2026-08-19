package dev.hivens.skinema.player

import dev.hivens.skinema.audio.BoundedPcmSink
import dev.hivens.skinema.audio.FakePcmSink
import dev.hivens.skinema.audio.PacedPcmSink
import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.core.AudioClock
import dev.hivens.skinema.core.MediaClock
import dev.hivens.skinema.core.PlaybackClock
import dev.hivens.skinema.libav.Fixtures
import org.junit.jupiter.api.Assumptions.assumeTrue
import dev.hivens.skinema.libav.LibavException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * A video that cannot be opened must fail the player, sound or no sound.
     * Every throw out of the open used to take the frameless door when the
     * file had audio -- an undecodable codec, a truncated stream with no
     * dimensions, a hardware-decode request the machine could not honour --
     * so the player played on as audio-only with the cause dropped, and the
     * fallback a consumer was told to build never ran. Only "there is no
     * video here" is frameless; the covered-audio test above pins that side.
     */
    @Test
    fun `a video that cannot be opened fails even when the file has sound`() {
        Fixtures.assumeDecodeEnvironment()
        val tone = Fixtures.generate(
            dir.resolve("failopen.flac"),
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "1", "-c:a", "flac",
        )
        val boom = LibavException("no decoder for this video stream")
        VideoPlayer(tone, false, true, null, FakePcmSink(), 1, null) { throw boom }.use { player ->
            assertTrue(
                awaitTrue { player.state is VideoPlayer.State.Failed },
                "an unopenable video must surface as Failed, state=${player.state}",
            )
            assertEquals(boom, (player.state as VideoPlayer.State.Failed).cause, "the cause must reach the consumer")
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
        val sink = FakePcmSink()
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

    private fun silent(name: String, seconds: String = "0.5") = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10:duration=$seconds",
        "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-an",
    )

    /**
     * Asking for sound a file does not have, or a machine cannot play, must
     * not stop the picture. The player documents both as degrading to silent
     * playback; a gate that waited for the audio side to say it was finished
     * hung on them instead, because a side that never started never said so.
     */
    @Test
    fun `a file with no sound still finishes when audio was asked for`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(silent("mute.mp4"), loop = false, audio = true, sink = FakePcmSink()).use { player ->
            assertTrue(
                awaitTrue(deadlineMs = 15_000) { player.acquireFrame(); player.state is VideoPlayer.State.Ended },
                "silent playback must end, state ${player.state} at ${player.positionNanos() / 1_000_000}ms",
            )
        }
    }

    @Test
    fun `a file with no sound still loops when audio was asked for`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(silent("mute-loop.mp4"), loop = true, audio = true, sink = FakePcmSink()).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            var last = -1L
            assertTrue(
                awaitTrue(deadlineMs = 15_000) {
                    val pts = player.acquireFrame()?.ptsNanos ?: return@awaitTrue false
                    val wrapped = pts < last
                    last = pts
                    wrapped
                },
                "the lap must come round, state ${player.state}",
            )
        }
    }

    /**
     * A pause near the end of a lap has to stop the lap, not wrap it. The wait
     * for the file's own time to run out reported the lap complete when the
     * state merely changed, so the wrap ran anyway: the decoder rewound, the
     * sound restarted and the clock came off pause under a paused player.
     */
    @Test
    fun `pausing while a lap plays out leaves the player paused and still`() {
        Fixtures.assumeDecodeEnvironment()
        val still = Fixtures.generate(
            dir.resolve("still-lap.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-frames:v", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        VideoPlayer(still, loop = true, audio = false).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.pause()
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Paused }, "the pause must land")
            val at = player.positionNanos()
            Thread.sleep(400)
            assertIs<VideoPlayer.State.Paused>(player.state, "a paused player must stay paused")
            assertEquals(at, player.positionNanos(), "a paused player's position must stand")
        }
    }

    /**
     * Reaching the end stops the clock, so leaving the end has to start it
     * again -- including when the seek that leaves lands past the end and
     * comes straight back, which took a different door out and left the
     * timeline running away from a file nothing was playing.
     */
    @Test
    fun `seeking out of the end revives playback, and seeking past it does not`() {
        Fixtures.assumeDecodeEnvironment()
        val video = silent("revive.mp4", seconds = "1")
        VideoPlayer(video, loop = false, audio = false).use { player ->
            assertTrue(
                awaitTrue(deadlineMs = 15_000) { player.acquireFrame(); player.state is VideoPlayer.State.Ended },
                "playback must end first",
            )
            player.seek(0)
            var seen = -1L
            assertTrue(
                awaitTrue { player.acquireFrame()?.let { seen = it.ptsNanos }; seen > 300_000_000L },
                "a seek out of the end must play again, reached ${seen}ns",
            )

            assertTrue(
                awaitTrue(deadlineMs = 15_000) { player.acquireFrame(); player.state is VideoPlayer.State.Ended },
                "playback must end again",
            )
            player.seek(30_000_000_000L)
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Ended }, "a seek past the end ends")
            val at = player.positionNanos()
            Thread.sleep(300)
            assertEquals(at, player.positionNanos(), "the timeline must not run on past the end")
        }
    }

    /**
     * A lap is over when the file's time is up, not when the decoder runs dry.
     * A file that decodes faster than it plays -- a single frame, a still
     * handed to the player, a truncated stream -- looped on itself as fast as
     * the machine allowed and burned a whole core on content nobody was
     * watching. loop = true is the documented default of the headline example,
     * so an image dropped in where a video was expected did this silently.
     */
    @Test
    fun `a one-frame looping file does not spin the decode thread`() {
        Fixtures.assumeDecodeEnvironment()
        val still = Fixtures.generate(
            dir.resolve("one-frame.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-frames:v", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        val threads = java.lang.management.ManagementFactory.getThreadMXBean()
        VideoPlayer(still, loop = true, audio = false).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            val decode = threads.allThreadIds.first { threads.getThreadInfo(it)?.getThreadName() == "skinema-decode" }
            val before = threads.getThreadCpuTime(decode)
            val wall = System.nanoTime()
            var frames = 0
            while (System.nanoTime() - wall < 2_000_000_000L) {
                if (player.acquireFrame() != null) frames++
                Thread.sleep(10)
            }
            val cores = (threads.getThreadCpuTime(decode) - before).toDouble() / (System.nanoTime() - wall)
            assertTrue(frames > 0, "the file must keep looping, saw no frames")
            // Measured: healthy is a few thousandths of a core, the spin this
            // guards was just under half of one. The old bar sat at 0.5,
            // calibrated from the broken measurement, so it passed either way
            // -- a check that could not fail on the machine that set it.
            assertTrue(cores < 0.05, "the decode thread must not spin on a short lap, used $cores of a core")
        }
    }

    /**
     * Neither stream's end is the file's end on its own. A short picture over
     * a long track ended the player mid-track; a short track under a long
     * picture stranded the picture where the sound stopped, still reporting
     * Playing. Both are ordinary files, and both are the same mistake read
     * from opposite sides.
     */
    @Test
    fun `playback ends when both streams do, not when the first one does`() {
        Fixtures.assumeDecodeEnvironment()
        for ((name, videoSeconds, audioSeconds) in listOf(
            Triple("short-audio.mp4", "3", "1"),
            Triple("short-video.mp4", "1", "3"),
        )) {
            val file = Fixtures.generate(
                dir.resolve(name),
                "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10:duration=$videoSeconds",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=$audioSeconds",
                "-map", "0:v", "-map", "1:a",
                "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
                "-c:a", "aac",
            )
            VideoPlayer(file, loop = false, audio = true, sink = FakePcmSink()).use { player ->
                val deadline = System.nanoTime() + 30_000_000_000L
                while (player.state !is VideoPlayer.State.Ended && System.nanoTime() < deadline) {
                    player.acquireFrame()
                    Thread.sleep(10)
                }
                assertIs<VideoPlayer.State.Ended>(player.state, "$name must finish")
                val end = player.positionNanos()
                assertTrue(
                    end >= 2_500_000_000L,
                    "$name should play out both streams, stopped at ${end / 1_000_000}ms",
                )
                // On the duration exactly, not a frame period short of it and
                // not past it: a progress bar has to be able to reach its end
                // and has to stop there.
                assertEquals(player.durationNanos, end, "$name: the end is the duration")
                Thread.sleep(120)
                assertEquals(end, player.positionNanos(), "$name: position must freeze at Ended")
            }
        }
    }

    /**
     * A lap of a file whose picture and sound end together -- practically
     * every normally muxed clip -- must cost the file's own time. It cost a
     * quarter more: the wrap waits for the sound to finish, and the sound's
     * end-of-track wait was measured against a container timestamp its
     * device-frame clock could never reach, so it ran to its stall deadline
     * instead. The picture stood on its last frame for that grace, state
     * reporting Playing throughout.
     */
    @Test
    fun `a looping file whose streams end together laps in its own time`() {
        Fixtures.assumeDecodeEnvironment()
        val av = Fixtures.generate(
            dir.resolve("equal-ends.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-map", "0:v", "-map", "1:a", "-t", "2",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:a", "flac",
        )
        // A line that plays in real time. FakePcmSink accepts a whole file
        // between two clock readings and calls it played, which makes every
        // question about when a lap ends unanswerable.
        val sink = PacedPcmSink(bufferFrames = 8_820)
        VideoPlayer(av, loop = true, audio = true, sink = sink).use { player ->
            try {
                val wrapWall = mutableListOf<Long>()
                val wrapGapMs = mutableListOf<Long>()
                var lastPts = -1L
                var lastPublish = 0L
                val deadline = System.nanoTime() + 25_000_000_000L
                while (wrapWall.size < 4 && System.nanoTime() < deadline) {
                    val pts = player.acquireFrame()?.ptsNanos
                    if (pts != null) {
                        val now = System.nanoTime()
                        if (pts < lastPts) {
                            wrapWall += now
                            wrapGapMs += (now - lastPublish) / 1_000_000
                        }
                        lastPts = pts
                        lastPublish = now
                    }
                    Thread.sleep(2)
                }
                assertEquals(4, wrapWall.size, "four wraps must come round inside the deadline")
                val laps = wrapWall.zipWithNext { a, b -> (b - a) / 1_000_000 }
                // The shortest lap and the shortest wrap gap, not the average
                // of either: a stalled runner lengthens individual laps, while
                // the defect lengthened every one by the same fixed grace.
                // Three laps, so one stalled one still leaves a clean minimum.
                assertTrue(laps.min() < 2_350, "a 2s lap must not carry the tail wait's grace, laps=$laps ms")
                assertTrue(
                    wrapGapMs.min() < 400,
                    "the picture must not stand still across the wrap, gaps=$wrapGapMs ms",
                )
            } finally {
                sink.release()
            }
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
        val sink = FakePcmSink()
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
        val sink = FakePcmSink()
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

    /**
     * A seek landing inside the window where a lap is playing itself out.
     * The wait reports that the wrap was superseded -- the seek has already
     * put the decoder and the clock where the user asked -- and the caller
     * then restored the end-of-stream mark it had just voided. The picture
     * stood on the landing frame until the clock walked the rest of the lap
     * out and wrapped: the seek honoured by the clock and thrown away by the
     * picture. One frame per second widens the window to a whole second; at
     * ordinary frame rates it is one frame period, and the defect is the
     * same size as the lap.
     */
    @Test
    fun `a seek while the lap plays out is not undone by the wrap`() {
        Fixtures.assumeDecodeEnvironment()
        val slow = Fixtures.generate(
            dir.resolve("slowlap.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=1", "-t", "3",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        )
        VideoPlayer(slow, loop = true, audio = false).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            // Past the last frame (pts 2s) but inside the file's 3s: the
            // decode thread is waiting the lap out.
            assertTrue(
                awaitTrue { player.positionNanos() > 2_100_000_000L },
                "the lap must reach its play-out window, at ${player.positionNanos() / 1_000_000}ms",
            )
            player.seek(500_000_000L)
            assertTrue(
                awaitTrue { player.acquireFrame()?.ptsNanos == 1_000_000_000L },
                "the landing must publish",
            )
            var next = -1L
            assertTrue(
                awaitTrue(deadlineMs = 1_500) {
                    player.acquireFrame()?.let { next = it.ptsNanos }
                    next == 2_000_000_000L
                },
                "playback must carry on from the landing, saw ${next / 1_000_000}ms",
            )
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

    /** Video plus two flac tracks at different rates (the discriminator). */
    private fun twoAudioTracks(name: String): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
        "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000",
        "-map", "0:v", "-map", "1:a", "-map", "2:a", "-t", "30",
        "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        "-c:a", "flac", "-disposition:a:0", "default",
    )

    @Test
    fun `audio tracks surface and the constructor picks one`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        sink.positionFrames.set(0)
        VideoPlayer(twoAudioTracks("pick.mkv"), loop = false, audio = true, sink = sink, audioTrack = 2).use { player ->
            assertTrue(awaitTrue { player.audioTracks.size == 2 }, "both tracks must surface")
            assertEquals(2, player.activeAudioTrack)
            assertEquals(48_000, sink.sampleRate, "the requested track drives the line")
        }
    }

    @Test
    fun `a live track switch keeps the picture flowing`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        sink.positionFrames.set(0)
        VideoPlayer(twoAudioTracks("switch.mkv"), loop = false, audio = true, sink = sink).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            assertEquals(1, player.activeAudioTrack, "the default disposition wins the pick")
            assertEquals(44_100, sink.sampleRate)

            player.selectAudioTrack(2)
            assertTrue(awaitTrue { player.activeAudioTrack == 2 }, "the switch must land")
            assertEquals(48_000, sink.sampleRate, "the line reopened at the new rate")

            // The rebased clock masters the picture: advance the fresh
            // line's DAC and frames must follow.
            sink.positionFrames.set(48_000 / 2)
            var seen = -1L
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let { seen = it.ptsNanos }
                    seen >= 400_000_000L
                },
                "video must pace on the rebased clock, saw ${seen}ns",
            )
        }
    }

    @Test
    fun `a covered audio file plays frameless and ships the cover`() {
        Fixtures.assumeDecodeEnvironment()
        // The only "video" stream is the attached picture. Playing it
        // would end the player at its one frame while the sound runs on;
        // the file must take the frameless path with the cover as bytes.
        val png = Fixtures.generate(
            dir.resolve("cover.png"),
            "-f", "lavfi", "-i", "color=c=red:size=16x16", "-frames:v", "1",
        )
        val flac = Fixtures.generate(
            dir.resolve("covered.flac"),
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-i", png.toString(),
            "-map", "0:a", "-map", "1:v", "-t", "1",
            "-c:a", "flac", "-c:v", "png", "-disposition:v:0", "attached_pic",
        )
        val sink = FakePcmSink()
        VideoPlayer(flac, loop = false, audio = true, sink = sink).use { player ->
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Ended }, "the frameless lifecycle must complete")
            assertEquals(null, player.acquireFrame(), "the cover is metadata, not a video stream")
            val art = player.coverArt
            assertTrue(art != null && art.size > 8, "the cover ships as bytes")
        }
    }

    @Test
    fun `a dead audio pipeline advertises no tracks`() {
        Fixtures.assumeDecodeEnvironment()
        // The sink throws at open -- the no-audio-device machine. The
        // player degrades to silent wall-clock playback and must not
        // offer a selector nothing would serve.
        val deaf = object : PcmSink {
            override fun open(sampleRate: Int) = throw IllegalStateException("no audio device")
            override fun write(data: ByteArray, offset: Int, length: Int) = Unit
            override fun stop() = Unit
            override fun start() = Unit
            override fun flush() = Unit
            override fun framePosition() = 0L
            override fun setVolume(volume: Float) = Unit
            override fun close() = Unit
        }
        VideoPlayer(twoAudioTracks("deaf.mkv"), loop = false, audio = true, sink = deaf).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "silent fallback must play")
            assertTrue(player.audioTracks.isEmpty(), "a dead pipeline must not advertise tracks")
            assertEquals(null, player.activeAudioTrack)
        }
    }

    /**
     * The audio side can leave in the middle of a file: a device that dies
     * for good, a track switch onto a rate the machine refuses. Which side
     * masters the clock was decided once, at the open, so the player went on
     * deferring to a thread that was no longer there -- its own seeks stopped
     * re-anchoring the clock, and the landing counter the dead thread left
     * owed made the pacer hold every frame it had. The picture stopped on the
     * landing frame while state reported Playing.
     */
    @Test
    fun `a seek keeps the picture moving after the audio thread has died`() {
        Fixtures.assumeDecodeEnvironment()
        val paced = PacedPcmSink(bufferFrames = 8_820)
        val gone = java.util.concurrent.atomic.AtomicBoolean(false)
        // A device that works and then does not, the way a yanked one throws.
        val sink = object : PcmSink by paced {
            override fun write(data: ByteArray, offset: Int, length: Int) {
                if (gone.get()) throw IllegalStateException("device gone")
                paced.write(data, offset, length)
            }
        }
        VideoPlayer(twoAudioTracks("audiodeath.mkv"), loop = false, audio = true, sink = sink).use { player ->
            try {
                assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
                gone.set(true)
                // The next write throws and takes the audio thread with it.
                Thread.sleep(300)

                player.seek(10_000_000_000L)
                var landed = -1L
                assertTrue(
                    awaitTrue {
                        player.acquireFrame()?.let { landed = it.ptsNanos }
                        landed >= 10_000_000_000L
                    },
                    "the landing must publish, saw ${landed / 1_000_000}ms",
                )
                var next = -1L
                assertTrue(
                    awaitTrue(deadlineMs = 3_000) {
                        player.acquireFrame()?.let { next = it.ptsNanos }
                        next > landed
                    },
                    "the picture must keep moving past the landing, stuck at ${landed / 1_000_000}ms",
                )
            } finally {
                paced.release()
            }
        }
    }

    /**
     * A device that stopped consuming has nothing left to say about when a
     * lap ends, and recovery retries for as long as the outage lasts --
     * which is unbounded by design. Holding the wrap for it froze the
     * picture on the last frame of the lap for the whole outage, state
     * reporting Playing, media time running on the wall clock the watchdog
     * had handed it.
     */
    @Test
    fun `a looping file wraps its lap even while the audio device is gone`() {
        Fixtures.assumeDecodeEnvironment()
        val av = Fixtures.generate(
            dir.resolve("gonedevice.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-map", "0:v", "-map", "1:a", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:a", "flac",
        )
        // Nothing ever consumes, and the device cannot be reopened: the
        // writer parks, the watchdog declares the loss, and recovery keeps
        // failing for the rest of the test.
        val sink = BoundedPcmSink(capacityFrames = 4_410, reopenable = false)
        VideoPlayer(av, loop = true, audio = true, sink = sink).use { player ->
            try {
                assertTrue(awaitTrue { sink.writerParked }, "the writer must park on the frozen device")
                var last = -1L
                assertTrue(
                    awaitTrue(deadlineMs = 20_000) {
                        val pts = player.acquireFrame()?.ptsNanos ?: return@awaitTrue false
                        val wrapped = pts < last
                        last = pts
                        wrapped
                    },
                    "the lap must come round with the device gone, state=${player.state}",
                )
            } finally {
                sink.release()
            }
        }
    }

    @Test
    fun `rate clamps to the supported envelope`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("clamp.mp4", "1"), loop = true).use { player ->
            player.setRate(10f)
            assertTrue(awaitTrue { player.rate == 4f }, "10x must clamp to 4x")
            player.setRate(0.1f)
            assertTrue(awaitTrue { player.rate == 0.5f }, "0.1x must clamp to 0.5x")
        }
    }

    @Test
    fun `setRate scales the silent clock`() {
        Fixtures.assumeDecodeEnvironment()
        val now = AtomicLong(1)
        val clock = PlaybackClock { now.get() }
        VideoPlayer(shortVideo("silentrate.mp4", "30"), loop = false, explicitClock = clock).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.setRate(2f)
            assertTrue(awaitTrue { player.rate == 2f }, "the rate command must land")
            // One fake second at rate 2 is two seconds of media; the
            // catch-up run must carry the picture there.
            now.addAndGet(1_000_000_000L)
            var seen = -1L
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let { seen = it.ptsNanos }
                    seen >= 1_900_000_000L
                },
                "one fake second at rate 2 must pace to ~2s of footage, saw ${seen}ns",
            )
        }
    }

    @Test
    fun `setRate reaches the audio clock through the pipeline`() {
        Fixtures.assumeDecodeEnvironment()
        val av = Fixtures.generate(
            dir.resolve("audiorate.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-map", "0:v", "-map", "1:a", "-t", "30",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:a", "flac",
        )
        val sink = FakePcmSink()
        sink.positionFrames.set(0)
        VideoPlayer(av, loop = false, audio = true, sink = sink).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            // The pipeline serializes its commands: the tempo applies
            // before the seek anchors, making the playhead deterministic.
            player.setRate(2f)
            player.seek(5_000_000_000L)
            assertTrue(
                awaitTrue { player.positionNanos() in 4_990_000_000L..5_010_000_000L },
                "the seek must anchor near 5s, got ${player.positionNanos()}",
            )
            val anchor = player.positionNanos()
            // A quarter second of device frames covers half a second of
            // media at tempo 2 -- as a band, not a point. This device steps
            // once and then stands still, and the clock fills the gap after
            // a step with wall time up to its ceiling; which thread reads
            // first after the step decides how much of that is in this
            // reading. A rate that never reached the pipeline lands 250 ms
            // out, nowhere near the band.
            sink.positionFrames.addAndGet(11_025)
            val due = anchor + 500_000_000L
            assertTrue(
                awaitTrue { player.positionNanos() in due..(due + AudioClock.MAX_INTERPOLATION_NANOS) },
                "the mastered clock must run at the tempo, got ${player.positionNanos()} against $due",
            )
        }
    }

    @Test
    fun `step forward advances exactly one frame and stays paused`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("stepf.mp4", "10"), loop = false).use { player ->
            var shown = -1L
            assertTrue(awaitTrue { player.acquireFrame()?.let { shown = it.ptsNanos } != null }, "playback must start")
            player.pause()
            awaitTrue { player.state is VideoPlayer.State.Paused }
            Thread.sleep(100)
            player.acquireFrame()?.let { shown = it.ptsNanos }

            player.stepForward()
            var stepped = -1L
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let { stepped = it.ptsNanos }
                    stepped == shown + 100_000_000L
                },
                "one step must advance one 100ms frame from ${shown}ns, saw ${stepped}ns",
            )
            assertIs<VideoPlayer.State.Paused>(player.state)

            player.stepForward()
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let { stepped = it.ptsNanos }
                    stepped == shown + 200_000_000L
                },
                "the second step advances one more, saw ${stepped}ns",
            )
            assertTrue(
                awaitTrue { player.positionNanos() == shown + 200_000_000L },
                "position must follow the stepped frame, got ${player.positionNanos()}",
            )
        }
    }

    @Test
    fun `step forward pauses a playing player first`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("stepauto.mp4", "10"), loop = false).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.stepForward()
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Paused }, "a step must pause the player")
        }
    }

    @Test
    fun `step backward returns to the previous frame`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("stepb.mp4", "10"), loop = false).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.seek(2_000_000_000L)
            var shown = -1L
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let { shown = it.ptsNanos }
                    shown >= 2_000_000_000L
                },
                "the seek must land",
            )
            player.pause()
            awaitTrue { player.state is VideoPlayer.State.Paused }
            Thread.sleep(100)
            player.acquireFrame()?.let { shown = it.ptsNanos }

            var stepped = -1L
            player.stepBackward()
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let { stepped = it.ptsNanos }
                    stepped == shown - 100_000_000L
                },
                "a backstep from ${shown}ns must land one frame earlier, saw ${stepped}ns",
            )
            assertIs<VideoPlayer.State.Paused>(player.state)

            player.stepBackward()
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let { stepped = it.ptsNanos }
                    stepped == shown - 200_000_000L
                },
                "the second backstep lands one more frame back, saw ${stepped}ns",
            )
        }
    }

    @Test
    fun `step backward at the first frame holds it`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(shortVideo("stepzero.mp4", "2"), loop = false).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.pause()
            awaitTrue { player.state is VideoPlayer.State.Paused }
            player.seek(0)
            assertTrue(
                awaitTrue { player.acquireFrame()?.ptsNanos == 0L },
                "the landing at zero must publish",
            )
            player.stepBackward()
            Thread.sleep(200)
            assertEquals(null, player.acquireFrame(), "there is nothing before the first frame")
            assertIs<VideoPlayer.State.Paused>(player.state)
        }
    }

    @Test
    fun `a step re-anchors the audio to the stepped frame`() {
        Fixtures.assumeDecodeEnvironment()
        val av = Fixtures.generate(
            dir.resolve("stepav.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-map", "0:v", "-map", "1:a", "-t", "10",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:a", "flac",
        )
        val sink = FakePcmSink()
        sink.positionFrames.set(0)
        VideoPlayer(av, loop = false, audio = true, sink = sink).use { player ->
            var shown = -1L
            assertTrue(awaitTrue { player.acquireFrame()?.let { shown = it.ptsNanos } != null }, "playback must start")
            player.pause()
            awaitTrue { player.state is VideoPlayer.State.Paused }
            Thread.sleep(100)
            player.acquireFrame()?.let { shown = it.ptsNanos }

            player.stepForward()
            val target = shown + 100_000_000L
            assertTrue(
                awaitTrue { player.acquireFrame()?.ptsNanos == target },
                "the step must publish the next frame",
            )
            assertTrue(
                awaitTrue { player.positionNanos() in (target - 1_000_000L)..(target + 1_000_000L) },
                "the mastered clock must re-anchor at the stepped frame, got ${player.positionNanos()}",
            )
        }
    }

    @Test
    fun `subtitle tracks surface on the player`() {
        Fixtures.assumeDecodeEnvironment()
        val srt = dir.resolve("surface.srt")
        Files.writeString(srt, "1\n00:00:00,500 --> 00:00:02,000\nHello subs\n")
        val video = Fixtures.generate(
            dir.resolve("subsurface.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
            "-i", srt.toString(),
            "-map", "0:v", "-map", "1", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:s", "srt",
        )
        VideoPlayer(video, loop = false).use { player ->
            assertTrue(awaitTrue { player.subtitleTracks.size == 1 }, "the track must surface")
            assertEquals("subrip", player.subtitleTracks[0].codecName)
        }
    }

    private fun subbedFixture(name: String): Path {
        val srt = dir.resolve("$name.srt")
        Files.writeString(srt, "1\n00:00:00,500 --> 00:00:04,000\nHello subs\n")
        return Fixtures.generate(
            dir.resolve("$name.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
            "-i", srt.toString(),
            "-map", "0:v", "-map", "1", "-t", "10",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:s", "srt",
        )
    }

    @Test
    fun `subtitles select, render and deselect`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        VideoPlayer(subbedFixture("subflow"), loop = true).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            val id = player.subtitleTracks.single().id
            player.selectSubtitleTrack(id)
            assertTrue(awaitTrue { player.activeSubtitleTrack == id }, "the selection must land")
            var seen = false
            assertTrue(
                awaitTrue {
                    player.acquireSubtitles()?.let { seen = seen || it.patches.isNotEmpty() }
                    seen
                },
                "the cue must reach the overlay mailbox",
            )
            player.selectSubtitleTrack(null)
            assertTrue(awaitTrue { player.activeSubtitleTrack == null }, "deselect must land")
        }
    }

    /** A font from the host, or null when this machine ships none to attach. */
    private fun hostFont(): Path? = runCatching {
        val p = ProcessBuilder("fc-match", "-f", "%{file}", "sans").redirectErrorStream(true).start()
        val out = p.inputStream.readAllBytes().decodeToString().trim()
        p.waitFor()
        Path.of(out).takeIf { out.endsWith(".ttf", true) || out.endsWith(".otf", true) }
    }.getOrNull()?.takeIf { Files.isReadable(it) }

    @Test
    fun `a file whose fonts ride inside it still renders its subtitles`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        // Anime releases ship their typesetting faces as attachments, and the
        // extraction that hands them to libass had never executed in any test
        // -- no fixture carried one. This proves the path runs and leaves the
        // render working; it does NOT prove the glyphs came from the attached
        // face, which would need a font this machine does not otherwise have.
        val font = hostFont()
        assumeTrue(font != null, "no host font to attach")
        val srt = dir.resolve("attached.srt")
        Files.writeString(srt, "1\n00:00:00,500 --> 00:00:04,000\nTypeset\n")
        val video = Fixtures.generate(
            dir.resolve("attached.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
            "-i", srt.toString(),
            "-attach", font.toString(),
            "-metadata:s:t", "mimetype=application/x-truetype-font",
            "-map", "0:v", "-map", "1", "-t", "10",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:s", "ass",
        )
        VideoPlayer(video, loop = true).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            val id = player.subtitleTracks.single().id
            player.selectSubtitleTrack(id)
            assertTrue(awaitTrue { player.activeSubtitleTrack == id }, "the selection must land")
            var patched = false
            assertTrue(
                awaitTrue {
                    player.acquireSubtitles()?.let { patched = patched || it.patches.isNotEmpty() }
                    patched
                },
                "text must reach the overlay with an attachment present",
            )
        }
    }

    @Test
    fun `a seek burst carries the subtitles to the final target`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        // What a consumer scrubbing a timeline sees: the text has to match
        // where the presses stopped, not where one of them was overtaken.
        //
        // The line that retargets the subtitle side inside the supersede had
        // never executed -- that branch runs in other tests, always without a
        // pipeline attached -- and covering it is how this test started. It
        // does NOT guard that line: removing it still passes, because the
        // pipeline repairs itself from a clock that moved under it. So the
        // line is promptness rather than correctness, and what is asserted
        // here is the property, which no other test covers.
        val srt = dir.resolve("burst.srt")
        // One cue, and it sits where the burst ends rather than where it
        // starts, so text on screen means the subtitle side followed the
        // final target and not a superseded one.
        Files.writeString(srt, "1\n00:00:04,000 --> 00:00:08,000\nFinal\n")
        val video = Fixtures.generate(
            dir.resolve("burst.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
            "-i", srt.toString(),
            "-map", "0:v", "-map", "1", "-t", "10",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:s", "srt",
        )
        VideoPlayer(video, loop = false).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            val id = player.subtitleTracks.single().id
            player.selectSubtitleTrack(id)
            assertTrue(awaitTrue { player.activeSubtitleTrack == id }, "the selection must land")
            player.pause()
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Paused }, "the pause must land")

            // Rapid enough that later presses supersede a landing in flight.
            for (step in 1..10) player.seek(step * 500_000_000L)
            assertTrue(
                awaitTrue { player.acquireFrame()?.ptsNanos == 5_000_000_000L },
                "the final target must land, pos=${player.positionNanos()}",
            )
            var patched = false
            assertTrue(
                awaitTrue {
                    player.acquireSubtitles()?.let { patched = patched || it.patches.isNotEmpty() }
                    patched
                },
                "the cue covering the final target must reach the overlay",
            )
        }
    }

    @Test
    fun `the canvas size a consumer announces reaches the rasterizer`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        // The player's forwarder had never been called by any test -- the
        // pipeline's own setCanvasSize was covered, the way through the
        // player was not, and text rasterized at the wrong size is exactly
        // the kind of defect that shows up only on a resized window.
        VideoPlayer(subbedFixture("subcanvas"), loop = true).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            // Announced right after asking for the track, which is the order
            // a consumer writes and the one that used to lose it: the
            // selection builds the pipeline on the decode thread, so the
            // announcement arrives before there is anything to receive it.
            player.selectSubtitleTrack(player.subtitleTracks.single().id)
            player.setSubtitleCanvasSize(800, 600)
            var canvas: Pair<Int, Int>? = null
            assertTrue(
                awaitTrue {
                    player.acquireSubtitles()?.let {
                        if (it.patches.isNotEmpty()) canvas = it.canvasWidth to it.canvasHeight
                    }
                    canvas == 800 to 600
                },
                "the overlay must rasterize at the announced size, got $canvas",
            )
        }
    }

    @Test
    fun `a subtitle selection queued before playback works`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        VideoPlayer(subbedFixture("subearly"), loop = true).use { player ->
            // Enqueued while the player may still be Opening; it must
            // apply once the decode thread reaches its command loop.
            player.selectSubtitleTrack(1)
            assertTrue(awaitTrue { player.activeSubtitleTrack == 1 }, "the early selection must land")
        }
    }

    @Test
    fun `an unknown subtitle id is a no-op`() {
        Fixtures.assumeDecodeEnvironment()
        VideoPlayer(subbedFixture("subbogus"), loop = true).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.selectSubtitleTrack(99)
            Thread.sleep(200)
            assertEquals(null, player.activeSubtitleTrack)
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback keeps running")
        }
    }

    @Test
    fun `external subtitles append, select and render`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        val srt = dir.resolve("external.srt")
        Files.writeString(srt, "1\n00:00:00,500 --> 00:00:04,000\nFrom outside\n")
        VideoPlayer(shortVideo("extsubs.mp4", "10"), loop = true).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            val added = player.addExternalSubtitles(srt)
            assertEquals(1, added.size, "the srt must probe as one track")
            assertEquals(-1, added[0].id, "externals count down from -1")
            assertEquals(srt, added[0].externalPath)
            assertTrue(player.subtitleTracks.any { it.id == -1 }, "the track joins the list")

            player.selectSubtitleTrack(-1)
            assertTrue(awaitTrue { player.activeSubtitleTrack == -1 }, "the external selection must land")
            var seen = false
            assertTrue(
                awaitTrue {
                    player.acquireSubtitles()?.let { seen = seen || it.patches.isNotEmpty() }
                    seen
                },
                "the external cue must render on the video timeline",
            )

            val more = player.addExternalSubtitles(srt)
            assertEquals(-2, more[0].id, "ids stay unique across adds")
        }
    }

    @Test
    fun `a garbage external file is refused without a trace`() {
        Fixtures.assumeDecodeEnvironment()
        val junk = dir.resolve("junk.srt")
        Files.write(junk, ByteArray(512) { (it * 7).toByte() })
        VideoPlayer(shortVideo("extjunk.mp4", "5"), loop = true).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            assertEquals(emptyList(), player.addExternalSubtitles(junk))
            assertEquals(emptyList(), player.addExternalSubtitles(dir.resolve("absent.srt")))
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback keeps running")
        }
    }

    @Test
    fun `live switching between embedded and external tracks`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        val srt = dir.resolve("switchext.srt")
        Files.writeString(srt, "1\n00:00:00,500 --> 00:00:09,000\nOutside line\n")
        VideoPlayer(subbedFixture("subswitch"), loop = true).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.selectSubtitleTrack(1)
            assertTrue(awaitTrue { player.activeSubtitleTrack == 1 }, "embedded first")
            player.addExternalSubtitles(srt)
            player.selectSubtitleTrack(-1)
            assertTrue(awaitTrue { player.activeSubtitleTrack == -1 }, "external second")
            player.selectSubtitleTrack(1)
            assertTrue(awaitTrue { player.activeSubtitleTrack == 1 }, "and back")
        }
    }

    @Test
    fun `rotation metadata surfaces on the player`() {
        Fixtures.assumeDecodeEnvironment()
        val plain = shortVideo("upright.mp4", "1")
        val rotated = Fixtures.generate(
            dir.resolve("sideways.mp4"),
            "-display_rotation", "90", "-i", plain.toString(), "-c", "copy",
        )
        VideoPlayer(rotated, loop = false).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            assertEquals(270, player.rotationDegrees, "90ccw metadata displays as 270cw")
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

    @Test
    fun `a looping source that yields nothing ends instead of turning forever`() {
        // Measured before this was guarded: a full core, indefinitely, with
        // the state reporting Playing. Each turn is a seek, and for a source
        // whose demuxer cannot seek it is a reopen from disk.
        val source = ScriptedFrameSource(frameCount = 0)
        VideoPlayer(Path.of("scripted"), true, false, null, null, 1, null) { source }.use { player ->
            assertTrue(
                awaitTrue(3_000) { player.state is VideoPlayer.State.Ended },
                "a lap with no frames must end, state was ${player.state}",
            )
            val turns = source.seekCount.get()
            Thread.sleep(200)
            assertEquals(turns, source.seekCount.get(), "nothing may keep turning the lap after it ended")
        }
    }

    @Test
    fun `a text format outside the old whitelist still draws`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        // sami, and equally microdvd, TTML or plain text: every text codec
        // the enumeration in this file did not name arrived flagged as
        // bitmap, and the bitmap branch skips the ASS rects their decoders
        // emit. The track selected, reported itself active, ran a thread and
        // drew nothing, with no error anywhere.
        val smi = dir.resolve("external.smi")
        Files.writeString(
            smi,
            "<SAMI><BODY>" +
                "<SYNC Start=200><P>Hello from sami</P>" +
                "<SYNC Start=8000><P>&nbsp;</P>" +
                "</BODY></SAMI>\n",
        )
        VideoPlayer(shortVideo("smi.mp4", "10"), loop = true).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            val added = player.addExternalSubtitles(smi)
            assertTrue(added.isNotEmpty(), "the file must probe as a subtitle track")
            player.selectSubtitleTrack(added.first().id)
            assertTrue(awaitTrue { player.activeSubtitleTrack == added.first().id }, "the selection must land")
            var patched = false
            assertTrue(
                awaitTrue {
                    player.acquireSubtitles()?.let { patched = patched || it.patches.isNotEmpty() }
                    patched
                },
                "a text codec outside the old list must still reach the overlay",
            )
        }
    }

    @Test
    fun `a close during the open is honoured rather than raced past`() {
        // Deliberately slow: the scenario IS close() exhausting its five
        // second join and returning while the open is still running. Shorter
        // than that and the thread finishes on its own, both builds settle
        // Closed, and the test proves nothing -- which is what the first
        // version of it did.
        val opening = CountDownLatch(1)
        val source = ScriptedFrameSource(frameCount = 50)
        val player = VideoPlayer(Path.of("scripted"), true, false, null, null, 1, null) {
            opening.await(20, TimeUnit.SECONDS)
            source
        }
        assertTrue(awaitTrue(2_000) { player.state is VideoPlayer.State.Opening }, "must start out opening")

        val closeReturned = AtomicLong(0)
        val closer = thread {
            player.close()
            closeReturned.set(System.nanoTime())
        }
        assertTrue(awaitTrue(8_000) { closeReturned.get() != 0L }, "close must return on its own timeout")

        // From here the caller has been told the player is gone. Nothing it
        // does afterwards may contradict that.
        opening.countDown()
        var sawPlaying = false
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (player.state is VideoPlayer.State.Playing) sawPlaying = true
            Thread.sleep(2)
        }
        closer.join(5_000)
        assertFalse(sawPlaying, "a player must not announce itself Playing after close() returned")
        assertTrue(player.state is VideoPlayer.State.Closed, "and must settle Closed, saw ${player.state}")
    }

    /**
     * The pacer reads the clock, and with an explicit clock that is the
     * caller's own code -- so the pacer can throw like any other thread here.
     * It was the only one that did not catch, and its death was the quietest
     * failure the player had: the decode thread's wait for a free cell ends
     * only when the queue closes, and only the decode thread closes it, so
     * the producer parked on a consumer that was already gone. close() then
     * spent its whole budget joining a thread that was never coming back and
     * returned having freed nothing -- decoder, native session and device all
     * still open, and with sound, still playing.
     *
     * The seek is what reaches the park: normal fill checks for room before
     * it decodes, while a seek commits a preview and a landing back to back
     * and at depth 1 the landing must wait out the preview's pop.
     */
    @Test
    fun `a pacer that dies fails the player instead of parking the decode thread`() {
        val inner = PlaybackClock()
        val clock = object : MediaClock {
            override val isPaused: Boolean get() = inner.isPaused
            override fun start(atMediaNanos: Long) = inner.start(atMediaNanos)
            override fun pause() = inner.pause()
            override fun resume() = inner.resume()
            override fun seek(mediaNanos: Long) = inner.seek(mediaNanos)
            override fun mediaNanos(): Long {
                // Only the pacer's reading throws. The decode thread reads the
                // same clock to judge lateness, and a throw there is a
                // different failure with its own path.
                if (Thread.currentThread().name == "skinema-pace") error("the clock is gone")
                return inner.mediaNanos()
            }
        }
        val source = ScriptedFrameSource(frameCount = 500)
        val player = VideoPlayer(Path.of("scripted"), true, false, clock, null, 1, null) { source }
        try {
            assertTrue(
                awaitTrue(5_000) { player.state is VideoPlayer.State.Failed },
                "a dead pacer must reach the caller as Failed, saw ${player.state}",
            )
            // A caller does press on after the picture stops. A Failed
            // player refuses the commands that could park its decode thread,
            // so this is the press that used to reach the park and now does
            // not -- and close() below is where that shows.
            player.seek(1_000_000_000L)
            val startedAt = System.nanoTime()
            player.close()
            val tookMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue(tookMs < 3_000, "close() must not spend its join budget on a parked thread, took ${tookMs}ms")
            assertTrue(source.closed.get(), "close() must actually tear the decoder down")
        } finally {
            player.close()
        }
    }
}
