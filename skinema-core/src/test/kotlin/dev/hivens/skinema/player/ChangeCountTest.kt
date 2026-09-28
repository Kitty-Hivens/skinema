package dev.hivens.skinema.player

import dev.hivens.skinema.audio.ChannelPreference
import dev.hivens.skinema.core.AudioClock
import dev.hivens.skinema.libav.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [VideoPlayer.awaitChange]: what a consumer waits on instead of polling the
 * player on every refresh of its display. Each thing a picture depends on has
 * to move the count, or a consumer that waits on it keeps a stale picture up;
 * and nothing else may, or it redraws for nothing.
 */
class ChangeCountTest {

    private val frames = AtomicLong(0)
    private val clock = AudioClock(48_000) { frames.get() }
    private val dir: Path = Files.createTempDirectory("skinema-change-count")

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

    private fun player(source: ScriptedFrameSource) = VideoPlayer(
        Path.of("scripted"), false, false, clock, null, 1, null, WhenUnwatched.Freeze, false, 1f, ChannelPreference.Source,
    ) { source }

    @Test
    fun `a published frame ends the wait`() {
        player(ScriptedFrameSource(frameCount = 60)).use { p ->
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Playing && p.acquireFrame() != null }, "playback must start")
            val before = p.changeCount
            val woke = AtomicLong(-1)
            val waiter = Thread { woke.set(p.awaitChange(before, 5_000_000_000L)) }.apply { start() }
            // The next frame is due at 100 ms of media time, and nothing
            // publishes it until the clock gets there.
            Thread.sleep(100)
            frames.addAndGet(48_000L * 150 / 1_000)
            waiter.join(5_000)
            assertTrue(woke.get() > before, "the wait must end on the publish, woke with ${woke.get()} from $before")
            assertTrue(p.acquireFrame() != null, "and the frame it woke for must be there to take")
        }
    }

    @Test
    fun `a state change ends the wait`() {
        player(ScriptedFrameSource(frameCount = 60)).use { p ->
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Playing }, "playback must start")
            val before = p.changeCount
            p.pause()
            val after = p.awaitChange(before, 5_000_000_000L)
            assertTrue(after > before, "the pause must move the count")
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Paused }, "and the state must say so")
        }
    }

    /** Nothing moving, nothing to wake for: the wait runs out and says so by returning what it was given. */
    @Test
    fun `a paused player lets the wait run out`() {
        player(ScriptedFrameSource(frameCount = 60)).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            p.pause()
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Paused }, "the pause must land")
            // Let anything the pause set off settle before the reading is taken.
            Thread.sleep(200)
            val before = p.changeCount
            val started = System.nanoTime()
            val after = p.awaitChange(before, 300_000_000L)
            val waitedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(before, after, "nothing changed, so nothing may be reported")
            assertTrue(waitedMs >= 250, "the wait must actually wait, returned after ${waitedMs}ms")
        }
    }

    /** A reading that is already stale returns at once, which is what makes read-then-wait race-free. */
    @Test
    fun `a stale reading returns at once`() {
        player(ScriptedFrameSource(frameCount = 60)).use { p ->
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Playing }, "playback must start")
            val stale = p.changeCount - 1
            val started = System.nanoTime()
            val now = p.awaitChange(stale, 5_000_000_000L)
            assertTrue(now > stale, "the count has moved past the reading")
            assertTrue(System.nanoTime() - started < 1_000_000_000L, "and no time may be spent finding that out")
        }
    }

    /** How a coroutine wrapping the wait in runInterruptible is cancelled. */
    @Test
    fun `an interrupted wait ends with InterruptedException`() {
        player(ScriptedFrameSource(frameCount = 60)).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            p.pause()
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Paused }, "the pause must land")
            val thrown = AtomicReference<Throwable?>(null)
            val started = CountDownLatch(1)
            val waiter = Thread {
                started.countDown()
                try {
                    p.awaitChange(p.changeCount, 30_000_000_000L)
                } catch (t: Throwable) {
                    thrown.set(t)
                }
            }.apply { start() }
            started.await(5, TimeUnit.SECONDS)
            Thread.sleep(100)
            waiter.interrupt()
            waiter.join(5_000)
            assertIs<InterruptedException>(thrown.get(), "the interrupt must end the wait")
        }
    }

    /**
     * Subtitles reach the picture without a frame: a cue appears on a paused
     * player, and turning the track off publishes nothing at all. Both have to
     * move the count, or a surface waiting on it keeps the old cue up.
     */
    @Test
    fun `selecting and dropping a subtitle track move the count on a paused player`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeSubtitleRendering()
        val srt = dir.resolve("subs.srt")
        Files.writeString(srt, "1\n00:00:00,000 --> 00:00:09,000\nTypeset\n")
        val video = Fixtures.generate(
            dir.resolve("subbed.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10", "-i", srt.toString(),
            "-map", "0:v", "-map", "1", "-t", "3",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-c:s", "srt",
        )
        VideoPlayer(video, loop = false, startPaused = true).use { p ->
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Paused && p.subtitleTracks.isNotEmpty() }, "the file must open paused")
            val track = p.subtitleTracks.first()

            val beforeSelect = p.changeCount
            p.selectSubtitleTrack(track.id)
            assertTrue(awaitTrue { p.changeCount > beforeSelect && p.activeSubtitleTrack == track.id }, "selecting must move the count")
            assertTrue(awaitTrue { p.acquireSubtitles() != null }, "and an overlay must be there for it")

            Thread.sleep(300)
            val beforeDrop = p.changeCount
            p.selectSubtitleTrack(null)
            val afterDrop = p.awaitChange(beforeDrop, 5_000_000_000L)
            assertTrue(afterDrop > beforeDrop, "dropping the track must move the count though nothing is published")
            assertEquals(null, p.activeSubtitleTrack)
        }
    }
}
