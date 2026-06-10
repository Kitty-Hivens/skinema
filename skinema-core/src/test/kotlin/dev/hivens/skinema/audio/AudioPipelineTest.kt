package dev.hivens.skinema.audio

import dev.hivens.skinema.libav.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
            assertTrue(sink.drainCount >= 1, "EOF must drain the buffered tail")
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
            assertTrue(awaitTrue { pipeline.isEnded }, "playback must finish after the seek")
            // 0.25s into 1s of 44.1kHz: the post-flush write is the cropped
            // remainder, sample-exact.
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
}
