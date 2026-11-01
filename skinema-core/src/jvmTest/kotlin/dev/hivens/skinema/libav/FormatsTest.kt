package dev.hivens.skinema.libav

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The extended "formats" decode set, round-tripped: the test CLI encodes a
 * tiny clip in each codec and container, then the bundle under test must
 * demux and decode it. A case skips when the CLI cannot encode that codec (a
 * fixture is impossible) or when the bundle carries no extended formats (the
 * core tier). Codecs that no FFmpeg build can encode -- VC-1, DTS, TrueHD,
 * most legacy -- cannot be round-tripped this way; CapabilitiesTest's load
 * probe is what guarantees their presence in the shipped bundle.
 */
class FormatsTest {

    private val dir: Path = Files.createTempDirectory("skinema-formats")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private data class VideoCase(val codec: String, val ext: String)
    private data class AudioCase(val codec: String, val ext: String)

    // codec/container pairs that the CLI can produce and the formats feature
    // can read back. The containers cover the new demuxers (avi, mpegts, asf)
    // alongside the codecs.
    private val videoCases = listOf(
        VideoCase("mpeg2video", "mp4"),
        VideoCase("mpeg2video", "ts"),   // mpegts demuxer
        VideoCase("mpeg4", "avi"),       // avi demuxer + MPEG-4 Part 2
        VideoCase("msmpeg4", "avi"),     // DivX 3
        VideoCase("wmv2", "avi"),        // Windows Media Video 8
        VideoCase("ffv1", "mkv"),        // lossless intra
    )

    private val audioCases = listOf(
        AudioCase("mp2", "mka"),         // MPEG audio layer II
        AudioCase("wmav2", "asf"),       // Windows Media Audio + asf demuxer
    )

    @TestFactory
    fun `the bundle decodes what the CLI encodes in each format`(): List<DynamicTest> {
        val video = videoCases.map { c ->
            DynamicTest.dynamicTest("video ${c.codec} in .${c.ext}") {
                Fixtures.assumeDecodeEnvironment()
                Fixtures.assumeFormats()
                Fixtures.assumeEncoder(c.codec)
                val out = Fixtures.generate(
                    dir.resolve("${c.codec}-${c.ext}.${c.ext}"),
                    "-f", "lavfi", "-i", "testsrc2=size=128x96:rate=10", "-t", "1",
                    "-pix_fmt", "yuv420p", "-c:v", c.codec,
                )
                VideoDecoder.open(out).use { d ->
                    val f = assertNotNull(d.nextFrame(), "${c.codec} produced no frame")
                    assertTrue(f.width > 0 && f.height > 0, "${c.codec} frame has no size")
                }
            }
        }
        val audio = audioCases.map { c ->
            DynamicTest.dynamicTest("audio ${c.codec} in .${c.ext}") {
                Fixtures.assumeDecodeEnvironment()
                Fixtures.assumeFormats()
                Fixtures.assumeEncoder(c.codec)
                val out = Fixtures.generate(
                    dir.resolve("${c.codec}.${c.ext}"),
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:a", c.codec,
                )
                assertNotNull(AudioDecoder.openOrNull(out), "${c.codec} did not open").use { d ->
                    assertNotNull(d.nextChunk(), "${c.codec} produced no audio")
                }
            }
        }
        return video + audio
    }
}
