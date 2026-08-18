package dev.hivens.skinema.libav

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The decode path across the formats it claims to carry, rather than across
 * the one it was written against.
 *
 * Counted before this existed: libx264 supplied 57 of the suite's 72 video
 * fixtures, yuv420p 60 of 64 pixel formats, and mp4 and mkv all but a
 * handful of the containers. So four fifths of everything the decoder does
 * was proven on one codec, one subsampling and one depth -- including the
 * colour conversion, whose whole job is to differ per format. The extended
 * decoder set even ships as its own capability, whose probe checks that
 * mpeg2video RESOLVES and never asks it to decode anything.
 *
 * A solid colour is the assertion because it travels: every one of these
 * paths converts to RGBA through swscale with a matrix and a range chosen
 * from what the stream declares, and a channel order or a matrix picked
 * wrongly shows up as a number far outside any codec's loss. On its first
 * run this caught a decoder that threw on the second packet of a Theora
 * file.
 */
class FormatMatrixTest {

    private val dir: Path = Files.createTempDirectory("skinema-format-matrix")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private class Case(
        val codec: String,
        val pixFmt: String,
        val ext: String,
        val extra: List<String> = emptyList(),
    ) {
        override fun toString() = "$codec $pixFmt .$ext"
    }

    @Test
    fun `a known colour survives every codec, subsampling and container`() {
        Fixtures.assumeDecodeEnvironment()
        val r = 200
        val g = 60
        val b = 90
        val cases = listOf(
            Case("libx264", "yuv420p", "mp4", listOf("-crf", "0", "-preset", "ultrafast")),
            Case("libx264", "yuv422p", "mkv", listOf("-crf", "0", "-preset", "ultrafast")),
            Case("libx264", "yuv444p", "mkv", listOf("-crf", "0", "-preset", "ultrafast")),
            Case("libx264", "yuv420p10le", "mkv", listOf("-crf", "0", "-preset", "ultrafast")),
            Case("libx265", "yuv420p", "mkv", listOf("-x265-params", "lossless=1")),
            Case("libvpx-vp9", "yuv420p", "webm", listOf("-lossless", "1")),
            Case("libvpx-vp9", "yuv444p", "webm", listOf("-lossless", "1")),
            Case("libaom-av1", "yuv420p", "mkv", listOf("-crf", "0", "-cpu-used", "8")),
            Case("mpeg2video", "yuv420p", "mpg", listOf("-q:v", "1")),
            Case("mpeg4", "yuv420p", "avi", listOf("-q:v", "1")),
            Case("ffv1", "yuv420p", "mkv"),
            Case("ffv1", "yuv444p", "mkv"),
            // Nine of its ten packets are empty -- one per repeated frame --
            // and an empty packet sent to the decoder is EINVAL. This entry
            // is the reason the suite knows that.
            Case("libtheora", "yuv420p", "ogv", listOf("-q:v", "10")),
            Case("prores", "yuv422p10le", "mov"),
            Case("mjpeg", "yuvj420p", "mkv", listOf("-q:v", "1")),
            Case("ffvhuff", "yuv420p", "mkv"),
            Case("png", "rgb24", "mkv"),
            // Above eight bits, where the conversion has to scale as well as
            // convert. The suite carried exactly one ten-bit fixture before
            // this and nothing deeper.
            Case("libx265", "yuv420p10le", "mkv", listOf("-x265-params", "lossless=1")),
            Case("libx265", "yuv422p10le", "mkv", listOf("-x265-params", "lossless=1")),
            Case("libx265", "yuv444p10le", "mkv", listOf("-x265-params", "lossless=1")),
            Case("libx265", "yuv420p12le", "mkv", listOf("-x265-params", "lossless=1")),
            Case("libvpx-vp9", "yuv420p10le", "webm", listOf("-lossless", "1")),
            Case("libvpx-vp9", "yuv420p12le", "webm", listOf("-lossless", "1")),
            Case("libaom-av1", "yuv420p10le", "mkv", listOf("-crf", "0", "-cpu-used", "8")),
            Case("ffv1", "yuv444p16le", "mkv"),
            // Planar RGB rather than YUV: no matrix to get wrong, which is
            // what makes it worth having next to the ones that do.
            Case("ffv1", "gbrp10le", "mkv"),
        )

        val ran = mutableListOf<String>()
        for (case in cases) {
            if (!Fixtures.hasCliEncoder(case.codec)) continue
            val out = dir.resolve("c-${case.codec}-${case.pixFmt}.${case.ext}")
            val built = runCatching {
                Fixtures.generate(
                    out,
                    *(
                        listOf(
                            "-f", "lavfi", "-i", "color=c=0x${"%02X%02X%02X".format(r, g, b)}:size=64x64:rate=10",
                            "-t", "1", "-pix_fmt", case.pixFmt, "-c:v", case.codec,
                        ) + case.extra
                        ).toTypedArray(),
                )
            }
            // A CLI that has the encoder but cannot put it in this container
            // is the environment's business, not this decoder's.
            if (built.isFailure) continue
            ran += case.toString()

            VideoDecoder.open(out).use { decoder ->
                var frames = 0
                var centre: Triple<Int, Int, Int>? = null
                while (true) {
                    val f = decoder.nextFrame() ?: break
                    if (centre == null) {
                        assertEquals(64, f.width, "$case decoded the wrong width")
                        assertEquals(64, f.height, "$case decoded the wrong height")
                        val mid = ((f.height / 2) * f.width + f.width / 2) * 4
                        centre = Triple(
                            f.rgba[mid].toInt() and 0xFF,
                            f.rgba[mid + 1].toInt() and 0xFF,
                            f.rgba[mid + 2].toInt() and 0xFF,
                        )
                    }
                    frames++
                }
                assertTrue(frames > 0, "$case decoded nothing at all")
                val got = centre!!
                val err = maxOf(abs(got.first - r), maxOf(abs(got.second - g), abs(got.third - b)))
                // Wide enough for the lossy entries (measured 1-3 across the
                // whole list), far below a swapped channel or the wrong
                // matrix, which land 100 and more apart.
                assertTrue(err <= 12, "$case came back as $got instead of ($r, $g, $b), off by $err")
            }
        }
        assertTrue(ran.size >= 6, "too few formats present to mean anything, ran $ran")
    }
}
