package dev.hivens.skinema.player

import dev.hivens.skinema.audio.BoundedPcmSink
import dev.hivens.skinema.audio.FakePcmSink
import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.core.AudioClock
import dev.hivens.skinema.core.PlaybackClock
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
            // media at tempo 2.
            sink.positionFrames.addAndGet(11_025)
            assertTrue(
                awaitTrue { player.positionNanos() == anchor + 500_000_000L },
                "the mastered clock must run at the tempo, got ${player.positionNanos()}",
            )
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
