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
        // The fixture takes whichever route exists rather than gating on the
        // CLI's own encoder: webp_anim ships in the decode tiers, and on the
        // platform whose CLI carries no libwebp this whole suite skipped.
        Fixtures.assumeAnimatedWebpFixture()
        Fixtures.assumeWebpDecoding()
    }

    private fun animated(name: String): Path = Fixtures.animatedWebp(dir.resolve(name))

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
        // The scenario is built by deleting the file out from under an open
        // decoder, and Windows refuses that: a file mapped by a running
        // process cannot be unlinked, so there is no way to reach the failing
        // reopen from here. The double free this guards is not
        // platform-specific; the other two platforms exercise it.
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Os.current() != Os.WINDOWS,
            "an open file cannot be deleted on Windows, so the failing reopen cannot be staged",
        )
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

    /**
     * The restart escalation used to be armed only by a seek to zero, so a
     * looping player worked and a scrubbed one did not: this demuxer answers
     * a seek, reports success and stays drained, and a seek anywhere but the
     * beginning therefore handed back nothing at all -- for the rest of the
     * session, since nothing else re-arms it.
     */
    @Test
    fun `a scrub to any position keeps an animated webp playing`() {
        assumeWebpEnvironment()
        FrameSources.open(animated("scrubbed.webp")).use { source ->
            assertTrue(source.nextFrame() != null, "playback must start before a scrub means anything")
            source.seekTo(500_000_000L)
            val after = generateSequence { source.nextFrame()?.ptsNanos }.take(3).toList()
            assertTrue(after.isNotEmpty(), "a seek off zero left the demuxer drained")
            // And again, because each escape is tried once per seek: a second
            // scrub has to re-arm rather than inherit a spent escalation.
            source.seekTo(200_000_000L)
            assertTrue(
                generateSequence { source.nextFrame()?.ptsNanos }.take(1).toList().isNotEmpty(),
                "the second scrub found the escalation already spent",
            )
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
        val video = Fixtures.animatedWebp(dir.resolve("alpha.webp"), size = "16x16", rate = 5, alpha = true)
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
        val video = Fixtures.stillWebp(dir.resolve("still.webp"))
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
