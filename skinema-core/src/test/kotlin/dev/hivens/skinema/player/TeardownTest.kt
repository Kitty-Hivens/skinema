package dev.hivens.skinema.player

import dev.hivens.skinema.audio.AudioPipeline
import dev.hivens.skinema.audio.BoundedPcmSink
import dev.hivens.skinema.libav.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What `close()` costs the caller, and what it is worth when it returns.
 *
 * The teardown used to be a chain of joins -- pacer, then decoder, then
 * sound, then subtitles -- each with its own wait, behind one the caller was
 * told about. The three sides do not depend on one another, so the shape put
 * the sum of their patience on a caller who had been promised one term of
 * it, and the worst case was reached by the most ordinary thing there is: a
 * write sitting in a sink that was not draining.
 *
 * Both halves are measured here. The sides are told to go before any of them
 * is joined, so their exits overlap; and a write already inside the sink is
 * broken out of rather than waited out, which is what lets the budget be
 * small enough to spend on a UI thread.
 */
class TeardownTest {

    private val dir: Path = Files.createTempDirectory("skinema-teardown-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun awaitTrue(deadlineMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun millisOf(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000
    }

    /** Picture and sound, both two seconds; the sound is what gets stuck. */
    private fun sounded(name: String): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-t", "2", "-i", "testsrc2=size=64x64:rate=10",
        "-f", "lavfi", "-t", "2", "-i", "sine=frequency=440:sample_rate=44100",
        "-map", "0:v", "-map", "1:a",
        "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        "-c:a", "flac",
    )

    /**
     * A sink that stopped draining is the ordinary worst case, not an exotic
     * one -- it is every paused device, every line whose consumer went away.
     * The audio thread is then inside a blocking write, and a teardown that
     * joins it can only wait: the write returns when the sink is closed, and
     * the thread that would close it is the one being joined.
     *
     * So the close does not join it. Announcing the close breaks the write
     * out of the sink through the watchdog -- the caller PcmSink.close
     * already names for exactly that -- and the join then finds a thread that
     * is already leaving. What the caller lent the player is its own again by
     * the time close() returns, which is the whole point of the number.
     */
    @Test
    fun `close returns inside its budget with the audio thread parked in a write`() {
        Fixtures.assumeDecodeEnvironment()
        // A tenth of a second of buffer and nothing consuming it: the pump
        // fills it and parks on the next write.
        val sink = BoundedPcmSink(capacityFrames = 4_410L)
        val player = VideoPlayer(sounded("parked.mkv"), loop = false, audio = true, sink = sink)
        assertTrue(awaitTrue { sink.writerParked }, "the audio thread must be stuck in a write to measure this")

        val elapsed = millisOf { player.close() }

        assertTrue(
            elapsed < 2_000,
            "close() spent ${elapsed}ms on a parked sink; it must not wait out the stall bound",
        )
        assertFalse(sink.writerParked, "the write must be out of the consumer's sink by the time close() returns")
    }

    /**
     * The door for a caller that cannot block at all. A dispose on a UI
     * thread has no second to give, and the part it actually needs -- its own
     * sink back, no more PCM arriving in it -- does not need one: that is
     * settled by announcing the close, and only the native memory is settled
     * by waiting.
     */
    @Test
    fun `closeAsync does not wait for the teardown and the player still settles Closed`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = BoundedPcmSink(capacityFrames = 4_410L)
        val player = VideoPlayer(sounded("async.mkv"), loop = false, audio = true, sink = sink)
        assertTrue(awaitTrue { sink.writerParked }, "the audio thread must be stuck in a write to measure this")

        val elapsed = millisOf { player.closeAsync() }

        assertTrue(elapsed < 250, "closeAsync() waited ${elapsed}ms; it is the door that does not wait")
        assertTrue(
            awaitTrue(5_000) { player.state is VideoPlayer.State.Closed },
            "the teardown must still finish on its own, state=${player.state}",
        )
    }

    /**
     * The same rescue, with the stall bound taken out of the picture.
     *
     * The watchdog frees a stuck write on its own after the pipeline's
     * stall bound, so on the player's three seconds a close could look prompt
     * for a reason that has nothing to do with the close. Pushed out to
     * thirty, only the announcement can be what freed this one -- and it does,
     * in milliseconds.
     */
    @Test
    fun `the shutter frees a write the stall bound would still be waiting out`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = BoundedPcmSink(capacityFrames = 4_410L)
        val pipeline = AudioPipeline(
            sounded("shutter.mkv"),
            sink,
            loop = false,
            writeStallNanos = 30_000_000_000L,
        )
        assertTrue(awaitTrue { sink.writerParked }, "the audio thread must be stuck in a write to measure this")

        val elapsed = millisOf { pipeline.close() }

        assertTrue(
            elapsed < 2_000,
            "close() spent ${elapsed}ms; with a thirty-second stall bound only the announcement can free the write",
        )
        assertFalse(pipeline.alive, "the audio thread must be gone, not merely announced to")
    }
}
