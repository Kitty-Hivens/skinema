package dev.hivens.skinema.audio

import dev.hivens.skinema.libav.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the pipeline and a device agree on, and what happens when they cannot.
 *
 * The ladder's order is proven where it is decided, in [PcmFormatTest]. What is
 * left to prove here is that the pipeline walks it, stops at the first rung the
 * device takes, and reports that rung rather than the one it wanted.
 */
class FormatNegotiationTest {

    private val dir: Path = Files.createTempDirectory("skinema-negotiation")

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

    /** Six channels of it, which is what makes the fold observable. */
    private fun surround(name: String): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "1",
        "-ac", "6", "-c:a", "ac3",
    )

    @Test
    fun `a device that takes everything is given the file's own shape`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("ac3")
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(surround("all.ac3"), sink)
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS), "the device must open")
            val agreed = assertNotNull(pipeline.activeFormat, "an open line has a shape")
            assertEquals(6, agreed.channels, "nothing narrowed it, so six channels went out")
            assertEquals(1, sink.opens, "and the first rung was taken, so nothing was retried")
            assertEquals(agreed, sink.format, "the sink and the pipeline must agree on what happened")
        } finally {
            pipeline.close()
        }
    }

    /**
     * The refusal is the protocol. A device with two channels says so by
     * throwing, and the pipeline walks down rather than handing it six and
     * hoping.
     */
    @Test
    fun `a device that refuses multichannel is handed the fold`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("ac3")
        val sink = FakePcmSink()
        sink.accepts = { it.channels <= 2 }
        val pipeline = AudioPipeline(surround("fold.ac3"), sink)
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS), "the device must still open")
            val agreed = assertNotNull(pipeline.activeFormat)
            assertEquals(2, agreed.channels, "the fold is what this device can take")
            assertEquals("stereo", agreed.layout, "and the fold names its order")
            assertTrue(
                sink.offered.first().channels == 6,
                "the file's own shape must have been offered first, got ${sink.offered.first()}",
            )
            assertTrue(sink.offered.size > 1, "a refusal must be followed by another offer")
            assertTrue(
                awaitTrue { sink.totalBytes > 0 },
                "and the sound must play through the shape that was taken",
            )
            assertEquals(
                0,
                sink.totalBytes % agreed.bytesPerFrame,
                "what reaches the device must be whole frames of the agreed shape",
            )
        } finally {
            pipeline.close()
        }
    }

    /**
     * The floor is the promise every sink makes, and a device that takes only
     * that still plays. Everything above it is refused here, so the pipeline
     * has to reach the last rung to get a line at all.
     */
    @Test
    fun `a device that takes only the floor still plays the file`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("ac3")
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(surround("floor.ac3"), sink)
        sink.accepts = { it == PcmFormat.floor(it.sampleRate) }
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS), "the floor must open")
            assertEquals(PcmFormat.floor(44_100), pipeline.activeFormat)
            assertTrue(awaitTrue { sink.totalBytes > 0 }, "and the file must be audible through it")
        } finally {
            pipeline.close()
        }
    }

    /**
     * A device accepting six channels is not evidence that six speakers exist,
     * so a consumer that wants the fold gets it without the device having to
     * refuse anything.
     */
    @Test
    fun `asking for stereo never offers the device more than two channels`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("ac3")
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(
            surround("stereo.ac3"),
            sink,
            channelPreference = ChannelPreference.Stereo,
        )
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS), "the device must open")
            assertEquals(2, assertNotNull(pipeline.activeFormat).channels)
            assertTrue(
                sink.offered.all { it.channels == 2 },
                "a stereo request must never reach for six, offered ${sink.offered}",
            )
        } finally {
            pipeline.close()
        }
    }

    /**
     * A 24-bit file, where the width it arrives in says nothing about how much
     * of it is real. The seam carries both, because a device that has to narrow
     * the samples cannot tell the difference on its own.
     */
    @Test
    fun `a 24-bit file reports twenty-four real bits in a wider carrier`() {
        Fixtures.assumeDecodeEnvironment()
        val file = Fixtures.generate(
            dir.resolve("deep.wav"),
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "1", "-c:a", "pcm_s24le",
        )
        val sink = FakePcmSink()
        val pipeline = AudioPipeline(file, sink)
        try {
            assertNotNull(pipeline.clockFuture.get(10, TimeUnit.SECONDS), "the device must open")
            val agreed = assertNotNull(pipeline.activeFormat)
            assertEquals(PcmEncoding.S32LE, agreed.encoding, "24-bit content arrives in the 32-bit carrier")
            assertEquals(24, agreed.significantBits, "and the seam says how much of it is real")
        } finally {
            pipeline.close()
        }
    }
}
