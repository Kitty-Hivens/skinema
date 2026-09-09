package dev.hivens.skinema.player

import dev.hivens.skinema.audio.ChannelPreference
import dev.hivens.skinema.audio.FakePcmSink
import dev.hivens.skinema.audio.PcmFormat
import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.core.AudioClock
import dev.hivens.skinema.libav.FrameSource
import dev.hivens.skinema.libav.Fixtures
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Another file on the same player.
 *
 * A queue was one player per item before this, and the cost was not the
 * allocation: a new player opens its own device and its own threads, knows
 * nothing of the volume, rate or looping set on the last one, and shows a gap
 * where one has gone and the next has not opened yet. Everything here is proven
 * over [ScriptedFrameSource] and a hand-driven clock, so no file and no device
 * are involved in the decision.
 */
class SourceSwitchTest {

    private val frames = AtomicLong(0)
    private val clock = AudioClock(48_000) { frames.get() }

    /** DAC frames for a media time, at the 48 kHz test rate. */
    private fun framesFor(ms: Long): Long = ms * 48

    private val first: Path = Path.of("first")
    private val second: Path = Path.of("second")
    private val missing: Path = Path.of("missing")

    private fun awaitTrue(deadlineMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun player(sources: Map<Path, FrameSource>, loop: Boolean = false) = VideoPlayer(
        first, loop, false, clock, null, 4, null, WhenUnwatched.Freeze, false, 1f, ChannelPreference.Source,
    ) { asked -> sources[asked] ?: throw NoSuchFileException(asked.toString()) }

    @Test
    fun `a switch plays the next file on the same player`() {
        val one = ScriptedFrameSource(frameCount = 60)
        val two = ScriptedFrameSource(frameCount = 60)
        player(mapOf(first to one, second to two)).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            frames.set(framesFor(200))
            assertTrue(awaitTrue { one.maxStartedIndex.get() >= 2 }, "the first file must be playing")

            p.setSource(second)
            assertTrue(awaitTrue { p.source == second }, "the switch must take, source=${p.source}")
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Playing }, "and it must run on, state=${p.state}")
            assertNull(p.sourceFailure, "nothing was refused")
            assertTrue(one.closed.get(), "the file that left must be closed")
            var seen = -1L
            assertTrue(
                awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == 0L },
                "the new file must be on screen from its start, saw ${seen}ns",
            )
            assertTrue(p.positionNanos() < 100_000_000L, "on its own timeline, at ${p.positionNanos()}ns")
        }
    }

    /**
     * The refusal a queue is built on: one unreadable item must cost that item,
     * not the player and the rest of the queue behind it.
     */
    @Test
    fun `a file that will not open leaves the one playing alone`() {
        val one = ScriptedFrameSource(frameCount = 60)
        player(mapOf(first to one)).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")

            p.setSource(missing)
            assertTrue(awaitTrue { p.sourceFailure != null }, "the refusal must be reported")
            assertIs<NoSuchFileException>(p.sourceFailure, "with the cause the open gave")
            assertEquals(first, p.source, "and the file playing must not change")
            assertIs<VideoPlayer.State.Playing>(p.state, "nor may the player stop")
            assertFalse(one.closed.get(), "the file playing must not be closed under it")

            frames.set(framesFor(400))
            assertTrue(awaitTrue { one.maxStartedIndex.get() >= 4 }, "and it must carry on playing")
        }
    }

    @Test
    fun `a switch revives a player that has ended`() {
        val one = ScriptedFrameSource(frameCount = 4)
        val two = ScriptedFrameSource(frameCount = 60)
        player(mapOf(first to one, second to two)).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            frames.set(framesFor(300))
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Ended }, "the first file must end")

            p.setSource(second)
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Playing }, "the next file must play, state=${p.state}")
            assertEquals(second, p.source)
            var seen = -1L
            assertTrue(
                awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == 0L },
                "from its first frame, saw ${seen}ns",
            )
        }
    }

    /**
     * A pause the consumer asked for outlives the switch, the way it outlives
     * everything else. The picture is still up, because a paused player showing
     * nothing is what a poster frame exists to prevent.
     */
    @Test
    fun `a paused player takes the switch and holds on the first frame`() {
        val one = ScriptedFrameSource(frameCount = 60)
        val two = ScriptedFrameSource(frameCount = 60)
        player(mapOf(first to one, second to two)).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            p.pause()
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Paused }, "the pause must land")

            p.setSource(second)
            assertTrue(awaitTrue { p.source == second }, "the switch must take")
            assertIs<VideoPlayer.State.Paused>(p.state, "and it must stay paused")
            var seen = -1L
            assertTrue(
                awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == 0L },
                "the first frame of the new file must be up, saw ${seen}ns",
            )
            Thread.sleep(150)
            assertEquals(0, two.maxStartedIndex.get(), "a paused player must not decode past its poster")
        }
    }

    /**
     * The switch is asked for while the decoder is somewhere else entirely: a
     * looping file at the end of its lap, waiting out the tail before it turns.
     * The wait is where a queue's "next" press lands whenever the file is short,
     * and it must not cost a lap.
     */
    @Test
    fun `a switch asked for at the end of a lap is taken instead of the wrap`() {
        val one = ScriptedFrameSource(frameCount = 4, declaredDurationNanos = 1_000_000_000L)
        val two = ScriptedFrameSource(frameCount = 60)
        player(mapOf(first to one, second to two), loop = true).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            // Stepped for the reason LoopToggleTest gives at the same point: a
            // clock that lands on the last frame's pts while the pacer is still
            // writing that playhead lets the lap turn early, and the wait this
            // test needs to be inside never happens.
            var seen = -1L
            for (ms in longArrayOf(100, 200, 300)) {
                frames.set(framesFor(ms))
                assertTrue(
                    awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == ms * 1_000_000L },
                    "the frame at ${ms}ms must present, saw ${seen}ns",
                )
            }
            Thread.sleep(200)
            assertEquals(0, one.seekCount.get(), "the wrap must still be waiting the tail out")

            p.setSource(second)
            assertTrue(awaitTrue { p.source == second }, "the switch must take from inside the wait")
            assertEquals(0, one.seekCount.get(), "and the lap must not have turned on the way out")
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Playing }, "state=${p.state}")
        }
    }
}

/**
 * The half a scripted source cannot answer: the sound, the device and the
 * metadata that only a container carries.
 */
class SourceSwitchAudioTest {

    private val dir: Path = Files.createTempDirectory("skinema-source-switch")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun awaitTrue(deadlineMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    /**
     * A device that takes its time to open, which is what makes the player's
     * wait for the audio side observable at all.
     *
     * The audio side publishes the new file's tracks after it has opened the
     * line for them, so on a device that opens instantly the window in which
     * the old file's tracks still stand is a few microseconds wide and a player
     * that never waited would win the race nearly every time. A line that takes
     * a moment is not a contrivance either: a real one does.
     */
    private class SlowOpenSink(private val delayMs: Long) : PcmSink {
        val inner = FakePcmSink()

        override fun open(format: PcmFormat) {
            Thread.sleep(delayMs)
            inner.open(format)
        }

        override fun write(data: ByteArray, offset: Int, length: Int) = inner.write(data, offset, length)
        override fun stop() = inner.stop()
        override fun start() = inner.start()
        override fun flush() = inner.flush()
        override fun framePosition(): Long = inner.framePosition()
        override fun setVolume(volume: Float) = inner.setVolume(volume)
        override fun close() = inner.close()
    }

    private fun sounded(name: String, seconds: String, tracks: Int): Path {
        val args = mutableListOf(
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-t", seconds, "-map", "0:v",
        )
        repeat(tracks) { args += listOf("-map", "1:a") }
        args += listOf("-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac")
        return Fixtures.generate(dir.resolve(name), *args.toTypedArray())
    }

    /**
     * What the sink counts is the point: the same object, opened again for the
     * new file rather than closed and replaced. A consumer's sink is a device,
     * a socket or a server connection, and taking it away and handing back
     * another one between two items of a queue is the thing a second player
     * would have done.
     */
    @Test
    fun `the sound follows the switch through the sink the player was given`() {
        Fixtures.assumeDecodeEnvironment()
        val one = sounded("one.mkv", "2", tracks = 1)
        val two = sounded("two.mkv", "1", tracks = 2)
        val slow = SlowOpenSink(delayMs = 300)
        val sink = slow.inner
        VideoPlayer(one, loop = false, audio = true, sink = slow).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null && p.audioTracks.size == 1 }, "the first file must play")
            val opensBefore = sink.opens
            val bytesBefore = sink.totalBytes

            p.setSource(two)
            // Settled facts only. Whether the file is still running when this
            // looks is not one of them: the sink takes every write the moment
            // it is offered, so a one-second file can be through the device
            // between two polls, and a state assertion here would be a race the
            // slowest runner loses. What the switch owes is that it happened.
            assertTrue(
                awaitTrue { p.source == two && p.state !is VideoPlayer.State.Opening },
                "the switch must take and settle, source=${p.source} state=${p.state}",
            )
            assertTrue(p.state !is VideoPlayer.State.Failed, "and it must not have failed: ${p.state}")
            assertEquals(0, sink.closes, "the sink the player was given must survive the switch")
            assertTrue(sink.opens > opensBefore, "the line is reopened for the new file's rate")
            // Read the moment the switch settles, not awaited: the player holds
            // the state back until the audio side has changed files, and
            // without that wait these would still be the last file's.
            assertEquals(2, p.audioTracks.size, "the new file's tracks must be published with it")
            assertTrue(
                awaitTrue { sink.totalBytes > bytesBefore },
                "and its sound must reach the same sink",
            )
        }
    }

    /**
     * A silent item in the middle of a queue, and the file after it.
     *
     * The audio side has no decoder for a file with no audio stream, and what
     * it must NOT do about that is end: ending closes the sink the consumer
     * lent the player, and the sound would then be gone for every later item as
     * well. Playing the second half of this test is what proves the thread and
     * the device survived the silent one.
     */
    @Test
    fun `a file with no sound in it does not take the sound away from the next one`() {
        Fixtures.assumeDecodeEnvironment()
        val sounded = sounded("sounded.mkv", "2", tracks = 1)
        val silent = Fixtures.generate(
            dir.resolve("silent.mkv"),
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10", "-t", "2",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast",
        )
        val sink = FakePcmSink()
        // A device that has played nothing yet, and the whole scenario turns on
        // it: left to report everything written as played, this sink drains a
        // two-second file in milliseconds, the audio side reaches the end of the
        // track before the switch and hands the timeline to the wall there. The
        // silent file would then inherit a clock that was already right, and
        // what happens at the switch itself would be unobservable.
        sink.positionFrames.set(0)
        VideoPlayer(sounded, loop = false, audio = true, sink = sink).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null && p.audioTracks.size == 1 }, "the first file must play")

            p.setSource(silent)
            assertTrue(
                awaitTrue { p.source == silent && p.state !is VideoPlayer.State.Opening },
                "the silent file must be taken, state=${p.state}",
            )
            assertTrue(p.state !is VideoPlayer.State.Failed, "and it must not have failed: ${p.state}")
            assertTrue(p.audioTracks.isEmpty(), "with nothing to select from")
            assertEquals(0, sink.closes, "and the sink must not be taken away with the sound")
            assertTrue(
                awaitTrue { p.positionNanos() > 300_000_000L },
                "its picture must run on the wall clock, at ${p.positionNanos() / 1_000_000}ms",
            )

            val bytesWhileSilent = sink.totalBytes
            p.setSource(sounded)
            assertTrue(awaitTrue { p.source == sounded }, "the file after it must be taken too")
            assertTrue(awaitTrue { p.audioTracks.size == 1 }, "its track must come back")
            assertTrue(
                awaitTrue { sink.totalBytes > bytesWhileSilent },
                "and its sound must reach the sink the silent file did not close",
            )
        }
    }

    /** The same switch with no picture anywhere: a queue of music. */
    @Test
    fun `a frameless player takes the next file and republishes its duration`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("libmp3lame")
        val one = Fixtures.generate(
            dir.resolve("one.mp3"),
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "1", "-c:a", "libmp3lame",
        )
        val two = Fixtures.generate(
            dir.resolve("two.mp3"),
            "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=44100", "-t", "3", "-c:a", "libmp3lame",
        )
        val sink = FakePcmSink()
        VideoPlayer(one, loop = false, audio = true, sink = sink).use { p ->
            assertTrue(
                awaitTrue { p.state is VideoPlayer.State.Playing && p.durationNanos != null },
                "the first file must play, state=${p.state}",
            )
            assertEquals(1_000L, p.durationNanos!! / 100_000_000 * 100, "one second to start with")

            p.setSource(two)
            assertTrue(awaitTrue { p.source == two }, "the switch must take")
            assertEquals(0, sink.closes, "the sink must survive it here too")
            assertEquals(
                3_000L,
                p.durationNanos!! / 100_000_000 * 100,
                "the new file's duration must be published with the switch",
            )
        }
    }
}
