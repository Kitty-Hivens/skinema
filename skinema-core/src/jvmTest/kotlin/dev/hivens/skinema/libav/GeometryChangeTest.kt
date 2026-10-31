package dev.hivens.skinema.libav

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A stream whose picture changes size partway through, which the decoder has
 * to rebuild its scaler and its buffers for.
 *
 * This defect class has bitten once already: frame buffers were not released
 * when the picture changed size, and the commit that fixed it declined to
 * write a test because the only signal it could think of was a megabyte or two
 * of resident memory a switch -- "a check that cannot be relied on to fail is
 * worse than none". Coverage agrees the branch is untaken to this day: the
 * rebuild arms in both the SDR and the HDR scaler setup are only ever entered
 * in the "nothing changed" direction.
 *
 * There is a better signal than memory, and it is the pixels. A buffer or a
 * scaler left over from the old geometry cannot produce the right colour at
 * the right size on the other side of the switch -- it produces garbage, a
 * short read, or a refusal. So this asserts what the frames are, and gets the
 * lifetime question for free.
 *
 * MPEG-TS rather than a raw elementary stream, deliberately. TS carries a
 * resolution change by design -- it is what a broadcast recording does across
 * a programme boundary -- and its demuxer is in the shipped bundles under the
 * `formats` feature. A raw stream would decode only against a system FFmpeg,
 * which is the shape of test that skips forever on the artifact it is meant to
 * be proving.
 */
class GeometryChangeTest {

    private val dir: Path = Files.createTempDirectory("skinema-geometry-change")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun clip(name: String, colour: String, size: String): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "color=c=$colour:size=$size:rate=10", "-t", "0.6",
        "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-f", "mpegts",
    )

    /** Whether [frame] is [r],[g],[b] at its centre, within yuv round-trip tolerance. */
    private fun centreIs(frame: VideoDecoder.RgbaFrame, r: Int, g: Int, b: Int): Boolean {
        val i = (frame.height / 2 * frame.width + frame.width / 2) * 4
        val got = Triple(
            frame.rgba[i].toInt() and 0xFF,
            frame.rgba[i + 1].toInt() and 0xFF,
            frame.rgba[i + 2].toInt() and 0xFF,
        )
        return kotlin.math.abs(got.first - r) < 40 &&
            kotlin.math.abs(got.second - g) < 40 &&
            kotlin.math.abs(got.third - b) < 40
    }

    @Test
    fun `a picture that changes size mid-stream keeps decoding, at the new size`() {
        Fixtures.assumeDecodeEnvironment()
        // The mpegts demuxer rides the extended-formats feature, so a core
        // bundle skips this rather than failing it.
        Fixtures.assumeFormats()
        val small = clip("small.ts", "red", "64x64")
        val large = clip("large.ts", "lime", "128x96")
        val mixed = dir.resolve("mixed.ts")
        Files.newOutputStream(mixed).use { out ->
            Files.newInputStream(small).use { it.copyTo(out) }
            Files.newInputStream(large).use { it.copyTo(out) }
        }

        val decoded = VideoDecoder.open(mixed).use { d ->
            generateSequence { d.nextFrame() }.map { Triple(it.width, it.height, centreIs(it, 237, 28, 36)) }.toList()
        }

        assertTrue(decoded.size >= 8, "both halves must decode, got ${decoded.size} frames")
        val sizes = decoded.map { it.first to it.second }
        assertTrue((64 to 64) in sizes, "the first half must decode at its own size, saw ${sizes.distinct()}")
        assertTrue((128 to 96) in sizes, "the second half must decode at the new size, saw ${sizes.distinct()}")
        // The switch happens once and forward -- not a scaler flapping between
        // two geometries, which is what a cache keyed on the wrong thing does.
        assertEquals(
            2,
            sizes.distinct().size,
            "the geometry must change exactly once, saw ${sizes.distinct()}",
        )
        assertEquals(
            listOf(64 to 64, 128 to 96),
            sizes.distinct(),
            "and in that order",
        )
    }

    @Test
    fun `the colour survives the size change on both sides of it`() {
        Fixtures.assumeDecodeEnvironment()
        Fixtures.assumeFormats()
        val small = clip("small-colour.ts", "red", "64x64")
        val large = clip("large-colour.ts", "lime", "128x96")
        val mixed = dir.resolve("mixed-colour.ts")
        Files.newOutputStream(mixed).use { out ->
            Files.newInputStream(small).use { it.copyTo(out) }
            Files.newInputStream(large).use { it.copyTo(out) }
        }

        // The half with teeth. Geometry alone would still pass against a
        // scaler reading through a buffer sized for the other side -- the
        // dimensions come from the frame, the pixels come from the memory.
        var redSeen = 0
        var greenSeen = 0
        var wrong = 0
        VideoDecoder.open(mixed).use { d ->
            generateSequence { d.nextFrame() }.forEach { f ->
                when {
                    f.width == 64 && centreIs(f, 237, 28, 36) -> redSeen++
                    f.width == 128 && centreIs(f, 0, 255, 0) -> greenSeen++
                    else -> wrong++
                }
            }
        }
        assertTrue(redSeen > 0, "the first half must come back red")
        assertTrue(greenSeen > 0, "the second half must come back green at its own size")
        assertEquals(0, wrong, "no frame may come back the wrong colour for its size")
    }
}
