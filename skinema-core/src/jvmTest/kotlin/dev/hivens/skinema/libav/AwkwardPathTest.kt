package dev.hivens.skinema.libav

import dev.hivens.skinema.encode.MediaWriter
import dev.hivens.skinema.encode.VideoEncodeConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Paths the platform makes awkward. Every fixture in this suite is an ASCII
 * name with no spaces in a temp directory, which is the one shape that works
 * everywhere -- so nothing here has ever asked what happens to a path a real
 * user has: a space, an apostrophe, a name in their own script.
 *
 * It matters most off Linux. The path crosses into FFmpeg as bytes, and what
 * those bytes must be is not the same question on a platform whose native
 * file APIs are UTF-16 and whose console codepage is neither. This asserts
 * the round trip in both directions -- a file opened for decode, and a file
 * created for encode -- so the matrix answers it for Windows and macOS too.
 */
class AwkwardPathTest {

    private val dir: Path = Files.createTempDirectory("skinema-awkward")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private val awkward = listOf(
        "с пробелом и кириллицей",
        "apostrophe's name",
        "上海 videos",
        "ümlaut-and-accént",
    )

    @Test
    fun `a file whose path is not plain ascii decodes`() {
        Fixtures.assumeDecodeEnvironment()
        for (name in awkward) {
            val nested = Files.createDirectories(dir.resolve(name))
            val file = Fixtures.generate(
                nested.resolve("$name.mp4"),
                "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10", "-t", "1",
                "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            )
            VideoDecoder.open(file).use { d ->
                val frames = generateSequence { d.nextFrame(convert = false) }.count()
                assertEquals(10, frames, "'$name' decoded $frames frames")
            }
        }
    }

    @Test
    fun `a file whose path is not plain ascii can be written`() {
        Fixtures.assumeLibraryEncoder("libx264")
        for (name in awkward) {
            val nested = Files.createDirectories(dir.resolve("out-$name"))
            val out = nested.resolve("$name.mp4")
            MediaWriter.open(
                out,
                VideoEncodeConfig("libx264", 64, 64, 10, options = mapOf("preset" to "ultrafast")),
            ).use { writer ->
                repeat(5) { i -> writer.writeFrame(ByteArray(64 * 64 * 4), i * 100_000_000L) }
                writer.finish()
            }
            assertTrue(Files.size(out) > 0, "'$name' produced an empty file")
            VideoDecoder.open(out).use { d ->
                assertNotNull(d.nextFrame(), "'$name' produced a file that decodes nothing")
            }
        }
    }
}
