package dev.hivens.skinema.audio

import dev.hivens.skinema.libav.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioPipelineTest {

    private val dir: Path = Files.createTempDirectory("skinema-pipeline-test")

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

    private fun tone(name: String = "tone.flac"): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "1", "-c:a", "flac",
    )

    /**
     * Two flac tracks at different rates; the rate is the discriminator.
     * Long enough that no loop wrap (a legitimate backward clock step)
     * lands inside a test's observation window.
     */
    private fun twoTracks(name: String): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-t", "30", "-i", "sine=frequency=440:sample_rate=44100",
        "-f", "lavfi", "-t", "30", "-i", "sine=frequency=880:sample_rate=48000",
        "-map", "0:a", "-map", "1:a", "-c:a", "flac",
        "-disposition:a:0", "default",
    )

    @Test
    fun `plays a tone through the sink and the clock tracks it`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(tone(), sink, loop = false)
        try {
            val clock = assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS), "a tone has audio")
            assertTrue(awaitTrue { pipeline.isEnded }, "non-looping playback must end")
            assertEquals(44_100 * 4, sink.totalBytes, "every sample reaches the sink")
            assertEquals(44_100, sink.sampleRate)
            // FakePcmSink reports everything written as played.
            assertEquals(1_000_000_000L, clock.mediaNanos())
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `a silent video resolves a null clock`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("silent.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-an",
        )
        val pipeline = AudioPipeline(video, FakePcmSink(), loop = false)
        try {
            assertNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `seek crops to the sample and replays the remainder`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(tone("crop.flac"), sink, loop = false)
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            pipeline.seek(250_000_000L)
            pipeline.videoLanded()
            // isEnded alone can be the PRE-seek end: on a stalled machine
            // the instant fake sink lets the short file play out before
            // the seek is even handled, and the assert reads mid-replay
            // bytes. pendingSeeks goes 1 -> 0 only after the seek handler
            // completes (which resets isEnded), so the pair pins the
            // POST-seek end state.
            assertTrue(
                awaitTrue { pipeline.pendingSeeks.get() == 0 && pipeline.isEnded },
                "playback must finish after the seek",
            )
            // 0.25s into 1s of 44.1kHz: the post-flush write is the cropped
            // remainder, sample-exact.
            assertEquals((44_100 - 11_025) * 4, sink.bytesSinceLastFlush)
            assertEquals(0, sink.writesWhileStopped, "a stopped line must never be written to")
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `seek freezes the sink until the video lands`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(tone("freeze.flac"), sink, loop = true)
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            pipeline.seek(250_000_000L)
            assertTrue(awaitTrue { sink.stopped }, "the sink must freeze at the anchor")
            Thread.sleep(150)
            assertEquals(0, sink.bytesSinceLastFlush, "no audio may flow while the video is landing")

            pipeline.videoLanded()
            assertTrue(awaitTrue { !sink.stopped }, "the landing must release the sink")
            assertTrue(awaitTrue { sink.bytesSinceLastFlush > 0 }, "audio must flow again")
            assertEquals(0, sink.writesWhileStopped, "a stopped line must never be written to")
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `resume during a landing keeps the sink frozen`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(tone("frozen-resume.flac"), sink, loop = true)
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            pipeline.pause()
            assertTrue(awaitTrue { sink.stopped })
            pipeline.seek(250_000_000L)
            pipeline.resume()
            Thread.sleep(150)
            assertTrue(sink.stopped, "resume mid-landing must not let audio run ahead of the video")

            pipeline.videoLanded()
            assertTrue(awaitTrue { !sink.stopped }, "the landing releases the resumed sink")
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `the EOF tail wait ends on the device clock and stays responsive`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        // Manual play position: everything is written near-instantly, but
        // the "DAC" stands at 0, so the buffered tail has not sounded yet.
        sink.positionFrames.set(0)
        val pipeline = AudioPipeline(tone("tail.flac"), sink, loop = false)
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            assertTrue(awaitTrue { sink.totalBytes == 44_100 * 4 }, "the file must be fully written")
            assertFalse(pipeline.isEnded, "the tail has not played; ended must wait for the device")

            // The old sink.drain() deafened the thread here; a seek must be
            // served mid-tail instead of queueing behind it.
            pipeline.seek(250_000_000L)
            assertTrue(awaitTrue { sink.stopped }, "a seek must interrupt the tail wait")

            pipeline.videoLanded()
            sink.positionFrames.set(44_100)
            assertTrue(awaitTrue { pipeline.isEnded }, "playback ends once the device played the tail")
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `a live track switch lands on the new rate at the same playhead`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(twoTracks("switch.mka"), sink, loop = true)
        try {
            val clock = assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            assertEquals(2, pipeline.tracks.size)
            assertEquals(0, pipeline.activeAudioTrack)
            assertEquals(44_100, sink.sampleRate)
            // Freeze the DAC at 100ms so the playhead is deterministic.
            sink.positionFrames.set(4_410)
            assertTrue(awaitTrue { clock.mediaNanos() >= 100_000_000L })

            pipeline.selectTrack(1)
            assertTrue(awaitTrue { pipeline.activeAudioTrack == 1 }, "the switch must land")
            assertEquals(48_000, sink.sampleRate, "the line reopened at the new rate")
            assertTrue(sink.opens >= 2, "a switch reopens the line")
            val pos = clock.mediaNanos()
            assertTrue(pos in 99_000_000L..130_000_000L, "the playhead survives the switch, got ${pos}ns")
            assertEquals(0, pipeline.pendingSeeks.get(), "a switch is not a seek")
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `the switch freezes the line before reading the playhead`() {
        Fixtures.assumeDecodeEnvironment()
        // A live device keeps consuming through the switch; reading the
        // playhead before the freeze rebases the mastered clock BACKWARD
        // by whatever played meanwhile -- the sampler below would see
        // time step back. Manual-position fakes are blind to this.
        val sink = BoundedPcmSink(capacityFrames = 4_410)
        val pipeline = AudioPipeline(twoTracks("freeze-switch.mka"), sink, loop = true)
        val clock = assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
        val running = AtomicBoolean(true)
        val violated = AtomicLong(-1)
        val consumer = thread {
            var maxSeen = 0L
            while (running.get()) {
                // Rate-limited "DAC": ~10x realtime keeps the device live
                // through the switch without racing to the loop wrap.
                sink.consume(441)
                val m = clock.mediaNanos()
                if (m < maxSeen - 2_000_000L) violated.set(maxSeen - m)
                maxSeen = maxOf(maxSeen, m)
                Thread.sleep(1)
            }
        }
        try {
            assertTrue(awaitTrue { clock.mediaNanos() > 200_000_000L }, "playback must run")
            pipeline.selectTrack(1)
            assertTrue(awaitTrue { pipeline.activeAudioTrack == 1 }, "the switch must land")
            Thread.sleep(100)
            assertEquals(-1L, violated.get(), "the clock stepped backward by ${violated.get()}ns across the switch")
        } finally {
            running.set(false)
            consumer.join(2_000)
            sink.release()
            pipeline.close()
        }
    }

    @Test
    fun `a switch mid-landing keeps the sink frozen for videoLanded`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(twoTracks("await.mka"), sink, loop = true)
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            pipeline.seek(100_000_000L)
            assertTrue(awaitTrue { sink.stopped }, "the seek freezes the sink")

            pipeline.selectTrack(1)
            assertTrue(awaitTrue { pipeline.activeAudioTrack == 1 }, "the switch must land")
            assertTrue(sink.stopped, "open() started the fresh line; mid-landing the switch must re-freeze it")
            assertEquals(48_000, sink.sampleRate)

            pipeline.videoLanded()
            assertTrue(awaitTrue { !sink.stopped }, "videoLanded releases as usual")
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `a switch to a shorter track past its end is refused`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        // Track 0 runs 3s, track 1 only 1s.
        val pipeline = AudioPipeline(
            Fixtures.generate(
                dir.resolve("short.mka"),
                "-f", "lavfi", "-t", "3", "-i", "sine=frequency=440:sample_rate=44100",
                "-f", "lavfi", "-t", "1", "-i", "sine=frequency=880:sample_rate=48000",
                "-map", "0:a", "-map", "1:a", "-c:a", "flac",
                "-disposition:a:0", "default",
            ),
            sink,
            loop = true,
        )
        try {
            val clock = assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            // Playhead at 2s -- past the short track's end.
            sink.positionFrames.set(44_100L * 2)
            assertTrue(awaitTrue { clock.mediaNanos() >= 2_000_000_000L })

            pipeline.selectTrack(1)
            Thread.sleep(200)
            assertEquals(0, pipeline.activeAudioTrack, "the switch must be refused")
            assertEquals(44_100, sink.sampleRate, "the line stays on the old track")
            assertTrue(awaitTrue { !sink.stopped }, "the old track resumes")
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `a switch to an unknown index is a no-op`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(twoTracks("badidx.mka"), sink, loop = true)
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            pipeline.selectTrack(99)
            Thread.sleep(150)
            assertEquals(0, pipeline.activeAudioTrack)
            assertEquals(44_100, sink.sampleRate)
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `a seek burst coalesces into one landing at the final target`() {
        Fixtures.assumeDecodeEnvironment()
        // A small bounded buffer parks the thread inside write, so the
        // burst queues up behind it like behind a real device.
        val sink = BoundedPcmSink(capacityFrames = 4_410)
        val pipeline = AudioPipeline(tone("burst.flac"), sink, loop = true)
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            assertTrue(awaitTrue { sink.writerParked }, "the writer must park")
            val flushesBefore = sink.flushes
            pipeline.seek(100_000_000L)
            pipeline.videoLanded()
            pipeline.seek(200_000_000L)
            pipeline.videoLanded()
            pipeline.seek(300_000_000L)
            pipeline.videoLanded()
            assertEquals(3, pipeline.pendingSeeks.get(), "the burst is owed")

            sink.release()
            assertTrue(awaitTrue { pipeline.pendingSeeks.get() == 0 }, "the burst must land")
            assertEquals(
                flushesBefore + 1,
                sink.flushes,
                "a burst lands once at the final target, not once per press",
            )
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `tempo 2 roughly halves what reaches the device`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(tone("tempo.flac"), sink, loop = false)
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            // The flush counter is the applied-handshake; the seek then
            // replays the WHOLE stream through the stretcher, so the
            // measurement does not depend on where the change landed.
            pipeline.setTempo(2.0)
            assertTrue(awaitTrue { sink.flushes == 1 }, "the change must apply")
            pipeline.seek(0)
            pipeline.videoLanded()
            // pendingSeeks pins the post-seek end (see the crop test).
            assertTrue(
                awaitTrue { pipeline.pendingSeeks.get() == 0 && pipeline.isEnded },
                "non-looping playback must end",
            )
            val full = 44_100 * 4
            assertTrue(
                sink.bytesSinceLastFlush in (full * 40 / 100)..(full * 60 / 100),
                "1s at tempo 2 should reach the device roughly halved, got ${sink.bytesSinceLastFlush} of $full",
            )
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `the clock runs at tempo against the device position`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        sink.positionFrames.set(0)
        // 30s of footage: the frozen fake device makes the EOF tail-wait
        // give up on its wall deadline and wrap the clock to zero -- keep
        // that far outside the observation window.
        val media = Fixtures.generate(
            dir.resolve("tempoclock.flac"),
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "30", "-c:a", "flac",
        )
        val pipeline = AudioPipeline(media, sink, loop = true)
        try {
            val clock = assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            pipeline.setTempo(2.0)
            // Anchor deterministically: land a seek at 500ms (the anchor
            // truncates to the sample grid), then play 100ms of device
            // frames -- at tempo 2 that is exactly 200ms of media.
            pipeline.seek(500_000_000L)
            pipeline.videoLanded()
            assertTrue(
                awaitTrue { clock.mediaNanos() in 499_000_000L..501_000_000L },
                "the seek must anchor, got ${clock.mediaNanos()}",
            )
            val anchor = clock.mediaNanos()
            sink.positionFrames.addAndGet(4_410)
            assertTrue(
                awaitTrue { clock.mediaNanos() == anchor + 200_000_000L },
                "100ms of device frames at tempo 2 must cover 200ms of media, got ${clock.mediaNanos()}",
            )
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `a tempo change mid-landing keeps the sink frozen`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(tone("tempofrozen.flac"), sink, loop = true)
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            pipeline.seek(250_000_000L)
            assertTrue(awaitTrue { sink.stopped }, "the seek freezes the sink")
            pipeline.setTempo(2.0)
            Thread.sleep(150)
            assertTrue(sink.stopped, "a rate change must not unfreeze a landing")
            assertEquals(0, sink.writesWhileStopped, "a stopped line must never be written to")
            pipeline.videoLanded()
            assertTrue(awaitTrue { !sink.stopped }, "videoLanded releases as usual")
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `the tempo change freezes the line before reading the playhead`() {
        Fixtures.assumeDecodeEnvironment()
        // Same shape as the switch's freeze test: a live device keeps
        // consuming through the change, and a playhead read before the
        // freeze re-anchors the mastered clock backward by whatever
        // played meanwhile. Manual-position fakes are blind to this.
        val sink = BoundedPcmSink(capacityFrames = 4_410)
        val media = Fixtures.generate(
            dir.resolve("freeze-tempo.flac"),
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "30", "-c:a", "flac",
        )
        val pipeline = AudioPipeline(media, sink, loop = true)
        val clock = assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
        val running = AtomicBoolean(true)
        val violated = AtomicLong(-1)
        val consumer = thread {
            var maxSeen = 0L
            while (running.get()) {
                sink.consume(441)
                val m = clock.mediaNanos()
                if (m < maxSeen - 2_000_000L) violated.set(maxSeen - m)
                maxSeen = maxOf(maxSeen, m)
                Thread.sleep(1)
            }
        }
        try {
            assertTrue(awaitTrue { clock.mediaNanos() > 200_000_000L }, "playback must run")
            val flushesBefore = sink.flushes
            pipeline.setTempo(2.0)
            assertTrue(awaitTrue { sink.flushes > flushesBefore }, "the change must land")
            Thread.sleep(100)
            assertEquals(-1L, violated.get(), "the clock stepped backward by ${violated.get()}ns across the change")
        } finally {
            running.set(false)
            consumer.join(2_000)
            sink.release()
            pipeline.close()
        }
    }

    @Test
    fun `back to tempo 1 the path is sample-exact again`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(tone("temporound.flac"), sink, loop = false)
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            // Each change must APPLY before the next command, or the seek
            // can land while tempo is still 2.0 and the late roundtrip's
            // own flush resets the byte accounting -- the interleaving a
            // stalled runner actually produced. Every applyTempo flushes
            // the sink; the counter is the handshake.
            pipeline.setTempo(2.0)
            assertTrue(awaitTrue { sink.flushes == 1 }, "the first change must apply")
            pipeline.setTempo(1.0)
            assertTrue(awaitTrue { sink.flushes == 2 }, "the roundtrip must apply")
            // The 1.0 path bypasses the stretcher entirely; a final seek's
            // cropped remainder must reach the device sample-exact, the
            // same arithmetic the plain crop test pins.
            pipeline.seek(250_000_000L)
            pipeline.videoLanded()
            // pendingSeeks pins the post-seek end (see the crop test).
            assertTrue(
                awaitTrue { pipeline.pendingSeeks.get() == 0 && pipeline.isEnded },
                "playback must finish after the seek",
            )
            assertEquals((44_100 - 11_025) * 4, sink.bytesSinceLastFlush)
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `pause stops the sink and volume forwards to it`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(tone("ctl.flac"), sink, loop = true)
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            pipeline.pause()
            assertTrue(awaitTrue { sink.stopped }, "pause must stop the device")
            pipeline.setVolume(0.25f)
            assertEquals(0.25f, sink.volume)
            pipeline.resume()
            assertTrue(awaitTrue { !sink.stopped }, "resume must restart the device")
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `a silently dead device detaches the clock to wall time`() {
        Fixtures.assumeDecodeEnvironment()
        // A bounded buffer nobody drains models a device that accepted the
        // line and then stopped consuming without raising -- bare ALSA on a
        // vanished sink, a yanked USB DAC. The audio thread parks in write
        // forever and the frame position freezes; only the watchdog can
        // keep media time -- and the video -- moving.
        val sink = BoundedPcmSink(capacityFrames = 4_410)
        val pipeline = AudioPipeline(
            tone("dead.flac"),
            sink,
            loop = false,
            writeStallNanos = 200_000_000L,
        )
        try {
            val clock = assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            assertTrue(awaitTrue { sink.writerParked }, "the write must block on the frozen device")
            // The device sits at frame zero, so media time can only move if
            // the watchdog detached the clock to wall time.
            assertTrue(
                awaitTrue { clock.mediaNanos() > 0L },
                "the watchdog must detach the clock to wall time",
            )
            val first = clock.mediaNanos()
            Thread.sleep(60)
            assertTrue(
                clock.mediaNanos() > first,
                "detached time must keep advancing while the device stays frozen",
            )
        } finally {
            sink.release()
            pipeline.close()
        }
    }
}
