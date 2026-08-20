package dev.hivens.skinema.player

import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.libav.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A file with nothing to show, on a machine with nothing to play it through.
 *
 * The constructor documents both halves of the degrade -- audio-only files
 * play frameless, and machines without an audio device fall back to silent
 * wall-clock playback -- and the two were asked as one question: frameless
 * was entered only where the pipeline had resolved a clock, which it cannot
 * do when no line will open. An audio-only file on such a machine failed
 * outright, with no duration and a position of zero.
 */
class FramelessPlaybackTest {

    private val dir: Path = Files.createTempDirectory("skinema-frameless")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    /** A machine with no sound card: the line refuses to open. */
    private class NoDevice : PcmSink {
        override fun open(sampleRate: Int): Unit = throw IllegalStateException("no audio device")
        override fun write(data: ByteArray, offset: Int, length: Int) = Unit
        override fun stop() = Unit
        override fun start() = Unit
        override fun flush() = Unit
        override fun framePosition(): Long = 0
        override fun setVolume(volume: Float) = Unit
        override fun close() = Unit
    }

    private fun awaitTrue(deadlineMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun audioOnly(name: String, seconds: String): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", seconds, "-c:a", "libmp3lame",
    )

    @Test
    fun `an audio-only file without a device still runs its lifecycle`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("libmp3lame")
        VideoPlayer(audioOnly("nodevice.mp3", "2"), loop = false, audio = true, sink = NoDevice()).use { player ->
            assertTrue(
                awaitTrue(5_000) { player.state is VideoPlayer.State.Playing },
                "a device that will not open must degrade, not fail: ${player.state}",
            )
            assertEquals(2_000_000_000L, player.durationNanos?.let { it / 100_000_000 * 100_000_000 })
            assertNull(player.acquireFrame(), "there is nothing to show")

            // The wall clock is the only thing moving here; it has to move.
            assertTrue(
                awaitTrue(3_000) { player.positionNanos() > 500_000_000L },
                "the timeline stood at ${player.positionNanos() / 1_000_000}ms",
            )
            assertTrue(
                awaitTrue(6_000) { player.state is VideoPlayer.State.Ended },
                "it must end where the file does, state=${player.state}",
            )
            assertEquals(player.durationNanos, player.positionNanos(), "and rest on the duration")
        }
    }

    @Test
    fun `a looping audio-only file without a device comes round instead of spinning`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("libmp3lame")
        VideoPlayer(audioOnly("looping.mp3", "1"), loop = true, audio = true, sink = NoDevice()).use { player ->
            assertTrue(awaitTrue(5_000) { player.state is VideoPlayer.State.Playing }, "must start")
            // Past the end of one lap and into the next: a level test on a
            // flag nothing clears fired ten times a second and pinned the
            // position near zero instead.
            assertTrue(
                awaitTrue(4_000) { player.positionNanos() > 300_000_000L },
                "the first lap must actually play, at ${player.positionNanos() / 1_000_000}ms",
            )
            assertTrue(
                awaitTrue(4_000) { player.positionNanos() < 200_000_000L },
                "the lap must come round, stuck at ${player.positionNanos() / 1_000_000}ms",
            )
            assertIs<VideoPlayer.State.Playing>(player.state, "and keep playing")
        }
    }
}
