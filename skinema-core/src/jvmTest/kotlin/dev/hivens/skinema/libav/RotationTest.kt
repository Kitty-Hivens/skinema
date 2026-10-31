package dev.hivens.skinema.libav

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.assertEquals

/**
 * Pins the display-rotation sign against real files: a phone-style
 * display-matrix is the only thing that exercises [displayRotationDegrees],
 * and the CCW-report / CW-apply inversion is exactly where a sign slips by
 * 90 or 180 unnoticed. The CLI tags a generated clip with each rotation; the
 * decoder must report the clockwise degrees a consumer rotates to upright.
 */
class RotationTest {

    private val dir: Path = Files.createTempDirectory("skinema-rotation")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    // -display_rotation is counterclockwise degrees to apply before display;
    // the decoder reports the clockwise degrees a consumer rotates to upright,
    // so a 90 CCW tag is a 270 CW report. 180 is sign-independent.
    private val cases = listOf(0 to 0, 90 to 270, 180 to 180, 270 to 90)

    @TestFactory
    fun `the decoder reports the display rotation a file carries`(): List<DynamicTest> =
        cases.map { (ccw, expectedCw) ->
            DynamicTest.dynamicTest("ccw=$ccw -> cw=$expectedCw") {
                Fixtures.assumeDecodeEnvironment()
                Fixtures.assumeEncoder("libx264")
                // Two steps: the libx264 encode drops the input's display
                // matrix, so tag it onto a stream copy. -display_rotation is an
                // input option, hence before -i.
                val plain = Fixtures.generate(
                    dir.resolve("plain-$ccw.mp4"),
                    "-f", "lavfi", "-i", "testsrc2=size=128x96:rate=5", "-t", "1",
                    "-pix_fmt", "yuv420p", "-c:v", "libx264",
                )
                val out = Fixtures.generate(
                    dir.resolve("rot-$ccw.mp4"),
                    "-display_rotation", "$ccw", "-i", plain.toString(), "-c", "copy",
                )
                VideoDecoder.open(out).use { d ->
                    assertEquals(expectedCw, d.rotationDegrees(), "display_rotation ccw=$ccw")
                }
            }
        }
}
