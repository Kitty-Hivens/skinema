package dev.hivens.skinema.audio

import dev.hivens.skinema.libav.Fixtures
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The line's position under a reader that never stops asking, which is what
 * the pacer is.
 *
 * A flush bumps the backend's own counter -- it reports the discarded tail as
 * played -- and the sink stores a compensating bias to undo it. Those were two
 * separate reads: a reader could take the counter after the flush and the bias
 * before it, and answer with a whole line buffer of sound that never played.
 * The mastered clock latches whatever it is told into a monotonic floor, so
 * the invention does not pass.
 *
 * The bound is real time, not a fixed slack. A device cannot play faster than
 * the clock on the wall, so any answer that climbs quicker than that came from
 * somewhere else -- and that holds however badly the reader is descheduled,
 * which a fixed slack does not.
 */
class LinePositionRaceTest {

    @Test
    fun `a flush racing a reader never advances the position faster than real time`() {
        Fixtures.assumeAudioDevice()
        val rate = 48_000
        val sink = JavaSoundSink()
        sink.open(rate)
        try {
            val stop = AtomicBoolean(false)
            val worstOvershootFrames = AtomicLong(0)
            val reader = thread(name = "position-reader") {
                var lastPos = sink.framePosition()
                var lastWall = System.nanoTime()
                while (!stop.get()) {
                    val pos = sink.framePosition()
                    val wall = System.nanoTime()
                    val couldHavePlayed = (wall - lastWall) * rate / 1_000_000_000L
                    val overshoot = (pos - lastPos) - couldHavePlayed
                    if (overshoot > worstOvershootFrames.get()) worstOvershootFrames.set(overshoot)
                    lastPos = pos
                    lastWall = wall
                }
            }

            // Eight seeks' worth of freeze-flush-run, with the line kept full
            // so every flush has a tail to discard.
            val quarter = ByteArray(rate / 4 * BYTES_PER_FRAME)
            repeat(8) {
                sink.write(quarter, 0, quarter.size)
                sink.stop()
                sink.flush()
                sink.start()
            }
            stop.set(true)
            reader.join(5_000)

            // A tenth of a second of frames appearing between two readings
            // taken microseconds apart is not playback. The window this
            // catches is the whole line buffer, 200 ms.
            val worst = worstOvershootFrames.get()
            assertTrue(
                worst < rate / 10,
                "the position ran $worst frames (${worst * 1000 / rate}ms) ahead of what could have played",
            )
        } finally {
            sink.close()
        }
    }

    private companion object {
        const val BYTES_PER_FRAME = 4
    }
}
