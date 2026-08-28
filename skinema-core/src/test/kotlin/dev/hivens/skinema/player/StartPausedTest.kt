package dev.hivens.skinema.player

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
 * Opening onto a frame instead of into playback, and opening at a volume
 * instead of at full.
 *
 * Both exist because the alternative is a gap nobody can close from outside.
 * A consumer that wanted a poster frame had to construct the player, race to
 * see it reach Playing, and pause it -- and whatever played in between is
 * already on screen and already through the speakers. The sink in particular
 * opens and takes its first chunk on the audio thread's own schedule, so
 * there is no "immediately after the constructor" that beats it.
 *
 * Time is hand-driven for the framed cases (a scripted source over a clock
 * this test turns), so nothing here waits on the machine being fast enough.
 */
class StartPausedTest {

    private val dir: Path = Files.createTempDirectory("skinema-start-paused")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

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

    private fun player(source: ScriptedFrameSource, startPaused: Boolean) = VideoPlayer(
        Path.of("scripted"), false, false, clock, null, 1, null, WhenUnwatched.Freeze, startPaused, 1f,
    ) { source }

    /** Media time forward by [millis], the way a device consuming would. */
    private fun advance(millis: Long) {
        frames.addAndGet(48_000L * millis / 1_000)
    }

    /**
     * The picture has to be up while the player waits, which is the whole
     * point: a poster frame with no poster is a black rectangle, and a
     * consumer asking for one would be no better off than before.
     *
     * The frame is committed forced for that reason -- the pacer publishes
     * forced frames whatever the state says -- and taking it must not start
     * anything, unlike the pause an unwatched player imposes on itself.
     */
    @Test
    fun `a player asked to start paused opens on the first frame and stays there`() {
        val source = ScriptedFrameSource(frameCount = 60)
        player(source, startPaused = true).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "the first frame must publish while paused")
            assertIs<VideoPlayer.State.Paused>(p.state)

            // However much the device would have consumed, nothing moves.
            advance(500)
            Thread.sleep(50)
            assertEquals(0L, p.positionNanos(), "a paused start must not let the timeline run")
            assertIs<VideoPlayer.State.Paused>(p.state)
            assertEquals(
                0,
                source.maxStartedIndex.get(),
                "only the frame on screen may be decoded, ${source.maxStartedIndex.get() + 1} were",
            )
        }
    }

    /** And it is [VideoPlayer.resume] that ends it, the way a caller's own pause is ended. */
    @Test
    fun `resume is what starts a player that began paused`() {
        val source = ScriptedFrameSource(frameCount = 60)
        player(source, startPaused = true).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "the first frame must publish while paused")
            assertIs<VideoPlayer.State.Paused>(p.state)

            p.resume()
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Playing }, "state=${p.state}")
            advance(500)
            assertTrue(
                awaitTrue { source.maxStartedIndex.get() > 0 },
                "decode must run once resumed, stopped at ${source.maxStartedIndex.get()}",
            )
        }
    }

    /**
     * The other direction, because a default that quietly changed would be
     * the worse defect: every player ever written takes this branch.
     */
    @Test
    fun `the default still opens playing`() {
        val source = ScriptedFrameSource(frameCount = 60)
        player(source, startPaused = false).use { p ->
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Playing }, "state=${p.state}")
            advance(500)
            assertTrue(
                awaitTrue { source.maxStartedIndex.get() > 0 },
                "an ordinary player decodes without being told to",
            )
        }
    }

    private fun audioOnly(name: String, seconds: String): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", seconds, "-c:a", "flac",
    )

    /**
     * A file with no picture has no frame to land on, so the whole of a
     * paused start there is that the sound holds and the state says so. It
     * runs the frameless loop, which publishes its own state and would
     * otherwise have overwritten the pause on its first pass.
     */
    @Test
    fun `an audio-only player asked to start paused holds its sound`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        VideoPlayer(
            audioOnly("held.flac", "3"),
            loop = false,
            audio = true,
            sink = sink,
            startPaused = true,
        ).use { p ->
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Paused }, "state=${p.state}")
            // The line takes its first chunk before anything can be paused --
            // that write is what opens the device -- so what proves the hold
            // is that it stops there rather than playing the file out.
            Thread.sleep(300)
            val held = sink.totalBytes
            Thread.sleep(300)
            assertEquals(held, sink.totalBytes, "a paused start must not go on feeding the line")
            assertIs<VideoPlayer.State.Paused>(p.state)

            p.resume()
            assertTrue(awaitTrue { sink.totalBytes > held }, "resume must let the sound run")
        }
    }

    /**
     * The volume has to be on the line before the first sample, not merely
     * soon: the first chunk is written by the audio thread the moment the
     * device opens, and a consumer's setVolume racing that has already lost.
     */
    @Test
    fun `the volume asked for is on the line before the first sample`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        VideoPlayer(audioOnly("quiet.flac", "2"), loop = false, audio = true, sink = sink, volume = 0.25f)
            .use { p ->
                assertTrue(awaitTrue { sink.totalBytes > 0 }, "sound must reach the sink, state=${p.state}")
                assertEquals(
                    0.25f,
                    sink.volumeAtFirstWrite,
                    "the first chunk played at ${sink.volumeAtFirstWrite}",
                )
            }
    }

    /** Out of range is clamped, as it is through [VideoPlayer.setVolume]. */
    @Test
    fun `an out-of-range volume is clamped rather than passed on`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        VideoPlayer(audioOnly("loud.flac", "2"), loop = false, audio = true, sink = sink, volume = 4f)
            .use { p ->
                assertTrue(awaitTrue { sink.totalBytes > 0 }, "sound must reach the sink, state=${p.state}")
                assertEquals(1f, sink.volumeAtFirstWrite, "a sink must never be handed a gain above one")
            }
    }

    /**
     * NaN is not a volume, and it cannot be clamped: every comparison with it
     * is false, so coerceIn returns it unchanged and it reaches a gain control
     * that takes it without complaint and silences the line. setVolume answers
     * one by keeping what it had; at the open, what it had is full.
     */
    @Test
    fun `NaN is not a volume and leaves the default standing`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        VideoPlayer(audioOnly("nan.flac", "2"), loop = false, audio = true, sink = sink, volume = Float.NaN)
            .use { p ->
                assertTrue(awaitTrue { sink.totalBytes > 0 }, "sound must reach the sink, state=${p.state}")
                assertEquals(1f, sink.volumeAtFirstWrite, "NaN must not reach the sink")
            }
    }
}
