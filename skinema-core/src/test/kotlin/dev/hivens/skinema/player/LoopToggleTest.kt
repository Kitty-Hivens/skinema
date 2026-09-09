package dev.hivens.skinema.player

import dev.hivens.skinema.core.AudioClock
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Looping as a live switch rather than a construction-time constant.
 *
 * A consumer offering a repeat button had to build a second player to answer
 * it, and the file then started over from zero: the position the first player
 * stood at went with it. Everything here is proven over [ScriptedFrameSource]
 * and a hand-driven clock, so a lap only turns when this test moves time.
 */
class LoopToggleTest {

    private val frames = AtomicLong(0)
    private val clock = AudioClock(48_000) { frames.get() }

    /** DAC frames for a media time, at the 48 kHz test rate. */
    private fun framesFor(ms: Long): Long = ms * 48

    private fun awaitTrue(deadlineMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun player(source: ScriptedFrameSource, loop: Boolean) = VideoPlayer(
        Path.of("scripted"), loop, false, clock, null, 4, null, WhenUnwatched.Freeze, false, 1f,
    ) { source }

    /**
     * The four frames are decoded and queued at once, and none of them past the
     * first is due until this test moves the clock. So the end of the stream is
     * reached while the flag is still being written, and the decision itself
     * lands where the test puts it.
     */
    @Test
    fun `looping turned off during a lap ends the file at the end of that lap`() {
        val source = ScriptedFrameSource(frameCount = 4)
        player(source, loop = true).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            p.loop = false
            frames.set(framesFor(300))
            assertTrue(
                awaitTrue { p.state is VideoPlayer.State.Ended },
                "the lap must not turn once looping is off, state=${p.state}",
            )
            assertEquals(0, source.seekCount.get(), "and nothing may have repositioned the source")
        }
    }

    /** The reported case: the button goes on while the file is playing. */
    @Test
    fun `looping turned on during a lap wraps instead of ending`() {
        val source = ScriptedFrameSource(frameCount = 4)
        player(source, loop = false).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            p.loop = true
            frames.set(framesFor(300))
            assertTrue(awaitTrue { source.seekCount.get() >= 1 }, "the lap must come round")
            assertIs<VideoPlayer.State.Playing>(p.state, "and the player must not have ended")
        }
    }

    /**
     * The wait for the lap's own time to run out is seconds long on an ordinary
     * file, and the flag is read before it rather than after. A press landing
     * inside that window was answered with one more turn.
     *
     * The scenario is pinned rather than assumed: with the last frame on screen
     * and the stream drained, the only thing that holds a wrap back is that
     * wait, so a source that has not been repositioned is a decode thread
     * parked in it.
     */
    @Test
    fun `looping turned off while the lap tail plays out ends the file`() {
        val source = ScriptedFrameSource(frameCount = 4, declaredDurationNanos = 1_000_000_000L)
        player(source, loop = true).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            // Stepped rather than jumped, and the step IS the scenario. Three
            // frames coming due at once are published in a burst, and the lap's
            // own end is measured from the playhead the pacer writes after it
            // takes each frame off the queue. Landing the clock on the last
            // frame's pts while that write is still in flight lets the wait
            // finish against the frame before it, and the lap turns before this
            // test has seen the tail it is here to hold. Measured on macOS,
            // which is fast enough to lose that race.
            var seen = -1L
            for (ms in longArrayOf(100, 200, 300)) {
                frames.set(framesFor(ms))
                assertTrue(
                    awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == ms * 1_000_000L },
                    "the frame at ${ms}ms must present, saw ${seen}ns",
                )
            }
            // The lap's own time runs to 400 ms, the last pts plus the frame
            // period, and the clock stands at 300.
            Thread.sleep(200)
            assertEquals(0, source.seekCount.get(), "the wrap must still be waiting the tail out")
            assertIs<VideoPlayer.State.Playing>(p.state, "and the player is still running the lap")

            p.loop = false
            frames.set(framesFor(500))
            assertTrue(
                awaitTrue { p.state is VideoPlayer.State.Ended },
                "the press inside the wait must be answered, state=${p.state}",
            )
            assertEquals(0, source.seekCount.get(), "and the lap must not have turned on the way out")
        }
    }

    /**
     * The flag is asked once before the tail wait and again after it, and only
     * the first question answers this one: a player that is not looping ends
     * when its last frame is out, not when that frame's display time is up.
     * Asking only after the wait leaves such a file reporting Playing for the
     * length of its own tail, which for a still is the whole of it.
     */
    @Test
    fun `a player that is not looping ends without waiting the tail out`() {
        val source = ScriptedFrameSource(frameCount = 4, declaredDurationNanos = 1_000_000_000L)
        player(source, loop = false).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            frames.set(framesFor(300))
            var seen = -1L
            assertTrue(
                awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == 300_000_000L },
                "the last frame of the file must present, saw ${seen}ns",
            )
            // The lap's own time runs to 400 ms and the clock stays at 300, so
            // an end reached from here is one that did not wait for it.
            assertTrue(
                awaitTrue { p.state is VideoPlayer.State.Ended },
                "the end must not be held for the tail's display time, state=${p.state}",
            )
        }
    }

    /**
     * Turning it on at the end is not a play command, and the documented way
     * back from [VideoPlayer.State.Ended] is the one that still works.
     */
    @Test
    fun `looping turned on after the end does not revive the player`() {
        val source = ScriptedFrameSource(frameCount = 4)
        player(source, loop = false).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            frames.set(framesFor(300))
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Ended }, "the file must end first")

            p.loop = true
            Thread.sleep(200)
            assertIs<VideoPlayer.State.Ended>(p.state, "a property write is not a playback command")
            assertEquals(0, source.seekCount.get(), "and nothing may have turned the lap")

            p.seek(0)
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Playing }, "a seek is what revives it")
        }
    }
}
