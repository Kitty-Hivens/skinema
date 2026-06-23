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
            path, sink, loop = true,
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

    private companion object {
        const val BYTES_PER_FRAME = 4
    }
}
