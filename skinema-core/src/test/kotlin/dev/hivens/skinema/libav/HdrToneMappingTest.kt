package dev.hivens.skinema.libav

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The tone-mapping path, which had no coverage at all: a parallel swscale
 * context to 16-bit RGBA, an inverse transfer and a pure-Kotlin mapper down
 * to 8-bit, none of it reached by a suite whose deepest fixture was a single
 * ten-bit clip with no transfer on it.
 *
 * Grey is the probe because it pins two things at once. Whatever the mapper
 * does to brightness, a grey that comes back tinted means the BT.2020 matrix
 * or the transfer went wrong; and PQ and HLG are different curves, so the
 * same input under the two tags MUST come back different -- which is the
 * assertion that catches a fixture whose transfer never reached the
 * bitstream. The first version of this measured two files that were both
 * plain SDR, because -color_trc is dropped by the muxer, and called them PQ
 * and HLG: they agreed to the byte, and agreement was the tell.
 */
class HdrToneMappingTest {

    private val dir: Path = Files.createTempDirectory("skinema-hdr-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    /** A grey of [level], tagged BT.2020 with [trc] in the bitstream itself. */
    private fun grey(trc: String, level: Int): Path = Fixtures.generate(
        dir.resolve("hdr-$trc-$level.mkv"),
        "-f", "lavfi", "-i", "color=c=0x%02X%02X%02X:size=64x64:rate=10".format(level, level, level),
        "-t", "1", "-pix_fmt", "yuv420p10le", "-c:v", "libx265",
        // Through x265's own VUI, because the container drops -color_trc.
        "-x265-params", "colorprim=bt2020:transfer=$trc:colormatrix=bt2020nc",
    )

    private fun decodeCentre(path: Path): Triple<Int, Int, Int> =
        VideoDecoder.open(path).use { d ->
            val f = assertNotNull(d.nextFrame(), "$path decoded nothing")
            val mid = ((f.height / 2) * f.width + f.width / 2) * 4
            Triple(
                f.rgba[mid].toInt() and 0xFF,
                f.rgba[mid + 1].toInt() and 0xFF,
                f.rgba[mid + 2].toInt() and 0xFF,
            )
        }

    @Test
    fun `PQ and HLG greys map to neutral, rising, and to different curves`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeEncoder("libx265")
        val levels = listOf(0x40, 0x80, 0xC0)
        val curves = mutableMapOf<String, List<Int>>()

        for (trc in listOf("smpte2084", "arib-std-b67")) {
            val out = levels.map { level ->
                val (r, g, b) = decodeCentre(grey(trc, level))
                val spread = maxOf(r, maxOf(g, b)) - minOf(r, minOf(g, b))
                assertTrue(spread <= 2, "$trc turned a grey of $level into ($r, $g, $b)")
                r
            }
            assertTrue(
                out.zipWithNext().all { (lo, hi) -> hi >= lo },
                "$trc must not invert its own ramp, mapped $levels to $out",
            )
            curves[trc] = out
        }

        // Two different transfers cannot answer the same. If they do, the tag
        // never reached the decoder and both files went through the SDR path.
        assertTrue(
            curves.getValue("smpte2084") != curves.getValue("arib-std-b67"),
            "PQ and HLG mapped identically (${curves.getValue("smpte2084")}), so neither transfer was applied",
        )
    }
}
