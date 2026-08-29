package dev.hivens.skinema.player

import dev.hivens.skinema.audio.BoundedPcmSink
import dev.hivens.skinema.audio.FakePcmSink
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
 * The two seek modes: exact landings preview their keyframe while the
 * decode-forward run is still working, inexact landings take the
 * keyframe as the destination -- picture, sound and the relative-seek
 * base all anchored to where the stream actually starts.
 */
class SeekModesTest {

    private val frames = AtomicLong(0)
    private val clock = AudioClock(48_000) { frames.get() }

    private fun awaitTrue(deadlineMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun player(source: ScriptedFrameSource, readAheadFrames: Int = 1) = VideoPlayer(
        Path.of("scripted"), false, false, clock, null, readAheadFrames, null, WhenUnwatched.Freeze, false, 1f,
    ) { source }

    /**
     * A backward jump in media time must flush the queued tail at once.
     *
     * Frames decoded ahead of a jump stand in the future the instant it
     * happens, so a pacer that only ever waits would hold them until the
     * timeline walked back up to their timestamps -- a whole lap later, or
     * never. The jump is judged by direction rather than by size, because a
     * lap shorter than a second moves the clock back by less than a second.
     *
     * The loop no longer produces this -- the picture drains its queue before
     * it wraps -- but a track switch rebases the clock with frames already
     * queued, and that is the same jump. This covers the branch directly,
     * which the two end-to-end tests it replaces did through the old
     * audio-driven wrap.
     */
    @Test
    fun `a backward jump publishes the queued tail instead of holding it`() {
        // A read-ahead deep enough that frames stand queued beyond the clock:
        // with one, the pacer can have just published everything it had and
        // the jump would have no tail to strand -- which is what made this
        // pass or fail by timing rather than by behaviour.
        val source = ScriptedFrameSource(frameCount = 200)
        player(source, readAheadFrames = 8).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            // Walk media time out to 2s so the pacer publishes toward it and
            // keeps a tail of decoded frames beyond.
            frames.set(48_000 * 2)
            var seen = -1L
            assertTrue(
                awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen >= 1_900_000_000L },
                "the pacer must follow the clock out to 2s, saw ${seen}ns",
            )
            // The precondition this test used to assume: something decoded
            // past the clock, so the jump has a tail to strand.
            assertTrue(
                awaitTrue { source.maxStartedIndex.get() > 21 },
                "a tail must stand beyond the clock, decoder reached ${source.maxStartedIndex.get()}",
            )
            // The jump. Everything queued now stands two seconds in the future.
            clock.seek(0)
            assertTrue(
                awaitTrue(deadlineMs = 5_000) { p.acquireFrame()?.let { seen = it.ptsNanos }; seen > 2_000_000_000L },
                "the stranded tail must present at the jump, saw ${seen}ns",
            )
        }
    }

    /**
     * close() must land whatever else is queued in front of it.
     *
     * A seek's decode-forward run watches only the head of the command queue,
     * and anything it does not act on -- a pause, a rate change, a subtitle
     * selection -- stood in front of the Close and hid it. The run then played
     * itself out to the end before the player noticed it had been closed: on a
     * real file that is seconds of decoding after close() was called, and past
     * the join timeout close() returns while the thread and its native session
     * are still running.
     */
    @Test
    fun `close is honoured even when another command is queued in front of it`() {
        val source = ScriptedFrameSource(frameCount = 20_000, keyframeEvery = 20_000)
        val latch = source.blockAt(50)
        val p = player(source)
        assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
        p.seek(19_000 * 100_000_000L, exact = true)
        assertTrue(awaitTrue { source.maxStartedIndex.get() >= 50 }, "the landing run must be under way")

        p.pause()
        val closer = Thread { p.close() }
        closer.start()
        assertTrue(awaitTrue { closer.state == Thread.State.WAITING || closer.state == Thread.State.TIMED_WAITING })
        val decodedBeforeRelease = source.decodeCount.get()

        latch.countDown()
        closer.join(10_000)
        assertTrue(!closer.isAlive, "close must return")
        // Without the fix the run carries on to frame 19000 before the Close
        // at the back of the queue is ever looked at.
        val after = source.decodeCount.get() - decodedBeforeRelease
        assertTrue(after < 1_000, "the run must stop at the Close, decoded $after more frames")
    }

    @Test
    fun `an exact seek previews the keyframe while the run lands`() {
        val source = ScriptedFrameSource(frameCount = 60)
        // Block the frame after the keyframe: the preview must reach the
        // screen while the exact landing is still decoding.
        val latch = source.blockAt(3)
        player(source).use { p ->
            try {
                assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
                while (p.acquireFrame() != null) { /* drain */ }

                p.seek(250_000_000L)
                var seen = -1L
                assertTrue(
                    awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == 200_000_000L },
                    "the keyframe must preview during the landing, saw ${seen}ns",
                )
                assertIs<VideoPlayer.State.Seeking>(p.state, "the landing is still running behind the preview")

                latch.countDown()
                assertTrue(
                    awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == 300_000_000L },
                    "the exact landing must follow the preview, saw ${seen}ns",
                )
            } finally {
                latch.countDown()
            }
        }
    }

    @Test
    fun `an inexact seek lands on the keyframe at once`() {
        val source = ScriptedFrameSource(frameCount = 60, keyframeEvery = 10)
        player(source).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            while (p.acquireFrame() != null) { /* drain */ }

            p.seek(1_500_000_000L, exact = false)
            var seen = -1L
            assertTrue(
                awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == 1_000_000_000L },
                "the keyframe at-or-before the target is the landing, saw ${seen}ns",
            )
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Playing }, "the landing resolves the state")
        }
    }

    @Test
    fun `repeated backsteps reuse the discovered run`() {
        val source = ScriptedFrameSource(frameCount = 100, keyframeEvery = 50)
        player(source).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            p.seek(3_000_000_000L)
            assertTrue(awaitTrue { p.acquireFrame()?.ptsNanos == 3_000_000_000L }, "the seek must land")
            p.pause()
            awaitTrue { p.state is VideoPlayer.State.Paused }

            val before = source.decodeCount.get()
            p.stepBackward()
            assertTrue(awaitTrue { p.acquireFrame()?.ptsNanos == 2_900_000_000L }, "the first backstep lands")
            val discovery = source.decodeCount.get() - before

            val mid = source.decodeCount.get()
            p.stepBackward()
            assertTrue(awaitTrue { p.acquireFrame()?.ptsNanos == 2_800_000_000L }, "the second backstep lands")
            val cachedCost = source.decodeCount.get() - mid

            // First step: discovery (31 decodes) + landing run (30);
            // second: the landing run alone (29) -- the memo answers.
            assertTrue(
                cachedCost < discovery / 2 + 2,
                "a cached backstep must skip discovery: first=$discovery decodes, second=$cachedCost",
            )
        }
    }

    @Test
    fun `a backstep lands without a keyframe preview`() {
        val source = ScriptedFrameSource(frameCount = 100, keyframeEvery = 50)
        player(source).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            p.seek(3_000_000_000L)
            assertTrue(awaitTrue { p.acquireFrame()?.ptsNanos == 3_000_000_000L }, "the seek must land")
            p.pause()
            awaitTrue { p.state is VideoPlayer.State.Paused }

            val before = source.convertCount.get()
            p.stepBackward()
            assertTrue(awaitTrue { p.acquireFrame()?.ptsNanos == 2_900_000_000L }, "the backstep lands")
            assertEquals(
                1,
                source.convertCount.get() - before,
                "the landing is the step's only conversion -- a keyframe preview would jump the picture back",
            )
        }
    }

    @Test
    fun `relative seeks base on the landed keyframe, not the request`() {
        val source = ScriptedFrameSource(frameCount = 100, keyframeEvery = 10)
        player(source).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")

            // Request 1.5s, land at the 1.0s keyframe.
            p.seek(1_500_000_000L, exact = false)
            assertTrue(awaitTrue { p.acquireFrame()?.ptsNanos == 1_000_000_000L }, "inexact landing")

            // +1s from what the user SEES (1.0s), not from the request:
            // basing on the request would land this exact seek at 2.5s.
            p.seekBy(1_000_000_000L)
            var seen = -1L
            assertTrue(
                awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen >= 1_900_000_000L },
                "the exact follow-up must land, saw ${seen}ns",
            )
            assertEquals(2_000_000_000L, seen, "the relative base is the landed position")
        }
    }
    /**
     * A wrap performed under a pause has nobody to decode the lap it turned
     * into: only the Playing arm of the decode loop fills the queue. The seek
     * had already cleared the inventory, so the wrap moved the timeline to
     * zero and left the picture on whatever the press jumped from, and the two
     * disagreed until something resumed.
     */
    @Test
    fun `a paused player seeked past the end of a looping file wraps with its picture`() {
        val source = ScriptedFrameSource(frameCount = 10)
        VideoPlayer(
            Path.of("scripted"), true, false, clock, null, 1, null, WhenUnwatched.Freeze, false, 1f,
        ) { source }.use { p ->
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Playing }, "state=${p.state}")
            // Far enough in that the wrap has somewhere to come back FROM: at
            // frame zero it would read the same either way.
            frames.set(48_000L * 500 / 1_000)
            var shown = -1L
            assertTrue(
                awaitTrue { p.acquireFrame()?.let { shown = it.ptsNanos }; shown > 0L },
                "the picture must get past its first frame, saw ${shown}ns",
            )
            p.pause()
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Paused }, "state=${p.state}")
            // Drain the mailbox, so the next frame out of it is the wrap's own
            // doing rather than something the pause left standing.
            val drainBy = System.currentTimeMillis() + 2_000
            while (p.acquireFrame() != null && System.currentTimeMillis() < drainBy) Thread.sleep(5)

            p.seek(100_000_000_000L)

            var wrapped = -1L
            assertTrue(
                awaitTrue { p.acquireFrame()?.let { wrapped = it.ptsNanos }; wrapped >= 0L },
                "the wrap must bring the new lap's first frame with it, state=${p.state}",
            )
            assertEquals(0L, wrapped, "the picture must be the start of the new lap")
            assertIs<VideoPlayer.State.Paused>(p.state)
        }
    }
}

/**
 * A backward seek whose audio half is still queued leaves the clock at
 * the OLD, higher position; lateness against it is fiction. The decode
 * side must hold instead of chasing the phantom forward -- the chase
 * burned the decoder seconds past the real position and froze the
 * picture until the clock walked there.
 */
class PhantomChaseTest {

    private val dir: Path = Files.createTempDirectory("skinema-phantom-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun awaitTrue(deadlineMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test
    fun `a backward seek does not chase the stale audio anchor`() {
        Fixtures.assumeDecodeEnvironment()
        // Real audio (the pipeline needs a Path), scripted video: a 30s
        // tone whose sink the test consumes by hand, against a 30s frame
        // grid whose decode is instant -- a phantom chase would rip
        // through it in microseconds.
        val tone = Fixtures.generate(
            dir.resolve("tone.flac"),
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "30", "-c:a", "flac",
        )
        val sink = BoundedPcmSink(capacityFrames = 11_025)
        val source = ScriptedFrameSource(frameCount = 300)
        val player = VideoPlayer(tone, false, true, null, sink, 1, null, WhenUnwatched.Freeze, false, 1f) { source }
        player.use { p ->
            try {
                assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
                // Hand-feed the DAC to 15s; video follows.
                assertTrue(
                    awaitTrue {
                        sink.consumeAllButTail(0)
                        p.acquireFrame()
                        sink.framePosition() >= 44_100L * 15
                    },
                    "the played position must reach 15s",
                )
                // Stop consuming: the audio thread fills the buffer and
                // parks inside write -- its half of the next seek will
                // wait, exactly the burst-backlog state.
                assertTrue(awaitTrue { sink.writerParked }, "the audio thread must park in write")

                p.seek(5_000_000_000L)
                assertTrue(
                    awaitTrue { p.acquireFrame()?.ptsNanos == 5_000_000_000L },
                    "the landing must publish against the stale clock",
                )
                // The clock still reads ~15s. A chasing fill would decode
                // the 5s..15s gap (instantly, here) before the anchor
                // lands; a holding fill decodes at most the inventory.
                val decodesAtLanding = source.decodeCount.get()
                Thread.sleep(300)
                val chased = source.decodeCount.get() - decodesAtLanding
                assertTrue(chased <= 5, "the fill must hold while the audio owes its anchor, decoded $chased frames")

                // Release the audio thread: the anchor lands at 5s and the
                // held inventory flows.
                var seen = -1L
                assertTrue(
                    awaitTrue {
                        sink.consumeAllButTail(0)
                        p.acquireFrame()?.let { seen = it.ptsNanos }
                        seen in 5_000_000_001L..8_000_000_000L
                    },
                    "frames past the landing must flow once the clock anchors, saw ${seen}ns",
                )
            } finally {
                sink.release()
            }
        }
    }
}

/** The audio half of an inexact landing needs the real pipeline. */
class InexactSeekAudioTest {

    private val dir: Path = Files.createTempDirectory("skinema-inexact-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun awaitTrue(deadlineMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test
    fun `an inexact landing re-anchors the sound to the keyframe`() {
        Fixtures.assumeDecodeEnvironment()
        // Keyframes every 500ms; the request falls between them. Sound
        // left at the request would play half a second ahead of the
        // picture for the rest of the stream.
        val av = Fixtures.generate(
            dir.resolve("inexact.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-map", "0:v", "-map", "1:a", "-t", "2",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-g", "5",
            "-c:a", "aac", "-shortest",
        )
        val sink = FakePcmSink()
        sink.positionFrames.set(0)
        VideoPlayer(av, loop = false, audio = true, sink = sink).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")

            p.seek(750_000_000L, exact = false)
            // The upfront audio freeze briefly anchors the clock at the
            // REQUEST; until the landing's corrective re-anchor wins the
            // race, a refill frame past the keyframe can flash through the
            // latest-wins mailbox. The picture assert therefore accepts
            // the landing or its transient successors -- the position
            // assert below is what discriminates the re-anchor bug.
            var seen = -1L
            assertTrue(
                awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen >= 500_000_000L },
                "the picture lands at-or-after the 500ms keyframe, saw ${seen}ns",
            )
            // The DAC stands still, so a correctly re-anchored clock reads
            // the landing (modulo sample-grid rounding); the bug reads the
            // 750ms request.
            assertTrue(
                awaitTrue { p.positionNanos() in 499_000_000L..510_000_000L },
                "sound must re-anchor to the landing, position=${p.positionNanos()}ns",
            )
        }
    }

}
