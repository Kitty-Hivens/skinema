package dev.hivens.skinema.audio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The behaviours every [PcmSink] has to have, as executable assertions.
 *
 * The interface's KDoc states the rules; this is where an implementation finds
 * out whether it follows them. It matters most for the test doubles: the whole
 * audio half of the player is proven against them, and a double that drifts
 * from a real line proves the wrong thing. `PacedPcmSink` was written to model
 * a line playing in real time, and until this suite existed that was a claim,
 * not a measurement.
 *
 * Assertions are deliberately qualitative -- froze, advanced, reset, unblocked
 * -- because a real device cannot be driven frame by frame. Anything needing
 * exact counts belongs in the tests of a sink that can be driven exactly.
 */
// SEPARATE_THREAD is the point of the annotation. JUnit's default thread mode
// lets the method run to completion and only then compares elapsed time, so a
// write parked on a device nothing drains hangs the build to the runner's own
// limit with the timeout sitting above it looking like protection. This suite
// exists partly to exercise exactly that park.
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
abstract class PcmSinkContract {

    /** A fresh, unopened sink. */
    protected abstract fun newSink(): PcmSink

    /** The rate the suite opens with. */
    protected open val sampleRate: Int = 48_000

    /**
     * Let the device play about [frames] frames. The default waits in real
     * time, which is all a real line can offer; a sink whose playhead is under
     * test control overrides this and moves it exactly.
     */
    protected open fun advance(sink: PcmSink, frames: Long) {
        Thread.sleep(frames * 1_000L / sampleRate + REAL_TIME_SLACK_MILLIS)
    }

    /** Silence, [count] frames long. */
    protected fun frames(count: Int): ByteArray = ByteArray(count * BYTES_PER_FRAME)

    /** Half a second of silence -- comfortably past any plausible prebuffer. */
    private fun writeHalfSecond(sink: PcmSink) {
        val half = frames(sampleRate / 2)
        sink.write(half, 0, half.size)
    }

    private fun halfSecondFrames(): Long = (sampleRate / 2).toLong()

    @Test
    fun `frame position is zero on a freshly opened sink`() {
        newSink().use { sink ->
            sink.open(sampleRate)
            assertEquals(0L, sink.framePosition(), "a fresh line counts from zero")
        }
    }

    @Test
    fun `open leaves the device running`() {
        // No start() anywhere here. If open only prepared the device, the
        // blocking write would never drain and the position would never move --
        // the pipeline would wait forever on a sink that looks healthy.
        newSink().use { sink ->
            sink.open(sampleRate)
            writeHalfSecond(sink)
            advance(sink, halfSecondFrames() / 2)
            assertTrue(sink.framePosition() > 0L, "open must start the device, position ${sink.framePosition()}")
        }
    }

    @Test
    fun `open resets the frame position`() {
        newSink().use { sink ->
            sink.open(sampleRate)
            writeHalfSecond(sink)
            advance(sink, halfSecondFrames() / 2)
            assertTrue(sink.framePosition() > 0L, "the playhead must be moving before the reopen means anything")

            // A track switch reopens the line, and the clock rebases against
            // the fresh one assuming it counts from zero.
            sink.open(sampleRate)
            assertEquals(0L, sink.framePosition(), "a reopened line counts from zero again")
        }
    }

    @Test
    fun `stop freezes the frame position and start resumes it`() {
        newSink().use { sink ->
            sink.open(sampleRate)
            writeHalfSecond(sink)
            advance(sink, halfSecondFrames() / 2)

            // The playhead has to be MOVING before the freeze means anything:
            // freezing a position that is still zero and asserting it stayed
            // zero passes on a sink that ignores stop() entirely.
            val moving = sink.framePosition()
            assertTrue(moving > 0L, "the playhead must be moving before the freeze")

            sink.stop()
            val frozen = sink.framePosition()
            advance(sink, halfSecondFrames() / 2)
            // The seek handshake freezes first and reads second; a position
            // that keeps moving here steps the mastered clock backward later.
            // A tolerance, not equality: a real line's stop is asynchronous.
            val drift = sink.framePosition() - frozen
            assertTrue(drift in 0..(sampleRate / 20).toLong(), "a stopped line drifted $drift frames")

            sink.start()
            writeHalfSecond(sink)
            advance(sink, halfSecondFrames() / 2)
            assertTrue(sink.framePosition() > frozen, "start must let the playhead run again")
        }
    }

    @Test
    fun `the playhead never runs past what was written`() {
        // What separates a device-derived playhead from an extrapolation. The
        // pipeline's end-of-track accounting is a balance of frames written
        // against frames played, and a sink answering wall-clock time would
        // drive that balance negative and cut a lap over its own sound.
        newSink().use { sink ->
            sink.open(sampleRate)
            val written = sampleRate / 2
            val data = frames(written)
            sink.write(data, 0, data.size)
            advance(sink, written.toLong())
            advance(sink, written.toLong())

            val position = sink.framePosition()
            assertTrue(position <= written.toLong(), "played $position of $written written")
            // And it got most of the way there, so a sink that always answers
            // zero does not pass by being trivially under the bound.
            assertTrue(position > written * 0.5, "played only $position of $written written")
        }
    }

    @Test
    fun `close unblocks a write in flight`() {
        // The device-death case, and the watchdog's only lever: a write parked
        // on a line that will never drain cannot free itself.
        val sink = newSink()
        sink.open(sampleRate)
        sink.stop()

        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>()
        val writer = Thread({
            try {
                entered.countDown()
                // Far past any plausible device buffer, against a stopped
                // device: this cannot complete on its own.
                val huge = frames(sampleRate * 10)
                sink.write(huge, 0, huge.size)
            } catch (t: Throwable) {
                // Returning or throwing are both ways to come back; staying
                // parked is not.
                thrown.set(t)
            } finally {
                finished.countDown()
            }
        }, "contract-blocked-writer")
        writer.isDaemon = true
        writer.start()

        assertTrue(entered.await(2, TimeUnit.SECONDS), "the writer thread must start")
        Thread.sleep(500) // let it fill the buffer and reach the park
        sink.close()

        assertTrue(finished.await(5, TimeUnit.SECONDS), "close must free a parked write")
        writer.join(1_000)
        assertTrue(!writer.isAlive, "the writer must be gone")
    }

    @Test
    fun `close is idempotent`() {
        val sink = newSink()
        sink.open(sampleRate)
        sink.close()
        sink.close()
    }

    private companion object {
        /** S16LE stereo: 2 bytes x 2 channels per sample frame. */
        const val BYTES_PER_FRAME = 4

        /** Slack over the nominal duration, so a loaded runner still drains. */
        const val REAL_TIME_SLACK_MILLIS = 150L
    }
}
