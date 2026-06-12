package dev.hivens.skinema.audio

import dev.hivens.skinema.libav.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
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
            assertTrue(awaitTrue { pipeline.isEnded }, "playback must finish after the seek")
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
}
