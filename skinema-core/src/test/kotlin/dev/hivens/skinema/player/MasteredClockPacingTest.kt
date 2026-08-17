package dev.hivens.skinema.player

import dev.hivens.skinema.libav.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the mastered clock does to the picture, over a real audio device.
 *
 * Everything else about the clock is provable against a hand-driven position,
 * and is. This is the part that is not: a device answers about its position
 * once per period and says nothing in between, and no fake reproduces that
 * cadence honestly because the cadence belongs to the machine. Measured here:
 * 21.3 ms of stillness followed by a 21.3 ms jump, which is longer than a
 * frame of 60 fps content -- so frames came due in bursts, the mailbox keeps
 * only the freshest, and 11.6 of every 60 frames a second were overwritten
 * before a consumer could take them.
 *
 * Opt-in for the same reason the sink contract's real-line suite is: a
 * headless runner has no device, and a suite that quietly skips its hardware
 * reads exactly like one that passed.
 */
class MasteredClockPacingTest {

    private val dir: Path = Files.createTempDirectory("skinema-pacing-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `the mastered clock advances smoothly enough to pace 60 fps`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeAudioDevice()
        val file = Fixtures.generate(
            dir.resolve("cadence.mkv"),
            "-f", "lavfi", "-t", "10", "-i", "testsrc2=size=128x128:rate=60",
            "-f", "lavfi", "-t", "10", "-i", "sine=frequency=440:sample_rate=48000",
            "-map", "0:v", "-map", "1:a",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:a", "flac",
        )

        VideoPlayer(file, loop = false, audio = true, readAheadFrames = 4).use { player ->
            // The device takes a buffer's length to start reporting progress
            // at all; measuring through that would measure the start-up.
            val settle = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < settle) {
                player.acquireFrame()
                Thread.sleep(1)
            }

            var readings = 0
            var changes = 0
            var lastReading = -1L
            var lastChangeWall = -1L
            var longestHoldNanos = 0L
            var distinctFrames = 0
            var lastPts = -1L

            val start = System.nanoTime()
            val until = start + WINDOW_NANOS
            while (System.nanoTime() < until) {
                val f = player.acquireFrame()
                if (f != null && f.ptsNanos != lastPts) {
                    lastPts = f.ptsNanos
                    distinctFrames++
                }
                val now = System.nanoTime()
                val reading = player.positionNanos()
                readings++
                if (reading != lastReading) {
                    if (lastChangeWall > 0) {
                        longestHoldNanos = maxOf(longestHoldNanos, now - lastChangeWall)
                    }
                    lastChangeWall = now
                    lastReading = reading
                    changes++
                }
                Thread.sleep(1)
            }
            val spanNanos = System.nanoTime() - start

            // Read straight off the device the clock changed on roughly one
            // reading in twenty; filling the gaps it changes on nearly all of
            // them. Half is far outside either regime, so a loaded runner
            // whose sampler misses ticks still lands on the right side.
            val movingFraction = changes.toDouble() / readings
            assertTrue(
                movingFraction > 0.5,
                "media time stood still on ${100 - (100 * movingFraction).toInt()}% of readings" +
                    " -- it is being read as a staircase, not a clock",
            )

            // The bound that matters to a 60 fps frame is its own period.
            assertTrue(
                longestHoldNanos < 12_000_000L,
                "media time froze for ${longestHoldNanos / 1_000_000}ms, longer than a frame of the content",
            )

            // And the consequence, which is the reason any of this matters.
            val delivered = distinctFrames * 1e9 / spanNanos
            assertTrue(
                delivered > 55.0,
                "only %.1f of the content's 60 frames a second reached the consumer".format(delivered),
            )
        }
    }

    private companion object {
        /** Long enough for ~300 frames, short enough not to pad the suite. */
        const val WINDOW_NANOS = 5_000_000_000L
    }
}
