package dev.hivens.skinema.libav

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The fixture generator, read back by something that is not it.
 *
 * [ClosedCaptionFixture] writes caption bytes into an H.264 bitstream by hand,
 * because no encoder anywhere in FFmpeg produces them. That makes it the one
 * fixture in this suite whose correctness is not established by the tool that
 * built it -- so it is established here, by asking FFmpeg to read the captions
 * out through its own path: the `movie` filter's caption output, which runs
 * the h264 decoder, takes the A53 side data it extracts, and decodes it with
 * cc_dec.
 *
 * If this test fails, the fixture is wrong and every caption test above it is
 * asserting nothing. It comes first for that reason.
 *
 * It leans on the `ffmpeg` CLI rather than on the bindings deliberately: the
 * bindings are what the rest of the caption work is going to test, and a
 * fixture proven with them would prove the two consistent rather than either
 * correct.
 */
class ClosedCaptionFixtureTest {

    private val dir: Path = Files.createTempDirectory("skinema-cc-fixture")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `the generated file carries captions FFmpeg itself can read`() {
        Fixtures.assumeDecodeEnvironment()
        val file = ClosedCaptionFixture.generate(dir, "captioned.mp4", text = "HI")
        assertTrue(Files.size(file) > 0, "the fixture must not be empty")

        val srt = readCaptionsThroughFFmpeg(file)
        assertTrue(
            srt.contains("HI"),
            "FFmpeg read no caption text out of the fixture. Output was:\n$srt",
        )
        assertTrue(
            srt.contains("-->"),
            "captions must arrive as timed cues, not as a bare string:\n$srt",
        )
    }

    /** A second text, so the test cannot pass on a constant baked into the generator. */
    @Test
    fun `the caption text is the text asked for`() {
        Fixtures.assumeDecodeEnvironment()
        val file = ClosedCaptionFixture.generate(dir, "other.mp4", text = "OK")
        val srt = readCaptionsThroughFFmpeg(file)
        assertTrue(srt.contains("OK"), "expected the requested text, got:\n$srt")
        assertTrue(!srt.contains("HI"), "the generator must not be emitting a fixed caption:\n$srt")
    }

    /**
     * `movie=<file>[out+subcc]` is FFmpeg's own way in: the filter opens the
     * file, decodes the video, and exposes the closed captions it finds as a
     * second output, which is written here as SubRip so the assertion can be
     * about text rather than about bytes.
     *
     * The file is named RELATIVELY, with the process started in its directory,
     * and that is not tidiness. A filter graph's argument syntax spends both
     * characters a Windows path is made of: ':' separates options and '\'
     * escapes, so an absolute path arrives mangled and the filter opens
     * nothing -- which reads exactly like a fixture carrying no captions.
     * Escaping it is possible and is a second thing to get right per platform;
     * having no colon and no backslash in the argument at all is not.
     */
    private fun readCaptionsThroughFFmpeg(file: Path): String {
        val cmd = listOf(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "movie=${file.fileName}[out+subcc]",
            "-map", "0:1", "-f", "srt", "-",
        )
        val proc = ProcessBuilder(cmd)
            .directory(file.toAbsolutePath().parent.toFile())
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.readAllBytes().decodeToString()
        proc.waitFor(30, TimeUnit.SECONDS)
        return out
    }
}
