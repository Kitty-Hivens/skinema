package dev.hivens.skinema.audio

import dev.hivens.skinema.core.AudioClock
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
            // FakePcmSink reports everything written as played, so media time
            // has reached the tone's end. Not equal to it, though: the sink is
            // never fed again past this point, and a clock that stayed there
            // would strand the video of any file whose audio is shorter. From
            // the end of audio the timeline runs on the wall clock, so this
            // reads at-or-past the tone and keeps moving. The upper bound is
            // what still catches a clock that lost the sink entirely.
            val atEnd = clock.mediaNanos()
            assertTrue(
                atEnd >= 1_000_000_000L && atEnd < 3_000_000_000L,
                "media time should have reached the tone's 1s and then run on, got $atEnd",
            )
        } finally {
            pipeline.close()
        }
    }

    /**
     * The end of audio must not become the end of time. A file whose audio
     * track is shorter than its video -- an ordinary thing, any cut where the
     * sound stops early -- froze the picture at that point and went on
     * reporting Playing, because the sink stops being fed and the frame
     * position it masters stops with it.
     */
    @Test
    fun `media time keeps moving after the audio ends`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(tone(), sink, loop = false)
        try {
            val clock = assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS), "a tone has audio")
            assertTrue(awaitTrue { pipeline.isEnded }, "non-looping playback must end")
            val first = clock.mediaNanos()
            assertTrue(awaitTrue { clock.mediaNanos() > first }, "time must advance past the last sample")
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
            // A pipeline that resolved no clock has already left. It must say
            // so: the video side reads this before handing it a seek, and one
            // that advertised itself as live fed a landing counter nobody
            // would ever lower.
            assertFalse(pipeline.alive, "a pipeline with no audio to play must not advertise itself as live")
        } finally {
            pipeline.close()
        }
    }

    /**
     * A sink the caller handed in is the caller's device, socket or server
     * connection, and the pipeline owns closing it. Both exits taken when the
     * file turns out to have no audio sit before the try/finally that does
     * that, so the most ordinary case there is -- asking for sound on a file
     * without any -- leaked it for the life of the process.
     */
    @Test
    fun `a file with no audio still closes the sink it was given`() {
        Fixtures.assumeDecodeEnvironment()
        val video = Fixtures.generate(
            dir.resolve("nosound.mp4"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-an",
        )
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(video, sink, loop = false)
        try {
            assertNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS), "a silent file resolves no clock")
            assertTrue(awaitTrue { sink.closes > 0 }, "the sink must be closed, not abandoned")
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

    /**
     * The tail wait asks whether the device still holds sound -- not whether
     * media time reached a timestamp. Those are different quantities and they
     * do not meet: a chunk's pts comes off the container's grid (Matroska's is
     * a millisecond, so the last chunk's nominal end lands tens of microseconds
     * past the last sample that exists), while media time counts frames the
     * device consumed. The wait could not finish on its own condition and ran
     * to its stall deadline every time, and since the player holds the end of
     * a lap open until this side speaks, half a second of that deadline showed
     * as frozen picture at the end of every lap of any normally muxed file.
     *
     * Raw .flac carries a 1/44100 time base where the two happen to agree,
     * which is why the fixture here is a container.
     */
    @Test
    fun `the end of the track is declared as soon as the device has played it`() {
        Fixtures.assumeDecodeEnvironment()
        val media = Fixtures.generate(
            dir.resolve("grid.mka"),
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "2", "-c:a", "flac",
        )
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(media, sink, loop = false)
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            val whole = 44_100 * 2 * 4
            assertTrue(
                awaitTrue { sink.totalBytes == whole },
                "the file must be fully written, got ${sink.totalBytes} of $whole",
            )
            // The fake calls every written frame played, so at this instant
            // the device holds nothing and there is nothing left to wait for.
            val playedOutAt = System.nanoTime()
            assertTrue(awaitTrue { pipeline.isEnded }, "playback must end")
            val waited = (System.nanoTime() - playedOutAt) / 1_000_000
            assertTrue(waited < 250, "the end must follow the last played sample, waited ${waited}ms")
        } finally {
            pipeline.close()
        }
    }

    /**
     * A flush throws away sound the line had accepted but not played, and
     * the tail wait counts what it handed over -- so a seek has to restate
     * that count against the device, or the line is owed frames that no
     * longer exist anywhere and every wait for the rest of the file runs to
     * its stall deadline. The same defect as the one above, reached from a
     * seek rather than from a container's timestamp grid. Only a line that
     * plays in real time drops anything on a flush, so only one shows it.
     */
    @Test
    fun `a seek restates the tail count over what its flush threw away`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = PacedPcmSink(bufferFrames = 2_205)
        val pipeline = AudioPipeline(tone("reanchor.flac"), sink, loop = false)
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            // Mid-playback, so the line is holding sound the flush will drop.
            assertTrue(awaitTrue { sink.framePosition() > 4_410 }, "playback must be under way")
            pipeline.seek(900_000_000L)
            pipeline.videoLanded()
            assertTrue(awaitTrue { pipeline.pendingSeeks.get() == 0 }, "the seek must land")
            val landedAt = System.nanoTime()
            assertTrue(awaitTrue { pipeline.isEnded }, "playback must end")
            val waited = (System.nanoTime() - landedAt) / 1_000_000
            // A tenth of a second of tone is left to play from here.
            assertTrue(waited < 400, "the end must follow the last played sample, waited ${waited}ms")
        } finally {
            sink.release()
            pipeline.close()
        }
    }

    /**
     * The other direction of the same accounting, and the one that costs
     * sound rather than time: after a seek the line's position is whatever
     * it had reached before the flush, so a count that forgot to settle
     * against the device credits the whole of it as already played and calls
     * the track finished on the spot. The lap then wraps over its own last
     * half second.
     */
    @Test
    fun `the tail wait outlasts the sound a seek left to play`() {
        Fixtures.assumeDecodeEnvironment()
        val media = Fixtures.generate(
            dir.resolve("notearly.flac"),
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "4", "-c:a", "flac",
        )
        // A second of line buffer, so the last second of the file is handed
        // over in one go and what separates a correct wait from an early one
        // is a whole second. With a buffer of a few frames the blocking write
        // paces both cases identically and only the drain tells them apart.
        val sink = PacedPcmSink(bufferFrames = 44_100)
        val pipeline = AudioPipeline(media, sink, loop = false)
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS))
            // Two seconds played before the seek: that is the credit a count
            // which never settles against the device hands the line for sound
            // it has not been given.
            assertTrue(awaitTrue { sink.framePosition() > 88_200 }, "two seconds must play first")
            pipeline.seek(3_000_000_000L)
            pipeline.videoLanded()
            assertTrue(awaitTrue { pipeline.pendingSeeks.get() == 0 }, "the seek must land")
            val landedAt = System.nanoTime()
            assertTrue(awaitTrue { pipeline.isEnded }, "playback must end")
            val played = (System.nanoTime() - landedAt) / 1_000_000
            // A second of tone is left from the seek. A stalled runner can
            // only stretch that, never shorten it, so the bound holds from
            // below whatever the machine is doing.
            assertTrue(played > 600, "the end must wait for the sound, ended after ${played}ms")
        } finally {
            sink.release()
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
            // A band, not a point, for the reason the player-level rate test
            // gives: this device steps once and stops, and the clock fills
            // the gap after a step with wall time up to its ceiling.
            sink.positionFrames.addAndGet(4_410)
            val due = anchor + 200_000_000L
            assertTrue(
                awaitTrue { clock.mediaNanos() in due..(due + AudioClock.MAX_INTERPOLATION_NANOS) },
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
        // line and then stopped consuming without raising -- a yanked USB
        // DAC that does not return (reopenable = false). The frame position
        // freezes; the watchdog detaches the clock to wall time and closes
        // the line, and since the reopen keeps failing the clock stays on
        // wall time, so media time -- and the video -- keep moving.
        val sink = BoundedPcmSink(capacityFrames = 4_410, reopenable = false)
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
