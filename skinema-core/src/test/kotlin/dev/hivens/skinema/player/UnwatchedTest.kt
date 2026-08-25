package dev.hivens.skinema.player

import dev.hivens.skinema.core.AudioClock
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
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
        Path.of("scripted"), false, false, clock, null, readAheadFrames, null, unwatched,
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
     * exactly what this mechanism exists to save -- so the return is a seek.
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
                "the gap was decoded rather than seeked past: ${source.decodeCount.get()} decodes",
            )
        }
    }

    /**
     * The consumer that says nothing, which is the ordinary one: a surface
     * polls while its window is on screen and simply stops when it is not.
     * The reading itself is the only signal there is.
     */
    @Test
    fun `a mailbox that stops being read is noticed on its own`() {
        val source = ScriptedFrameSource(frameCount = 600)
        player(source, WhenUnwatched.Freeze).use { player ->
            assertTrue(awaitTrue { player.acquireFrame() != null }, "the mailbox must be read at least once")
            advance(500)

            // Nothing is read from here on, and nothing says so either.
            assertTrue(
                awaitTrue(6_000) { player.state is VideoPlayer.State.Paused },
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
