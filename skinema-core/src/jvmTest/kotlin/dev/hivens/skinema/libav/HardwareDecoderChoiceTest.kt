package dev.hivens.skinema.libav

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which decoders a device is offered, decided without a device. The decision
 * is the part that was wrong: under AUTO, AV1 went to libdav1d and 8-bit
 * VP8/VP9 to libvpx, neither of which has a hardware config, so the device
 * was never asked and every one of those files decoded on the CPU while
 * reporting, correctly, that it had. A runner with no GPU can check the
 * decision, and the decision is what failed.
 */
class HardwareDecoderChoiceTest {

    private val arena = Arena.ofConfined()
    private val dir: Path = Files.createTempDirectory("skinema-hwchoice-test")

    @AfterTest
    fun cleanup() {
        arena.close()
        dir.toFile().deleteRecursively()
    }

    private fun decoder(name: String): MemorySegment {
        assumeTrue(Fixtures.libraryHasDecoder(name), "the loaded libav has no '$name' decoder, skipping")
        return Libav.avcodecFindDecoderByName(arena.allocateFrom(name))
    }

    private fun names(candidates: List<MemorySegment>, vararg known: String): List<String> =
        candidates.map { c -> known.firstOrNull { decoder(it).address() == c.address() } ?: "unknown" }

    @Test
    fun `AV1 offers the device FFmpeg's own decoder after libdav1d`() {
        Fixtures.assumeDecodeEnvironment()
        val candidates = VideoDecoder.hardwareCandidates(arena, decoder("libdav1d"), "av1", alphaTagged = false)
        assertEquals(listOf("libdav1d", "av1"), names(candidates, "libdav1d", "av1"))
    }

    @Test
    fun `8-bit VP9 offers the device FFmpeg's own decoder after libvpx`() {
        Fixtures.assumeDecodeEnvironment()
        val candidates = VideoDecoder.hardwareCandidates(arena, decoder("libvpx-vp9"), "vp9", alphaTagged = false)
        assertEquals(listOf("libvpx-vp9", "vp9"), names(candidates, "libvpx-vp9", "vp9"))
    }

    @Test
    fun `a VP9 stream carrying alpha is offered libvpx alone`() {
        Fixtures.assumeDecodeEnvironment()
        val candidates = VideoDecoder.hardwareCandidates(arena, decoder("libvpx-vp9"), "vp9", alphaTagged = true)
        assertEquals(listOf("libvpx-vp9"), names(candidates, "libvpx-vp9", "vp9"))
    }

    @Test
    fun `a codec whose software decoder is FFmpeg's own is offered it once`() {
        Fixtures.assumeDecodeEnvironment()
        val candidates = VideoDecoder.hardwareCandidates(arena, decoder("h264"), "h264", alphaTagged = false)
        assertEquals(listOf("h264"), names(candidates, "h264"))
    }

    @Test
    fun `webm alpha survives AUTO on any machine`() {
        // End to end, because the tag is read off the container: a stream
        // that lost its alpha to FFmpeg's own decoder would still decode,
        // opaque, and nothing short of the alpha byte would say so. Runs with
        // a device or without one, and the choice must not depend on either.
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("libvpx-vp9")
        decoder("libvpx-vp9")
        val video = Fixtures.generate(
            dir.resolve("alpha.webm"),
            "-f", "lavfi", "-i", "color=c=red@0.5:size=16x16:rate=5,format=yuva420p", "-t", "1",
            "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", "-deadline", "realtime", "-cpu-used", "8",
        )
        VideoDecoder.open(video, HwAccel.AUTO).use { d ->
            val frame = d.nextFrame()!!
            assertTrue(d.usesDecoder("libvpx-vp9"), "an alpha stream must stay on libvpx under AUTO")
            val a = frame.rgba[(8 * 16 + 8) * 4 + 3].toInt() and 0xFF
            assertTrue(a in 96..160, "alpha 0.5 must survive AUTO, got $a")
        }
    }
}
