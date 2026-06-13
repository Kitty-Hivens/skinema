package dev.hivens.skinema.webp

import dev.hivens.skinema.libav.Fixtures
import dev.hivens.skinema.libav.FrameSources
import dev.hivens.skinema.libav.LibavException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebpAnimSourceTest {

    private val dir: Path = Files.createTempDirectory("skinema-webp-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    // libwebpdemux is an optional capability (absent = the libav
    // fallback); the fixture also needs a libwebp ENCODER, which brew's
    // ffmpeg lacks, so this can skip on the encoder before reaching the
    // load check. SKINEMA_REQUIRE_CAPS 'webp' makes a present-but-broken
    // library loud (here when reached; always in CapabilitiesTest).
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

    @Test
    fun `animated webp decodes every frame on the pts grid`() {
        assumeWebpEnvironment()
        FrameSources.open(animated("anim.webp")).use { source ->
            assertIs<WebpAnimSource>(source, "RIFF/WEBP must route to libwebp")
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
    fun `still webp routes through libwebp as a single frame`() {
        assumeWebpEnvironment()
        val video = Fixtures.generate(
            dir.resolve("still.webp"),
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-frames:v", "1",
            "-c:v", "libwebp",
        )
        FrameSources.open(video).use { source ->
            assertIs<WebpAnimSource>(source)
            val frames = generateSequence { source.nextFrame() }.count()
            assertEquals(1, frames)
        }
    }

    @Test
    fun `animated webp reports no duration`() {
        assumeWebpEnvironment()
        FrameSources.open(animated("nodur.webp")).use { source ->
            assertNull(source.durationNanos(), "the format does not declare one")
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
