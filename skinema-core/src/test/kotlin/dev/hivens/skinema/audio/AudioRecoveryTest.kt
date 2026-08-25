package dev.hivens.skinema.audio

import dev.hivens.skinema.libav.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Device-loss recovery (#19) under a manual fake sink: the audio thread
 * must detach the clock to wall time when the device stalls, reopen and
 * re-anchor when it returns, and -- the original leak -- exit promptly on
 * close() even while a write is stuck on a gone device.
 */
class AudioRecoveryTest {

    private val dir: Path = Files.createTempDirectory("skinema-audio-recovery")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    /**
     * A [PcmSink] whose "device" can be pulled and returned. While absent a
     * write blocks (a stuck SourceDataLine.write) and the frame position
     * freezes; close() releases the stuck write (the real unblock lever);
     * open() succeeds only once the device is back, like the real reopen.
     */
    private class FakeSink : PcmSink {
        @Volatile var present = true
        @Volatile var openCount = 0
        @Volatile private var closed = false
        private val frames = AtomicLong(0)

        // open() is the audio thread's alone (pump/recovery), so the
        // non-atomic increment has a single writer; the test reads it volatile.
        override fun open(sampleRate: Int) {
            if (!present) throw IllegalStateException("device absent")
            closed = false
            openCount++
            frames.set(0) // a fresh line restarts its frame counter
        }

        override fun write(data: ByteArray, offset: Int, length: Int) {
            // A gone device blocks the write (the stuck SourceDataLine.write);
            // close() ends it, like the real unblock lever.
            while (!present && !closed) Thread.sleep(10)
            if (closed) return // closed mid-write: returns, nothing played
            frames.addAndGet((length / BYTES_PER_FRAME).toLong())
            Thread.sleep(2) // light pacing so the looping pipeline does not hot-spin
        }

        override fun stop() {}
        override fun start() {}
        override fun flush() {}
        override fun framePosition(): Long = frames.get()
        override fun setVolume(volume: Float) {}
        override fun close() { closed = true }

        fun loseDevice() { present = false }
        fun returnDevice() { present = true }
    }

    private fun awaitTrue(deadlineMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun sineWav(): Path = Fixtures.generate(
        dir.resolve("tone.wav"),
        "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
        "-c:a", "pcm_s16le", "-ar", "48000", "-ac", "2",
    )

    @Test
    fun `device loss detaches the clock, return reopens it, close does not leak`() {
        Fixtures.assumeDecodeEnvironment()
        val path = sineWav()
        val sink = FakeSink()
        // loop: keep writing indefinitely so the device can be pulled mid-stream.
        // Short stall/retry windows keep the test quick.
        val pipeline = AudioPipeline(
            path, sink,
            writeStallNanos = 200_000_000L,
            recoveryIntervalMs = 30L,
        )
        try {
            val clock = pipeline.clockFuture.get(5, TimeUnit.SECONDS)
                ?: error("the fake device should have opened a clock")

            // Playing: the device's frame position climbs.
            assertTrue(awaitTrue(2_000) { sink.framePosition() > 0 }, "sound must start playing")

            // Pull the device. The in-flight write stalls, frames freeze --
            // but the watchdog must detach the clock so media time keeps
            // advancing on the wall clock (video would keep moving).
            sink.loseDevice()
            assertTrue(
                awaitTrue(2_000) {
                    val a = sink.framePosition(); Thread.sleep(120); a == sink.framePosition()
                },
                "the frame position must freeze while the device is gone",
            )
            val mAtFreeze = clock.mediaNanos()
            assertTrue(
                awaitTrue(2_000) { clock.mediaNanos() > mAtFreeze },
                "media time must keep advancing on the wall clock while detached",
            )

            // Return the device: recovery must reopen it and frames climb again.
            val opensBefore = sink.openCount
            sink.returnDevice()
            assertTrue(awaitTrue(3_000) { sink.openCount > opensBefore }, "the device must be reopened")
            val resumed = sink.framePosition()
            assertTrue(
                awaitTrue(3_000) { sink.framePosition() > resumed },
                "sound must resume after the device returns",
            )
        } finally {
            // The leak guard: a stuck write on a gone device must not keep
            // close() from returning. Pull it again, then close under a
            // deadline well below the pipeline's own 5s join.
            sink.loseDevice()
            val start = System.currentTimeMillis()
            pipeline.close()
            assertTrue(
                System.currentTimeMillis() - start < 4_500,
                "close() must not block on a stuck write -- the audio thread leaked",
            )
        }
    }

    /**
     * An outage lasts as long as it lasts -- a jack can come back in seconds
     * or never -- so the wait between reopen attempts cannot be deaf. It was:
     * every command that arrived during one went unread for the whole of it.
     * A seek was the expensive case, because the video side holds every frame
     * it has while a landing it was promised is still owed, so scrubbing
     * during an outage froze the picture until the device came back.
     */
    @Test
    fun `a seek during an outage is answered instead of waiting it out`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakeSink()
        val pipeline = AudioPipeline(
            sineWav(), sink,
            writeStallNanos = 200_000_000L,
            recoveryIntervalMs = 30L,
        )
        try {
            pipeline.clockFuture.get(5, TimeUnit.SECONDS) ?: error("the fake device should have opened a clock")
            assertTrue(awaitTrue(2_000) { sink.framePosition() > 0 }, "sound must start playing")

            sink.loseDevice()
            assertTrue(awaitTrue(2_000) { !pipeline.hasSoundLeft }, "the watchdog must declare the device lost")

            pipeline.seek(1_000_000_000L)
            pipeline.videoLanded(1_000_000_000L)
            assertTrue(
                awaitTrue(2_000) { pipeline.pendingSeeks.get() == 0 },
                "the seek must be answered while the device is away, still owed ${pipeline.pendingSeeks.get()}",
            )
        } finally {
            sink.loseDevice()
            pipeline.close()
        }
    }

    /**
     * A line whose position can only be read between writes. That is stricter
     * than a JavaSound line, which does share a native monitor between the
     * position query and the write but runs the write as a polling loop --
     * one non-blocking native call under the monitor, the wait elsewhere --
     * so a query is delayed by an iteration rather than by the write. Held
     * deliberately at the stricter shape: it is the contract a consumer's own
     * PcmSink is allowed to have, and [FakeSink] answers freely and is blind
     * to it.
     */
    private class MonitorSink : PcmSink {
        @Volatile var present = true
        private val monitor = Any()
        private val frames = AtomicLong(0)
        @Volatile private var closed = false

        override fun open(sampleRate: Int) = synchronized(monitor) {
            if (!present) throw IllegalStateException("device absent")
            closed = false
        }

        override fun write(data: ByteArray, offset: Int, length: Int) = synchronized(monitor) {
            // Parked while HOLDING the monitor, which is the whole point: a
            // write stuck in the driver locks every other question out.
            while (!present && !closed) Thread.sleep(10)
            if (closed) return@synchronized
            frames.addAndGet((length / BYTES_PER_FRAME).toLong())
            Thread.sleep(2)
        }

        override fun framePosition(): Long = synchronized(monitor) { frames.get() }

        override fun stop() = Unit
        override fun start() = Unit
        override fun flush() = Unit
        override fun setVolume(volume: Float) = Unit

        // close() must NOT take the monitor: it is the lever that frees the
        // stuck write, exactly as JavaSound's flush is.
        override fun close() {
            closed = true
        }

        fun loseDevice() { present = false }
    }

    /**
     * The rescue must not depend on the device answering. The watchdog used
     * to poll the frame position to decide a write was stuck, and that read
     * goes through the same monitor the write holds -- so on the dead device
     * it exists for, the watchdog parked on the very lock it came to break.
     * The clock was never detached, and every thread that reads it went down
     * behind the one that was already stuck.
     */
    @Test
    fun `a device that answers nothing is still declared lost`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = MonitorSink()
        val pipeline = AudioPipeline(
            sineWav(), sink,
            writeStallNanos = 200_000_000L,
            recoveryIntervalMs = 30L,
        )
        try {
            val clock = pipeline.clockFuture.get(5, TimeUnit.SECONDS)
                ?: error("the fake device should have opened a clock")
            assertTrue(awaitTrue(2_000) { sink.framePosition() > 0 }, "sound must start playing")

            sink.loseDevice()
            // Watched through a flag, not through the clock: reading the
            // clock while the device is still attached goes to the line, so
            // a watchdog that has itself parked there would park this thread
            // too and the test would hang instead of failing.
            assertTrue(
                awaitTrue(3_000) { !pipeline.hasSoundLeft },
                "the watchdog must declare the loss without asking the line where it is",
            )
            // Safe now: a detached clock does not touch the line.
            val detachedAt = clock.mediaNanos()
            assertTrue(
                awaitTrue(2_000) { clock.mediaNanos() > detachedAt },
                "media time must run on the wall clock once detached",
            )
        } finally {
            pipeline.close()
        }
    }

    private companion object {
        const val BYTES_PER_FRAME = 4
    }
}
