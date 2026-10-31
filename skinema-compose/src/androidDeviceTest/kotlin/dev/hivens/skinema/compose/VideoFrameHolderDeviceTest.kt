package dev.hivens.skinema.compose

import androidx.test.platform.app.InstrumentationRegistry
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.player.VideoPlayer
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class VideoFrameHolderDeviceTest {

    /** The clips skinema-core's fixtures task builds, packaged into this test APK as well. */
    private fun fixture(name: String): Path {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("skinema-fixtures").apply { mkdirs() }
        val file = File(dir, name)
        if (!file.exists()) {
            val stream = javaClass.getResourceAsStream("/fixtures/$name") ?: error("fixture $name is not in the test APK")
            stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return file.toPath()
    }

    private fun awaitTrue(timeoutMillis: Long = 5_000, what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    @Test
    fun framesArriveAsHardwareBitmapsOfTheRightSizeAndColour() {
        VideoPlayer(fixture("red-h264.mp4"), loop = true, hardware = HwAccel.AUTO).use { player ->
            val holder = VideoFrameHolder()
            awaitTrue(what = "a frame") { holder.update(player) }
            val frame = assertNotNull(holder.frameForDraw())
            assertEquals(320, frame.width)
            assertEquals(240, frame.height)
            val pixels = assertNotNull(holder.copyPixels())
            val i = ((pixels.height / 2) * pixels.width + pixels.width / 2) * 4
            val r = pixels.rgba[i].toInt() and 255
            val g = pixels.rgba[i + 1].toInt() and 255
            assertTrue(r > 230 && g < 25, "red, got $r,$g")
            holder.close()
        }
    }

    /**
     * The player lends each frame and the reader holds five images, so a
     * holder that never gave one back would stop the picture within a few
     * frames. Two seconds of looping 30 fps through the holder, drawing as a
     * surface would, take about sixty; a leak stops under five. Timed from the
     * first frame, so opening the file is not in the count.
     */
    @Test
    fun aHolderThatDrawsAsItGoesNeverStarvesThePlayer() {
        VideoPlayer(fixture("red-h264.mp4"), loop = true, hardware = HwAccel.AUTO).use { player ->
            val holder = VideoFrameHolder()
            awaitTrue(what = "the first frame") { holder.update(player) }
            var taken = 0
            val until = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < until) {
                if (holder.update(player)) taken++
                holder.frameForDraw()
                Thread.sleep(8)
            }
            assertTrue(taken >= 40, "took $taken frames in two seconds")
            holder.close()
        }
    }

    /**
     * The same, with frames taken far faster than they are drawn, which is a
     * window drawing slowly. Most frames are superseded without ever being
     * drawn, and those are the ones only the update side can give back.
     */
    @Test
    fun framesSupersededBeforeAnyDrawAreGivenBack() {
        VideoPlayer(fixture("red-h264.mp4"), loop = true, hardware = HwAccel.AUTO).use { player ->
            val holder = VideoFrameHolder()
            awaitTrue(what = "the first frame") { holder.update(player) }
            var taken = 0
            var lastDraw = 0L
            val until = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < until) {
                if (holder.update(player)) taken++
                if (System.nanoTime() - lastDraw > 200_000_000L) {
                    holder.frameForDraw()
                    lastDraw = System.nanoTime()
                }
                Thread.sleep(8)
            }
            assertTrue(taken >= 40, "took $taken frames in two seconds")
            holder.close()
        }
    }
}
