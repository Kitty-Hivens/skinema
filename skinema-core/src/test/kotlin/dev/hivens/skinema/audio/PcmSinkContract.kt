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
    fun `flush does not count discarded sound as played`() {
        // A flush throws away sound the line had accepted but not played.
        // What it must not do is credit that sound to the playhead: this
        // counter is what the mastered clock is anchored on, and a seek --
        // which is a flush -- would then place the timeline a buffer's
        // length past where the listener actually was.
        //
        // A real line does exactly that if left alone. Its backend derives
        // the position as handed-over minus still-queued, and a flush
        // destroys the second term, so it reports the whole of the first.
        newSink().use { sink ->
            sink.open(sampleRate)
            val quarter = frames(sampleRate / 4)
            // Play some, THEN hand over more and stop straight away. Both
            // halves are load-bearing: the playhead has to be moving or a
            // sink that never plays anything passes, and the line has to be
            // holding sound at the flush or there is nothing to discard.
            // A single write followed by a wait satisfies neither -- the wait
            // drains a real line's buffer dry, which is how this assertion
            // first passed against the very sink it was written to catch.
            sink.write(quarter, 0, quarter.size)
            advance(sink, (sampleRate / 8).toLong())
            sink.write(quarter, 0, quarter.size)

            val playing = sink.framePosition()
            assertTrue(playing > 0L, "the playhead must be moving before the flush means anything")

            sink.stop()
            val frozen = sink.framePosition()
            sink.flush()
            val gained = sink.framePosition() - frozen
            // A tolerance, not equality: a real line's stop is asynchronous,
            // the same slack the freeze test allows. The failure this catches
            // is a whole line buffer, several times over it.
            assertTrue(
                gained <= (sampleRate / 20).toLong(),
                "flush credited $gained frames of discarded sound as played",
            )
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

    /**
     * Volume is best-effort by contract -- not every line exposes a gain --
     * but "best effort" is about what it achieves, never about whether it
     * survives the call. Out-of-range values are clamped rather than
     * rejected, and the call is legal before the device is open and after it
     * is closed, because it arrives from whatever thread the consumer uses
     * and at whatever moment.
     *
     * Written because nothing exercised the real line's gain path at all:
     * every setVolume in this suite ran against a double, and all three
     * doubles implement it as a no-op, so the dB conversion and the
     * control-support probe behind it had never executed anywhere.
     */
    @Test
    fun `volume is accepted at any value and leaves the device playing`() {
        val sink = newSink()
        sink.setVolume(0.5f)
        sink.open(sampleRate)
        for (v in listOf(0f, 0.25f, 1f, -1f, 2f, Float.NaN)) sink.setVolume(v)

        // Surviving the call is the weaker half, and on its own it is nearly
        // unfalsifiable -- the only way to fail is to throw. The half that
        // has teeth is that the device is still playing afterwards: a
        // setVolume that reached for the line's gain by stopping it, or that
        // flushed to apply a change, would leave a consumer's UI slider
        // silently pausing the sound.
        writeHalfSecond(sink)
        advance(sink, halfSecondFrames())
        val before = sink.framePosition()
        sink.setVolume(0.3f)
        writeHalfSecond(sink)
        advance(sink, halfSecondFrames())
        assertTrue(
            sink.framePosition() > before,
            "the playhead must keep moving across a volume change, was $before then ${sink.framePosition()}",
        )

        sink.close()
        sink.setVolume(0.5f)
    }

    /**
     * And it must not wait for a write to finish.
     *
     * Every clock reader in the player reaches the device through this sink,
     * and a consumer setting volume from its UI thread is the ordinary case;
     * a setVolume that parked behind a blocking write would hold that thread
     * for the length of the device buffer. The bound here is deliberately
     * enormous -- this asserts that the call RETURNS, not that it is quick,
     * because how quick is the scheduler's business and not this suite's.
     */
    @Test
    fun `volume does not wait for a write in flight`() {
        val sink = newSink()
        sink.open(sampleRate)
        sink.stop()

        val entered = CountDownLatch(1)
        val writer = Thread({
            entered.countDown()
            runCatching {
                val huge = frames(sampleRate * 10)
                sink.write(huge, 0, huge.size)
            }
        }, "contract-volume-writer")
        writer.isDaemon = true
        writer.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS), "the writer thread must start")
        Thread.sleep(500) // let it fill the buffer and park

        val done = CountDownLatch(1)
        val setter = Thread({ sink.setVolume(0.3f); done.countDown() }, "contract-volume-setter")
        setter.isDaemon = true
        setter.start()
        val returned = done.await(5, TimeUnit.SECONDS)
        sink.close()
        writer.join(1_000)
        setter.join(1_000)
        assertTrue(returned, "setVolume must not park behind a blocking write")
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
