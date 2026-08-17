package dev.hivens.skinema.libav

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WebP decoding, animated and still, through whatever [FrameSources] picks.
 *
 * These assert behaviour and never the implementation behind it: they were
 * written against a libwebp binding, and every one of them survived the move
 * to FFmpeg's own animated-WebP decoder unchanged except for dropping the
 * assertions that named the class.
 */
class WebpDecodingTest {

    private val dir: Path = Files.createTempDirectory("skinema-webp-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun assumeWebpEnvironment() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("libwebp")
        Fixtures.assumeWebpDecoding()
    }

    private fun animated(name: String, vararg extra: String): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "1",
        "-c:v", "libwebp", "-lossless", "0", "-loop", "0", *extra,
    )

    /**
     * The reopen escalation frees the demuxer before it tries to replace it,
     * so a replacement that cannot happen -- the file deleted, the medium
     * gone -- left the decoder holding a freed pointer, and close() freed it
     * a second time. A double free here does not raise, it takes the JVM
     * down. Animated WebP is the format that reaches this path: its demuxer
     * answers a seek, reports success and stays drained, so every lap goes
     * through the reopen.
     */
    @Test
    fun `a reopen that cannot happen still closes safely`() {
        assumeWebpEnvironment()
        val file = animated("vanishing.webp")
        FrameSources.open(file).use { source ->
            // Drain it, so the next read needs the restart escalation.
            @Suppress("ControlFlowWithEmptyBody")
            while (source.nextFrame() != null) { }
            Files.delete(file)
            source.seekTo(0)
            assertFailsWith<LibavException> { source.nextFrame() }
            // The close on the way out of `use` is the one that used to abort
            // the process; reaching the end of this test IS the assertion.
        }
    }

    @Test
    fun `animated webp decodes every frame on the pts grid`() {
        assumeWebpEnvironment()
        FrameSources.open(animated("anim.webp")).use { source ->
            val pts = generateSequence { source.nextFrame()?.ptsNanos }.toList()
            assertEquals(List(10) { it * 100_000_000L }, pts)
        }
    }

    @Test
    fun `alpha survives animated webp`() {
        assumeWebpEnvironment()
        val video = Fixtures.generate(
            dir.resolve("alpha.webp"),
            "-f", "lavfi", "-i", "color=c=red@0.5:size=16x16:rate=5,format=rgba", "-t", "1",
            "-c:v", "libwebp", "-lossless", "1", "-loop", "0", "-pix_fmt", "yuva420p",
        )
        FrameSources.open(video).use { source ->
            val frame = source.nextFrame()!!
            val i = (8 * 16 + 8) * 4
            val a = frame.rgba[i + 3].toInt() and 0xFF
            assertTrue(a in 96..160, "alpha 0.5 should survive animated webp, got $a")
        }
    }

    /**
     * The loop primitive, and the reason the animated-WebP demuxer needs the
     * decoder's restart escalation: it accepts a seek, reports success and
     * stays drained, so nothing here would produce a second frame without it.
     */
    @Test
    fun `seek resets to the start -- the loop primitive`() {
        assumeWebpEnvironment()
        FrameSources.open(animated("loop.webp")).use { source ->
            val drained = generateSequence { source.nextFrame() }.count()
            assertTrue(drained > 0)
            assertNull(source.nextFrame(), "stream is drained")
            source.seekTo(0)
            assertEquals(0L, source.nextFrame()?.ptsNanos, "seek must reopen a drained animation")
        }
    }

    @Test
    fun `still webp decodes as a single frame`() {
        assumeWebpEnvironment()
        val video = Fixtures.generate(
            dir.resolve("still.webp"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-frames:v", "1",
            "-c:v", "libwebp",
        )
        FrameSources.open(video).use { source ->
            val frames = generateSequence { source.nextFrame() }.count()
            assertEquals(1, frames)
        }
    }

    @Test
    fun `animated webp learns its duration after one lap`() {
        assumeWebpEnvironment()
        FrameSources.open(animated("dur.webp")).use { source ->
            assertNull(source.durationNanos(), "the format declares none up front")
            generateSequence { source.nextFrame() }.count()
            assertNull(source.nextFrame(), "the lap is drained")
            assertEquals(1_000_000_000L, source.durationNanos(), "one full lap reveals the 1s duration")
        }
    }

    @Test
    fun `corrupt webp fails closed with LibavException`() {
        Fixtures.assumeWebpDecoding()
        val junk = dir.resolve("junk.webp")
        Files.write(junk, "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + ByteArray(64) { (it * 7).toByte() })
        assertFailsWith<LibavException> { FrameSources.open(junk) }
    }
}
