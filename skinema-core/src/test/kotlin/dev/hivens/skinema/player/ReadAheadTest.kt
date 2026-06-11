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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The read-ahead queue's behavior, proven over [ScriptedFrameSource] and
 * a manually-driven clock: no natives, no wall-time guessing. The clock
 * is an [AudioClock] over a hand-set frame counter at a realistic rate --
 * a made-up rate like 1e9 overflows Long inside mediaNanos.
 */
class ReadAheadTest {

    private val frames = AtomicLong(0)
    private val clock = AudioClock(48_000) { frames.get() }

    /** DAC frames for a media time, at the 48 kHz test rate. */
    private fun framesFor(ms: Long): Long = ms * 48

    private fun awaitTrue(deadlineMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun player(source: ScriptedFrameSource, depth: Int, loop: Boolean = false) = VideoPlayer(
        Path.of("scripted"), loop, false, clock, null, depth,
    ) { source }

    @Test
    fun `inventory presents through a decode stall`() {
        val source = ScriptedFrameSource(frameCount = 60)
        val latch = source.blockAt(6)
        player(source, depth = 4).use { p ->
            try {
                assertTrue(awaitTrue { p.acquireFrame() != null }, "frame 0 is due at media time 0")
                // With the screen at 200 ms, fill must already be 4 frames
                // ahead, stalled inside frame 6's decode.
                frames.set(framesFor(200))
                assertTrue(
                    awaitTrue { source.maxStartedIndex.get() == 6 },
                    "fill must run ahead to frame 6, got ${source.maxStartedIndex.get()}",
                )
                // The stall is live; the queued frames keep presenting.
                var seen = -1L
                for (ms in longArrayOf(300, 400, 500)) {
                    frames.set(framesFor(ms))
                    assertTrue(
                        awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == ms * 1_000_000L },
                        "frame at ${ms}ms must present from inventory during the stall, saw ${seen}ns",
                    )
                }
                assertEquals(6, source.maxStartedIndex.get(), "decode stayed stalled the whole time")
                assertEquals(1L, latch.count, "the stall was never released")
                latch.countDown()
                frames.set(framesFor(600))
                assertTrue(
                    awaitTrue { p.acquireFrame()?.ptsNanos == 600_000_000L },
                    "the stalled frame must present once decode resumes",
                )
            } finally {
                latch.countDown()
            }
        }
    }

    @Test
    fun `depth 1 decodes on demand only`() {
        val source = ScriptedFrameSource(frameCount = 60)
        player(source, depth = 1).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            frames.set(framesFor(200))
            assertTrue(awaitTrue { source.maxStartedIndex.get() == 3 })
            Thread.sleep(150)
            assertEquals(
                3,
                source.maxStartedIndex.get(),
                "at depth 1 only the next frame may be decoded ahead of the screen",
            )
        }
    }

    @Test
    fun `a seek flushes decoded-ahead inventory`() {
        val source = ScriptedFrameSource(frameCount = 60)
        player(source, depth = 4).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            frames.set(framesFor(200))
            assertTrue(awaitTrue { source.maxStartedIndex.get() == 6 }, "inventory must fill")
            // Latest-wins mailbox: drain it so the next acquire is a
            // post-seek publish.
            while (p.acquireFrame() != null) Unit

            p.seek(0)
            val acquired = mutableListOf<Long>()
            assertTrue(
                awaitTrue {
                    p.acquireFrame()?.let { acquired += it.ptsNanos }
                    acquired.isNotEmpty()
                },
                "the landing must publish",
            )
            assertEquals(listOf(0L), acquired, "only the landing may surface after the flush")
        }
    }

    @Test
    fun `EOF presents the queued tail before Ended`() {
        val source = ScriptedFrameSource(frameCount = 6)
        player(source, depth = 4).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            var seen = -1L
            for (ms in longArrayOf(100, 200, 300, 400)) {
                frames.set(framesFor(ms))
                assertTrue(
                    awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == ms * 1_000_000L },
                    "the tail frame at ${ms}ms must present, saw ${seen}ns",
                )
                assertIs<VideoPlayer.State.Playing>(p.state, "the drain must not be cut short")
            }
            frames.set(framesFor(500))
            assertTrue(
                awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == 500_000_000L },
                "the last frame must present, saw ${seen}ns",
            )
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Ended }, "Ended follows the drained tail")
        }
    }

    @Test
    fun `a loop wrap follows the drained tail`() {
        val source = ScriptedFrameSource(frameCount = 4)
        player(source, depth = 4, loop = true).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            var seen = -1L
            for (ms in longArrayOf(100, 200, 300)) {
                frames.set(framesFor(ms))
                assertTrue(
                    awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == ms * 1_000_000L },
                    "the tail frame at ${ms}ms must present before the wrap, saw ${seen}ns",
                )
            }
            // Tail done; the wrap re-anchors the clock and frame 0 is due.
            assertTrue(
                awaitTrue { p.acquireFrame()?.ptsNanos == 0L },
                "the wrap must come around to frame 0 after the tail",
            )
        }
    }

    @Test
    fun `pause holds inventory and resume presents it without re-decode`() {
        val source = ScriptedFrameSource(frameCount = 60)
        player(source, depth = 4).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            // Wait for a full queue first: pausing mid-fill would let the
            // post-resume fill bump the decode watermark spuriously.
            assertTrue(awaitTrue { source.maxStartedIndex.get() == 4 }, "inventory must fill")

            p.pause()
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Paused })
            Thread.sleep(50)
            p.acquireFrame()
            // Nothing presents while paused, even with frames coming due.
            frames.set(framesFor(300))
            Thread.sleep(150)
            assertNull(p.acquireFrame(), "no frames may surface while paused")
            assertEquals(4, source.maxStartedIndex.get(), "decode must stand still while paused")

            // Resume must serve the held inventory; decode is blocked at
            // frame 5, so frames 1..4 can only come from the queue.
            val latch = source.blockAt(5)
            try {
                p.resume()
                assertTrue(awaitTrue { p.state is VideoPlayer.State.Playing })
                var seen = -1L
                assertTrue(
                    awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == 300_000_000L },
                    "the held inventory must present after resume, saw ${seen}ns",
                )
            } finally {
                latch.countDown()
            }
        }
    }

    @Test
    fun `a clock jump degrades to a monotonic slideshow`() {
        val source = ScriptedFrameSource(frameCount = 300)
        player(source, depth = 4).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            val seen = mutableListOf<Long>()
            frames.set(framesFor(500))
            assertTrue(
                awaitTrue {
                    p.acquireFrame()?.let { seen += it.ptsNanos }
                    seen.lastOrNull()?.let { it >= 500_000_000L } == true
                },
                "normal pacing must follow the clock",
            )

            // Every frame for the next ~19.5s of footage is now deep-late.
            frames.set(framesFor(20_000))
            assertTrue(
                awaitTrue(deadlineMs = 5_000) {
                    p.acquireFrame()?.let { seen += it.ptsNanos }
                    seen.lastOrNull()?.let { it >= 20_000_000_000L } == true
                },
                "the catch-up run must reach the clock, saw ${seen.lastOrNull()}ns",
            )
            val sorted = seen.sorted()
            assertEquals(sorted, seen, "published pts must never move backward: $seen")
        }
    }
}

/**
 * The stranded-tail rule needs the real audio path: the audio thread
 * wraps the clock at ITS end-of-stream, under inventory the video side
 * still holds.
 */
class WrapStrandedTailTest {

    private val dir: Path = Files.createTempDirectory("skinema-wrap-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun awaitTrue(deadlineMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test
    fun `a pre-wrap tail presents at the wrap instead of a lap later`() {
        Fixtures.assumeDecodeEnvironment()
        // Video outlasts audio: when the audio wraps the clock to zero,
        // the queued video tail suddenly stands ~media-length in the
        // future. The pacer must recognize the backward jump and show the
        // tail now -- the old behavior held it until the NEXT lap reached
        // those pts.
        val av = Fixtures.generate(
            dir.resolve("strand.mp4"),
            "-f", "lavfi", "-t", "4", "-i", "testsrc2=size=64x64:rate=10",
            "-f", "lavfi", "-t", "2.5", "-i", "sine=frequency=440:sample_rate=44100",
            "-map", "0:v", "-map", "1:a",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-c:a", "aac",
        )
        val sink = FakePcmSink()
        sink.positionFrames.set(0)
        VideoPlayer(av, loop = true, audio = true, sink = sink, readAheadFrames = 8).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            // Stage one pins the pacer's last clock reading near 2s: a
            // frame due at 2s can only publish after the pacer read the
            // clock there.
            sink.positionFrames.set(44_100 * 2)
            var seen = -1L
            assertTrue(
                awaitTrue {
                    p.acquireFrame()?.let { seen = it.ptsNanos }
                    seen >= 1_900_000_000L
                },
                "video must follow the device clock to ~2s, saw ${seen}ns",
            )
            // Stage two: the device reaches the audio's end; the audio
            // thread wraps the clock to zero under the queued video tail.
            sink.positionFrames.set(44_100 * 5 / 2)
            assertTrue(
                awaitTrue(deadlineMs = 5_000) {
                    p.acquireFrame()?.let { seen = it.ptsNanos }
                    seen >= 2_550_000_000L
                },
                "the stranded tail must present at the wrap, saw ${seen}ns",
            )
        }
    }
}
