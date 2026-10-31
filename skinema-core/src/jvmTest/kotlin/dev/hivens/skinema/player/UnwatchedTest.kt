package dev.hivens.skinema.player

import dev.hivens.skinema.audio.BoundedPcmSink
import dev.hivens.skinema.audio.ChannelPreference
import dev.hivens.skinema.audio.FakePcmSink
import dev.hivens.skinema.core.AudioClock
import dev.hivens.skinema.libav.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A player nobody is taking frames from.
 *
 * It used to run at full tilt: the decode thread watches only the state and
 * the room in the queue, the pacer publishes into a mailbox nothing empties,
 * and neither has any way to know. A launcher minimised to the tray paid for
 * a picture no one could see, and the surface's own documentation said the
 * cost was already gone.
 *
 * Both halves are held here. The consumer that knows says so and is obeyed;
 * the one that says nothing is noticed anyway, by the only signal there is --
 * a mailbox that was being read and stopped.
 *
 * Time is hand-driven throughout (a scripted source over a clock this test
 * turns), so nothing here waits on a machine being fast enough.
 */
class UnwatchedTest {

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

    private fun player(
        source: ScriptedFrameSource,
        unwatched: WhenUnwatched,
        // A deeper queue than the default where the point is that an
        // unwatched player stops with inventory in hand rather than at the
        // one-cell boundary, which is the shape a real consumer's read-ahead
        // has. It does not separate the two gates -- nothing can: the pacer
        // holding its inventory stops the fill side by itself within a queue's
        // depth, so what the fill side's own gate saves is bounded and once.
        readAheadFrames: Int = 1,
    ) = VideoPlayer(
        Path.of("scripted"), false, false, clock, null, readAheadFrames, null, unwatched, false, 1f, ChannelPreference.Source,
    ) { source }

    /** Media time forward by [millis], the way a device consuming would. */
    private fun advance(millis: Long) {
        frames.addAndGet(48_000L * millis / 1_000)
    }

    /**
     * The explicit switch, and the policy a background wants: the timeline
     * stops with the picture and carries on from there. What proves the
     * decoding stopped is the source's own count -- the state alone would
     * pass against a player that merely reported itself paused while its
     * decode thread ran on.
     */
    @Test
    fun `Freeze stops the decoding and the timeline, and carries on from where it stopped`() {
        val source = ScriptedFrameSource(frameCount = 600)
        player(source, WhenUnwatched.Freeze).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            advance(500)
            assertTrue(awaitTrue { source.maxStartedIndex.get() >= 3 }, "decode must be running first")

            player.setPresenting(false)
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Paused }, "Freeze parks the player")
            val stoppedAt = source.maxStartedIndex.get()
            val position = player.positionNanos()

            // A whole second of timeline nobody asked for. Neither the picture
            // nor the clock may take it.
            advance(1_000)
            Thread.sleep(200)
            assertTrue(
                source.maxStartedIndex.get() <= stoppedAt + 1,
                "decode ran on while nobody watched: $stoppedAt -> ${source.maxStartedIndex.get()}",
            )
            assertTrue(
                player.positionNanos() <= position + 50_000_000L,
                "the timeline ran on: ${position / 1_000_000}ms -> ${player.positionNanos() / 1_000_000}ms",
            )

            player.setPresenting(true)
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Playing }, "the picture is wanted again")
            advance(500)
            assertTrue(
                awaitTrue { source.maxStartedIndex.get() > stoppedAt + 1 },
                "decode must pick up again, stuck at ${source.maxStartedIndex.get()}",
            )
        }
    }

    /**
     * The other policy: a live source runs on without its viewer, so what
     * comes back is the current picture rather than a replay of the gap. The
     * decoder must not walk there -- decoding the gap to catch up spends
     * exactly what this mechanism exists to save -- so the decoder jumps.
     *
     * The clock must not. The return used to be an inexact seek, which moved
     * the timeline back to the keyframe the picture landed on, and with it the
     * sound of anyone who had gone on listening. The clock is hand-driven
     * here and stands still through the return, so any move is the player's.
     */
    @Test
    fun `KeepTime runs the timeline on and rejoins the picture where it got to`() {
        val source = ScriptedFrameSource(frameCount = 600, keyframeEvery = 5)
        player(source, WhenUnwatched.KeepTime, readAheadFrames = 8).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            advance(300)
            assertTrue(awaitTrue { source.maxStartedIndex.get() >= 2 }, "decode must be running first")

            player.setPresenting(false)
            Thread.sleep(100)
            val stoppedAt = source.maxStartedIndex.get()

            // Twenty seconds of file nobody watched, at a tenth of a second a
            // frame: two hundred frames the decoder must NOT walk through.
            advance(20_000)
            Thread.sleep(200)
            assertTrue(
                source.maxStartedIndex.get() <= stoppedAt + 1,
                "decode chased the clock while nobody watched: $stoppedAt -> ${source.maxStartedIndex.get()}",
            )
            assertIs<VideoPlayer.State.Playing>(player.state, "KeepTime does not park the player")
            assertTrue(
                player.positionNanos() > 15_000_000_000L,
                "the timeline must run on, at ${player.positionNanos() / 1_000_000}ms",
            )

            // Between two keyframes, so a return that re-anchored on the one
            // it landed on would show up as the clock stepping back 300 ms.
            val before = player.positionNanos()
            assertTrue(before % 500_000_000L != 0L, "the clock must stand between keyframes, at ${before}ns")
            player.setPresenting(true)
            var landed = -1L
            assertTrue(
                awaitTrue(5_000) {
                    player.acquireFrame()?.let { landed = it.ptsNanos }
                    landed > 15_000_000_000L
                },
                "the picture must rejoin the clock, landed at ${landed / 1_000_000}ms",
            )
            assertTrue(
                source.decodeCount.get() < 100,
                "the gap was decoded rather than jumped: ${source.decodeCount.get()} decodes",
            )
            // Caught up to the clock, not merely somewhere past the gap.
            assertTrue(
                awaitTrue(5_000) {
                    player.acquireFrame()?.let { landed = it.ptsNanos }
                    landed >= before - 100_000_000L
                },
                "the picture must catch up with the clock at ${before / 1_000_000}ms, reached ${landed / 1_000_000}ms",
            )
            // Not equal: the clock fills the gaps between device readings with
            // wall time, capped at a few tens of milliseconds, so it creeps
            // forward. A return that re-anchored on the keyframe would read
            // 300 ms back.
            assertTrue(
                player.positionNanos() >= before - 1_000_000L,
                "the return moved the clock back: ${before / 1_000_000}ms -> ${player.positionNanos() / 1_000_000}ms",
            )
        }
    }

    /**
     * A short absence on a file with keyframes far apart. Jumping to the
     * keyframe before the clock would go back five seconds here and decode
     * them all again, where the decoder is half a second behind: it carries
     * on from where it stood, and the picture never steps back.
     */
    @Test
    fun `a short absence decodes forward instead of jumping back to a keyframe`() {
        val source = ScriptedFrameSource(frameCount = 600, keyframeEvery = 50)
        player(source, WhenUnwatched.KeepTime).use { player ->
            // After the start, which anchors the clock where the device stands.
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            advance(5_300)
            var shown = -1L
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let { shown = it.ptsNanos }
                    shown >= 5_200_000_000L
                },
                "playback must reach 5.2s first, at ${shown / 1_000_000}ms",
            )
            player.setPresenting(false)
            Thread.sleep(100)
            val seeks = source.seekCount.get()

            advance(500)
            player.setPresenting(true)
            var latest = shown
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let {
                        assertTrue(it.ptsNanos >= shown, "the picture stepped back to ${it.ptsNanos / 1_000_000}ms")
                        latest = it.ptsNanos
                    }
                    latest >= 5_700_000_000L
                },
                "the picture must reach the clock, at ${latest / 1_000_000}ms",
            )
            assertEquals(seeks, source.seekCount.get(), "a half-second gap is not worth a jump")
        }
    }

    /**
     * A long absence does jump, and the keyframe it lands on can still be
     * behind the picture on screen: ten seconds apart here, the clock three
     * seconds on. Whatever decodes at or before the frame already shown is
     * dropped unseen, so the viewer sees the picture move forward only.
     */
    @Test
    fun `a jump never shows a frame older than the one on screen`() {
        val source = ScriptedFrameSource(frameCount = 600, keyframeEvery = 100)
        player(source, WhenUnwatched.KeepTime).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            advance(5_000)
            var shown = -1L
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let { shown = it.ptsNanos }
                    shown >= 4_900_000_000L
                },
                "playback must reach 4.9s first, at ${shown / 1_000_000}ms",
            )
            player.setPresenting(false)
            // Longer than the catch-up's publish interval, so the first frame
            // after the jump would be published as a guard if nothing held it
            // back, which is the thing under test.
            Thread.sleep(300)
            val seeks = source.seekCount.get()

            advance(3_000)
            // The decode after the keyframe is held, so the keyframe itself,
            // were it shown, would stay in the mailbox long enough to be read:
            // the scripted source decodes so fast that anything published is
            // otherwise overwritten before a poll can see it.
            val held = source.blockAt(1)
            try {
                player.setPresenting(true)
                assertTrue(awaitTrue { source.seekCount.get() > seeks }, "three seconds is worth a jump")
                repeat(30) {
                    player.acquireFrame()?.let {
                        assertTrue(it.ptsNanos > shown, "the picture stepped back to ${it.ptsNanos / 1_000_000}ms")
                    }
                    Thread.sleep(10)
                }
            } finally {
                held.countDown()
            }
            var latest = shown
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let {
                        assertTrue(it.ptsNanos > shown, "the picture stepped back to ${it.ptsNanos / 1_000_000}ms")
                        latest = it.ptsNanos
                    }
                    latest >= 7_900_000_000L
                },
                "the picture must reach the clock, at ${latest / 1_000_000}ms",
            )
        }
    }

    /**
     * Paused while nobody watched: the clock ran on and then stopped, and the
     * picture has to be at the playhead when the window comes back, not at
     * the frame it had when it was hidden.
     */
    @Test
    fun `a player paused while nobody watched shows the playhead when it comes back`() {
        val source = ScriptedFrameSource(frameCount = 600, keyframeEvery = 10)
        player(source, WhenUnwatched.KeepTime).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.setPresenting(false)
            Thread.sleep(100)
            advance(5_000)
            player.pause()
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Paused }, "state=${player.state}")
            val playhead = player.positionNanos()

            player.setPresenting(true)
            var shown = -1L
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let { shown = it.ptsNanos }
                    shown >= playhead - 100_000_000L
                },
                "the picture must land at the playhead ${playhead / 1_000_000}ms, shows ${shown / 1_000_000}ms",
            )
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Paused }, "it stays paused, state=${player.state}")
        }
    }

    /**
     * The lap a player turns while nobody watches.
     *
     * The fill loop turns a lap when the decoder runs out, and a decoder
     * nobody reads stands still, so a hidden player whose time ran on went
     * past the end of its file and kept going, state Playing, with nothing
     * to show for it when the window came back.
     */
    @Test
    fun `a lap still turns while nobody watches`() {
        val source = ScriptedFrameSource(frameCount = 20, declaredDurationNanos = 2_000_000_000L)
        VideoPlayer(
            Path.of("scripted"), true, false, clock, null, 1, null, WhenUnwatched.KeepTime, false, 1f, ChannelPreference.Source,
        ) { source }.use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.setPresenting(false)
            Thread.sleep(100)
            val seeksBefore = source.seekCount.get()

            advance(2_500)
            // The clock is what turning the lap moves last, so it is what is
            // waited on: the decoder's seek comes first and says nothing yet.
            assertTrue(
                awaitTrue { player.positionNanos() < 2_000_000_000L },
                "the clock must be back inside the file, at ${player.positionNanos() / 1_000_000}ms",
            )
            assertTrue(source.seekCount.get() > seeksBefore, "the decoder must have been turned with it")
            assertIs<VideoPlayer.State.Playing>(player.state)
        }
    }

    /** And one that does not loop ends there instead. */
    @Test
    fun `a file that does not loop ends while nobody watches`() {
        val source = ScriptedFrameSource(frameCount = 20, declaredDurationNanos = 2_000_000_000L)
        player(source, WhenUnwatched.KeepTime).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.setPresenting(false)
            Thread.sleep(100)

            advance(2_500)
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Ended }, "state=${player.state}")
            assertTrue(
                abs(player.positionNanos() - 2_000_000_000L) <= 1_000_000L,
                "an ended file rests on its duration, at ${player.positionNanos()}ns",
            )
        }
    }

    /**
     * The default on a player with nothing to hear: nobody gains from it
     * running on, so it stops the way [WhenUnwatched.Freeze] does.
     */
    @Test
    fun `FollowSound freezes a player nobody can hear`() {
        val source = ScriptedFrameSource(frameCount = 600)
        player(source, WhenUnwatched.FollowSound).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.setPresenting(false)
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Paused }, "state=${player.state}")
            player.setPresenting(true)
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Playing }, "state=${player.state}")
        }
    }

    /**
     * What a surface reports. A report both ways is obeyed, and while it
     * stands the mailbox moves nothing: a surface hidden behind another window
     * still reads now and then, and each read would revive the player it had
     * just reported out of sight.
     */
    @Test
    fun `a visibility report is obeyed, and reads do not undo it`() {
        val source = ScriptedFrameSource(frameCount = 600)
        player(source, WhenUnwatched.Freeze).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")

            player.reportVisible(false)
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Paused }, "state=${player.state}")
            repeat(20) {
                player.acquireFrame()
                Thread.sleep(10)
            }
            assertIs<VideoPlayer.State.Paused>(player.state, "a read revived a player reported out of sight")

            player.reportVisible(true)
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Playing }, "state=${player.state}")
        }
    }

    /** The application's word outranks the surface's, in both directions. */
    @Test
    fun `setPresenting outranks a visibility report`() {
        val source = ScriptedFrameSource(frameCount = 600)
        player(source, WhenUnwatched.Freeze).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.setPresenting(true)
            player.reportVisible(false)
            Thread.sleep(300)
            assertIs<VideoPlayer.State.Playing>(player.state, "a report overrode what the application said")

            player.setPresenting(false)
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Paused }, "state=${player.state}")
            player.reportVisible(true)
            Thread.sleep(300)
            assertIs<VideoPlayer.State.Paused>(player.state, "a report overrode what the application said")
        }
    }

    /**
     * A surface that leaves withdraws its report, and the mailbox takes the
     * question back. Otherwise a player whose surface was removed would have
     * decoded on for good behind the last report it was given.
     */
    @Test
    fun `a withdrawn report hands the question back to the mailbox`() {
        val source = ScriptedFrameSource(frameCount = 600)
        player(source, WhenUnwatched.Freeze).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.reportVisible(true)
            player.reportVisible(null)
            assertTrue(
                awaitTrue(15_000) {
                    advance(200)
                    player.state is VideoPlayer.State.Paused
                },
                "an unread mailbox must be noticed once the report is withdrawn, state=${player.state}",
            )
            player.acquireFrame()
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Playing }, "a read revives it again")
        }
    }

    /**
     * The consumer that says nothing, which is the ordinary one: a surface
     * reads while its window draws and simply stops when it does not. The
     * reading itself is the only signal there is.
     */
    @Test
    fun `a mailbox that stops being read is noticed on its own`() {
        val source = ScriptedFrameSource(frameCount = 600)
        player(source, WhenUnwatched.Freeze).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "the mailbox must be read at least once")

            // Nothing is read from here on, and nothing says so either. The
            // clock is turned far enough that the pictures nobody is taking
            // pile up past the count -- what is being asserted is the notice,
            // and the notice is about frames thrown away rather than time.
            assertTrue(
                awaitTrue(15_000) {
                    advance(200)
                    player.state is VideoPlayer.State.Paused
                },
                "an unread mailbox must be noticed, state=${player.state}",
            )
            val stoppedAt = source.maxStartedIndex.get()
            advance(1_000)
            Thread.sleep(200)
            assertTrue(
                source.maxStartedIndex.get() <= stoppedAt + 1,
                "decode ran on behind the notice: $stoppedAt -> ${source.maxStartedIndex.get()}",
            )

            // And reading it again is all it takes to come back.
            player.acquireFrame()
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Playing }, "a read revives it")
            advance(500)
            assertTrue(
                awaitTrue { source.maxStartedIndex.get() > stoppedAt + 1 },
                "decode must pick up again, stuck at ${source.maxStartedIndex.get()}",
            )
        }
    }

    /**
     * And the case the notice must NOT fire on. A player whose mailbox has
     * never been read is not one that stopped being watched: it may be
     * feeding something that is not a screen at all, and every test in this
     * suite that drives a player without taking a frame is that consumer.
     */
    @Test
    fun `a player nobody has ever read from is left alone`() {
        val source = ScriptedFrameSource(frameCount = 600)
        player(source, WhenUnwatched.Freeze).use { player ->
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Playing }, "playback must start")
            repeat(6) {
                advance(500)
                Thread.sleep(500)
            }
            assertIs<VideoPlayer.State.Playing>(player.state, "a player nobody ever read must keep running")
            assertTrue(
                source.maxStartedIndex.get() > 3,
                "it must still be decoding, at ${source.maxStartedIndex.get()}",
            )
        }
    }
}

/**
 * The default policy on a player somebody can hear, which needs the real
 * audio side: sound decoded from a file into a sink whose played position
 * the test turns by hand, under a scripted picture.
 */
class UnwatchedSoundTest {

    private val dir: Path = Files.createTempDirectory("skinema-unwatched-sound")

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

    private fun tone(): Path = Fixtures.generate(
        dir.resolve("tone.flac"),
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "30", "-c:a", "flac",
    )

    private fun player(sink: FakePcmSink, source: ScriptedFrameSource, volume: Float = 1f) = VideoPlayer(
        tone(), false, true, null, sink, 1, null, WhenUnwatched.FollowSound, false, volume, ChannelPreference.Source,
    ) { source }

    /**
     * What a browser does with a tab playing music behind another: the sound
     * goes on, the picture stops, and when the tab comes back the picture
     * catches up with the sound rather than the sound going back for the
     * picture.
     *
     * The sink's flush count is the sound's side of that. Every reposition of
     * the audio side flushes the line, and the return used to be one: an
     * inexact seek that cropped the sound back to the picture's keyframe.
     */
    @Test
    fun `FollowSound keeps the sound of a hidden player and catches the picture up on return`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        sink.positionFrames.set(0)
        val source = ScriptedFrameSource(frameCount = 300, keyframeEvery = 20)
        player(sink, source).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            sink.positionFrames.set(48_000L / 2)
            assertTrue(awaitTrue { source.maxStartedIndex.get() >= 4 }, "decode must be running first")

            player.setPresenting(false)
            Thread.sleep(200)
            assertIs<VideoPlayer.State.Playing>(player.state, "a player somebody can hear must not pause")
            val stoppedAt = source.maxStartedIndex.get()
            val flushes = sink.flushes

            // Eight and a half seconds nobody watched and somebody heard.
            sink.positionFrames.set(48_000L * 9)
            assertTrue(
                awaitTrue { player.positionNanos() >= 8_900_000_000L },
                "the sound must carry the clock on, at ${player.positionNanos() / 1_000_000}ms",
            )
            Thread.sleep(200)
            assertTrue(
                source.maxStartedIndex.get() <= stoppedAt + 1,
                "the picture decoded for nobody: $stoppedAt -> ${source.maxStartedIndex.get()}",
            )

            player.setPresenting(true)
            var shown = -1L
            assertTrue(
                awaitTrue {
                    player.acquireFrame()?.let { shown = it.ptsNanos }
                    shown >= 8_900_000_000L
                },
                "the picture must catch up with the sound, reached ${shown / 1_000_000}ms",
            )
            assertEquals(flushes, sink.flushes, "the return moved the sound")
            assertTrue(
                player.positionNanos() >= 8_900_000_000L,
                "the clock went back for the picture, at ${player.positionNanos() / 1_000_000}ms",
            )
        }
    }

    /**
     * A seek made while nobody watched, and a return before the sound has
     * performed its half. The picture landed at the target at once, the clock
     * still reads where it stood before the seek, and a return that trusted
     * the clock jumped the picture back there: ten seconds away from the
     * sound that was about to start at the target.
     *
     * The sink parks the audio thread in a write, which is what leaves its
     * half of the seek queued, the same lever the phantom-chase test uses.
     */
    @Test
    fun `a return while the sound still owes a seek rejoins at the seek, not at the stale clock`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = BoundedPcmSink(capacityFrames = 12_000)
        val source = ScriptedFrameSource(frameCount = 300)
        VideoPlayer(
            tone(), false, true, null, sink, 1, null, WhenUnwatched.FollowSound, false, 1f, ChannelPreference.Source,
        ) { source }.use { player ->
            try {
                assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
                assertTrue(
                    awaitTrue {
                        sink.consumeAllButTail(0)
                        player.acquireFrame()
                        sink.framePosition() >= 48_000L * 15
                    },
                    "the played position must reach 15s",
                )
                player.setPresenting(false)
                assertTrue(awaitTrue { sink.writerParked }, "the audio thread must park in write")
                assertIs<VideoPlayer.State.Playing>(player.state, "a player somebody can hear runs on")

                player.seek(5_000_000_000L)
                assertTrue(
                    awaitTrue { player.acquireFrame()?.ptsNanos == 5_000_000_000L },
                    "the landing publishes while nobody watches",
                )
                player.setPresenting(true)
                var furthest = 0L
                repeat(40) {
                    player.acquireFrame()?.let { furthest = maxOf(furthest, it.ptsNanos) }
                    Thread.sleep(10)
                }
                assertTrue(
                    furthest < 8_000_000_000L,
                    "the picture went back to where the clock stood before the seek: ${furthest / 1_000_000}ms",
                )
            } finally {
                sink.release()
            }
        }
    }

    /** Muted is silent: nobody is listening to a player at volume zero. */
    @Test
    fun `FollowSound freezes a muted player`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        sink.positionFrames.set(0)
        val source = ScriptedFrameSource(frameCount = 300)
        player(sink, source, volume = 0f).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
            player.setPresenting(false)
            assertTrue(awaitTrue { player.state is VideoPlayer.State.Paused }, "state=${player.state}")
        }
    }
}
