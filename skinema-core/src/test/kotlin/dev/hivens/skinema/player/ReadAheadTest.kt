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
        Path.of("scripted"), loop, false, clock, null, depth, null, WhenUnwatched.Freeze,
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
            while (p.acquireFrame() != null) { /* drain */ }

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
            // Hold the second lap's first decode: the wrap is otherwise
            // fast enough that frame 0 overwrites the final tail frame in
            // the latest-wins mailbox before this test can acquire it.
            val lap2 = source.blockAt(0)
            try {
                var seen = -1L
                for (ms in longArrayOf(100, 200, 300)) {
                    frames.set(framesFor(ms))
                    assertTrue(
                        awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == ms * 1_000_000L },
                        "the tail frame at ${ms}ms must present before the wrap, saw ${seen}ns",
                    )
                }
                // Tail done; the wrap re-anchors the clock and frame 0 is due.
                lap2.countDown()
                assertTrue(
                    awaitTrue { p.acquireFrame()?.ptsNanos == 0L },
                    "the wrap must come around to frame 0 after the tail",
                )
            } finally {
                lap2.countDown()
            }
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
                // Time continues from where the pause froze it, so the device
                // walking during the pause bought nothing: it has to walk
                // again, now, for the held frames to come due. That is the
                // pause contract -- the device advancing under a stopped
                // clock used to count, and a timeline stopped from outside
                // the pipeline then drifted with a line that was still
                // draining.
                frames.set(framesFor(600))
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
    fun `steady-state production is not gated on the room poll`() {
        // The RoomFreed mechanism, measured machine-independently: at
        // depth 1 the fill parks in its command poll whenever the queue
        // is full, and every publish must wake it through the token --
        // discovered by poll timeout instead, each of the 480 frames
        // costs up to 20ms of dead time. The clock advances in 240ms
        // steps (under the chase threshold, so every frame publishes
        // through a room cycle) and the whole stream's wall time is the
        // verdict: healthy is bounded by scheduling noise (~0.3s), the
        // gated regime by 480 poll timeouts (~9.6s). The previous shape
        // of this test paced 125 fps against the live wall clock;
        // thrashed shared runners sank healthy runs below any fixed
        // frame-count threshold.
        val source = ScriptedFrameSource(frameCount = 480, periodNanos = 8_000_000L)
        player(source, depth = 1).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            val started = System.currentTimeMillis()
            for (chunk in 1..16) {
                frames.set(framesFor(chunk * 240L))
                val target = minOf(chunk * 30, 479)
                assertTrue(
                    awaitTrue { source.maxStartedIndex.get() >= target },
                    "the fill must keep up with the clock, at ${source.maxStartedIndex.get()} of $target",
                )
            }
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Ended }, "the stream must play out")
            val elapsedMs = System.currentTimeMillis() - started
            assertTrue(
                elapsedMs < 4_000,
                "480 room cycles took ${elapsedMs}ms -- the poll timeout, not the token, woke the fill",
            )
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

    /**
     * A picture longer than its sound must play out whole, every lap.
     *
     * This replaces two tests that staged a stranded video tail at the loop
     * point. They were written against a design where the SOUND wrapped the
     * clock at its own end, which left the queued picture standing a lap in
     * the future -- and which also meant a four-second picture over a
     * two-and-a-half-second track only ever played two and a half seconds of
     * itself, in time, per lap. The timeline belongs to the file now: the
     * sound simply ends, the picture finishes, and the video side restarts
     * both. A lap therefore cannot strand a tail -- the queue is drained
     * before the wrap -- so the situation those tests built no longer occurs,
     * and what is worth asserting is the outcome that was actually broken.
     */
    @Test
    fun `a picture longer than its sound plays out whole on every lap`() {
        Fixtures.assumeDecodeEnvironment()
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
            // Walk the device to the end of the audio; from there the sound is
            // over and the timeline runs on without it.
            sink.positionFrames.set(44_100 * 5 / 2)
            var seen = -1L
            var maxPts = 0L
            assertTrue(
                awaitTrue(deadlineMs = 15_000) {
                    p.acquireFrame()?.let { seen = it.ptsNanos; if (seen > maxPts) maxPts = seen }
                    maxPts >= 3_500_000_000L
                },
                "the picture past the sound's end must play in time, reached ${maxPts}ns",
            )
            // And the lap does come round, driven by the picture rather than
            // by a track that finished a second and a half earlier.
            assertTrue(
                awaitTrue(deadlineMs = 15_000) {
                    p.acquireFrame()?.let { seen = it.ptsNanos }
                    seen in 0..1_000_000_000L
                },
                "the lap must restart, last pts ${seen}ns",
            )
        }
    }
}
