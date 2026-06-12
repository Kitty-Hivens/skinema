package dev.hivens.skinema.player

import dev.hivens.skinema.audio.BoundedPcmSink
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
import kotlin.test.assertTrue

/**
 * The two seek modes: exact landings preview their keyframe while the
 * decode-forward run is still working, inexact landings take the
 * keyframe as the destination -- picture, sound and the relative-seek
 * base all anchored to where the stream actually starts.
 */
class SeekModesTest {

    private val frames = AtomicLong(0)
    private val clock = AudioClock(48_000) { frames.get() }

    private fun awaitTrue(deadlineMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun player(source: ScriptedFrameSource) = VideoPlayer(
        Path.of("scripted"), false, false, clock, null, 1, null,
    ) { source }

    @Test
    fun `an exact seek previews the keyframe while the run lands`() {
        val source = ScriptedFrameSource(frameCount = 60)
        // Block the frame after the keyframe: the preview must reach the
        // screen while the exact landing is still decoding.
        val latch = source.blockAt(3)
        player(source).use { p ->
            try {
                assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
                while (p.acquireFrame() != null) Unit

                p.seek(250_000_000L)
                var seen = -1L
                assertTrue(
                    awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == 200_000_000L },
                    "the keyframe must preview during the landing, saw ${seen}ns",
                )
                assertIs<VideoPlayer.State.Seeking>(p.state, "the landing is still running behind the preview")

                latch.countDown()
                assertTrue(
                    awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == 300_000_000L },
                    "the exact landing must follow the preview, saw ${seen}ns",
                )
            } finally {
                latch.countDown()
            }
        }
    }

    @Test
    fun `an inexact seek lands on the keyframe at once`() {
        val source = ScriptedFrameSource(frameCount = 60, keyframeEvery = 10)
        player(source).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            while (p.acquireFrame() != null) Unit

            p.seek(1_500_000_000L, exact = false)
            var seen = -1L
            assertTrue(
                awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen == 1_000_000_000L },
                "the keyframe at-or-before the target is the landing, saw ${seen}ns",
            )
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Playing }, "the landing resolves the state")
        }
    }

    @Test
    fun `repeated backsteps reuse the discovered run`() {
        val source = ScriptedFrameSource(frameCount = 100, keyframeEvery = 50)
        player(source).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            p.seek(3_000_000_000L)
            assertTrue(awaitTrue { p.acquireFrame()?.ptsNanos == 3_000_000_000L }, "the seek must land")
            p.pause()
            awaitTrue { p.state is VideoPlayer.State.Paused }

            val before = source.decodeCount.get()
            p.stepBackward()
            assertTrue(awaitTrue { p.acquireFrame()?.ptsNanos == 2_900_000_000L }, "the first backstep lands")
            val discovery = source.decodeCount.get() - before

            val mid = source.decodeCount.get()
            p.stepBackward()
            assertTrue(awaitTrue { p.acquireFrame()?.ptsNanos == 2_800_000_000L }, "the second backstep lands")
            val cachedCost = source.decodeCount.get() - mid

            // First step: discovery (31 decodes) + landing run (30);
            // second: the landing run alone (29) -- the memo answers.
            assertTrue(
                cachedCost < discovery / 2 + 2,
                "a cached backstep must skip discovery: first=$discovery decodes, second=$cachedCost",
            )
        }
    }

    @Test
    fun `a backstep lands without a keyframe preview`() {
        val source = ScriptedFrameSource(frameCount = 100, keyframeEvery = 50)
        player(source).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
            p.seek(3_000_000_000L)
            assertTrue(awaitTrue { p.acquireFrame()?.ptsNanos == 3_000_000_000L }, "the seek must land")
            p.pause()
            awaitTrue { p.state is VideoPlayer.State.Paused }

            val before = source.convertCount.get()
            p.stepBackward()
            assertTrue(awaitTrue { p.acquireFrame()?.ptsNanos == 2_900_000_000L }, "the backstep lands")
            assertEquals(
                1,
                source.convertCount.get() - before,
                "the landing is the step's only conversion -- a keyframe preview would jump the picture back",
            )
        }
    }

    @Test
    fun `relative seeks base on the landed keyframe, not the request`() {
        val source = ScriptedFrameSource(frameCount = 100, keyframeEvery = 10)
        player(source).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")

            // Request 1.5s, land at the 1.0s keyframe.
            p.seek(1_500_000_000L, exact = false)
            assertTrue(awaitTrue { p.acquireFrame()?.ptsNanos == 1_000_000_000L }, "inexact landing")

            // +1s from what the user SEES (1.0s), not from the request:
            // basing on the request would land this exact seek at 2.5s.
            p.seekBy(1_000_000_000L)
            var seen = -1L
            assertTrue(
                awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen >= 1_900_000_000L },
                "the exact follow-up must land, saw ${seen}ns",
            )
            assertEquals(2_000_000_000L, seen, "the relative base is the landed position")
        }
    }
}

/**
 * A backward seek whose audio half is still queued leaves the clock at
 * the OLD, higher position; lateness against it is fiction. The decode
 * side must hold instead of chasing the phantom forward -- the chase
 * burned the decoder seconds past the real position and froze the
 * picture until the clock walked there.
 */
class PhantomChaseTest {

    private val dir: Path = Files.createTempDirectory("skinema-phantom-test")

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
    fun `a backward seek does not chase the stale audio anchor`() {
        Fixtures.assumeDecodeEnvironment()
        // Real audio (the pipeline needs a Path), scripted video: a 30s
        // tone whose sink the test consumes by hand, against a 30s frame
        // grid whose decode is instant -- a phantom chase would rip
        // through it in microseconds.
        val tone = Fixtures.generate(
            dir.resolve("tone.flac"),
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "30", "-c:a", "flac",
        )
        val sink = BoundedPcmSink(capacityFrames = 11_025)
        val source = ScriptedFrameSource(frameCount = 300)
        val player = VideoPlayer(tone, false, true, null, sink, 1, null) { source }
        player.use { p ->
            try {
                assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")
                // Hand-feed the DAC to 15s; video follows.
                assertTrue(
                    awaitTrue {
                        sink.consumeAllButTail(0)
                        p.acquireFrame()
                        sink.framePosition() >= 44_100L * 15
                    },
                    "the played position must reach 15s",
                )
                // Stop consuming: the audio thread fills the buffer and
                // parks inside write -- its half of the next seek will
                // wait, exactly the burst-backlog state.
                assertTrue(awaitTrue { sink.writerParked }, "the audio thread must park in write")

                p.seek(5_000_000_000L)
                assertTrue(
                    awaitTrue { p.acquireFrame()?.ptsNanos == 5_000_000_000L },
                    "the landing must publish against the stale clock",
                )
                // The clock still reads ~15s. A chasing fill would decode
                // the 5s..15s gap (instantly, here) before the anchor
                // lands; a holding fill decodes at most the inventory.
                val decodesAtLanding = source.decodeCount.get()
                Thread.sleep(300)
                val chased = source.decodeCount.get() - decodesAtLanding
                assertTrue(chased <= 5, "the fill must hold while the audio owes its anchor, decoded $chased frames")

                // Release the audio thread: the anchor lands at 5s and the
                // held inventory flows.
                var seen = -1L
                assertTrue(
                    awaitTrue {
                        sink.consumeAllButTail(0)
                        p.acquireFrame()?.let { seen = it.ptsNanos }
                        seen in 5_000_000_001L..8_000_000_000L
                    },
                    "frames past the landing must flow once the clock anchors, saw ${seen}ns",
                )
            } finally {
                sink.release()
            }
        }
    }
}

/** The audio half of an inexact landing needs the real pipeline. */
class InexactSeekAudioTest {

    private val dir: Path = Files.createTempDirectory("skinema-inexact-test")

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
    fun `an inexact landing re-anchors the sound to the keyframe`() {
        Fixtures.assumeDecodeEnvironment()
        // Keyframes every 500ms; the request falls between them. Sound
        // left at the request would play half a second ahead of the
        // picture for the rest of the stream.
        val av = Fixtures.generate(
            dir.resolve("inexact.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-map", "0:v", "-map", "1:a", "-t", "2",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-g", "5",
            "-c:a", "aac", "-shortest",
        )
        val sink = FakePcmSink()
        sink.positionFrames.set(0)
        VideoPlayer(av, loop = false, audio = true, sink = sink).use { p ->
            assertTrue(awaitTrue { p.acquireFrame() != null }, "playback must start")

            p.seek(750_000_000L, exact = false)
            // The upfront audio freeze briefly anchors the clock at the
            // REQUEST; until the landing's corrective re-anchor wins the
            // race, a refill frame past the keyframe can flash through the
            // latest-wins mailbox. The picture assert therefore accepts
            // the landing or its transient successors -- the position
            // assert below is what discriminates the re-anchor bug.
            var seen = -1L
            assertTrue(
                awaitTrue { p.acquireFrame()?.let { seen = it.ptsNanos }; seen >= 500_000_000L },
                "the picture lands at-or-after the 500ms keyframe, saw ${seen}ns",
            )
            // The DAC stands still, so a correctly re-anchored clock reads
            // the landing (modulo sample-grid rounding); the bug reads the
            // 750ms request.
            assertTrue(
                awaitTrue { p.positionNanos() in 499_000_000L..510_000_000L },
                "sound must re-anchor to the landing, position=${p.positionNanos()}ns",
            )
        }
    }
}
