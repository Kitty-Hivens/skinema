package dev.hivens.skinema.player

import dev.hivens.skinema.libav.DeviceFixtures
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.libav.onEmulator
import org.junit.Assume.assumeFalse
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The player on image files, which it paces itself rather than through ExoPlayer. */
class ImagePlayerDeviceTest {

    private val players = mutableListOf<VideoPlayer>()

    private fun player(
        name: String,
        loop: Boolean = false,
        audio: Boolean = false,
        startPaused: Boolean = false,
        unwatched: WhenUnwatched = WhenUnwatched.FollowSound,
        hardware: HwAccel = HwAccel.AUTO,
    ): VideoPlayer = VideoPlayer(
        path = DeviceFixtures.path(name), loop = loop, audio = audio, startPaused = startPaused,
        unwatched = unwatched, hardware = hardware,
    ).also { players += it }

    @AfterTest
    fun closeAll() = players.forEach { it.close() }

    private fun awaitTrue(timeoutMillis: Long = 5_000, what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    private fun awaitFrame(p: VideoPlayer, timeoutMillis: Long = 5_000, accept: (VideoPlayer.FrameSlot) -> Boolean = { true }): VideoPlayer.FrameSlot {
        var got: VideoPlayer.FrameSlot? = null
        awaitTrue(timeoutMillis, "a frame") {
            val f = p.acquireFrame()
            if (f != null && accept(f)) {
                got = f
                true
            } else {
                false
            }
        }
        return got!!
    }

    private fun pixel(f: VideoPlayer.FrameSlot, x: Int, y: Int): IntArray {
        val i = (y * f.width + x) * 4
        return IntArray(4) { f.rgba[i + it].toInt() and 0xFF }
    }

    private fun centre(f: VideoPlayer.FrameSlot) = pixel(f, f.width / 2, f.height / 2)

    /** Which of red, green and blue a frame's centre is, or null for anything else. */
    private fun colour(f: VideoPlayer.FrameSlot): Char? {
        val (r, g, b) = centre(f)
        return when {
            r > 200 && g < 60 && b < 60 -> 'r'
            g > 200 && r < 60 && b < 60 -> 'g'
            b > 200 && r < 60 && g < 60 -> 'b'
            else -> null
        }
    }

    private fun near(actual: Long, expected: Long, tolerance: Long = 5_000_000L) =
        assertTrue(abs(actual - expected) <= tolerance, "expected about $expected, got $actual")

    @Test
    fun anAnimationPlaysItsFramesInOrderAtTheirTimes() {
        assumeFalse("every frame at its time is a phone's GPU, not an emulated one", onEmulator())
        val p = player("rgb.gif")
        val seen = mutableListOf<Pair<Long, Char?>>()
        val start = System.nanoTime()
        awaitTrue(what = "Ended") {
            p.acquireFrame()?.let { f -> if (seen.lastOrNull()?.first != f.ptsNanos) seen += f.ptsNanos to colour(f) }
            p.state == VideoPlayer.State.Ended
        }
        val took = System.nanoTime() - start
        assertEquals(listOf<Pair<Long, Char?>>(0L to 'r', 100_000_000L to 'g', 200_000_000L to 'b'), seen)
        assertTrue(took in 250_000_000L..900_000_000L, "three tenths of a second, took $took")
        assertEquals(300_000_000L, p.durationNanos)
        near(p.positionNanos(), 300_000_000L)
        assertEquals(VideoPlayer.State.Ended, p.state)
        assertEquals(false, p.hardwareActive)
    }

    @Test
    fun aLoopingAnimationTurnsItsLapAndLoopIsLive() {
        val p = player("rgb.webp", loop = true)
        var wrapped = false
        var last = -1L
        awaitTrue(what = "a turned lap") {
            p.acquireFrame()?.let { f ->
                if (f.ptsNanos < last) wrapped = true
                last = f.ptsNanos
            }
            wrapped
        }
        assertEquals(VideoPlayer.State.Playing, p.state)
        assertTrue(p.positionNanos() < 300_000_000L, "the position wrapped: ${p.positionNanos()}")
        p.loop = false
        awaitTrue(2_000, "Ended once looping is off") { p.state == VideoPlayer.State.Ended }
        // A property write does not revive it; a seek does.
        p.loop = true
        Thread.sleep(400)
        assertEquals(VideoPlayer.State.Ended, p.state)
        p.seek(0)
        awaitTrue(what = "Playing again") { p.state == VideoPlayer.State.Playing }
    }

    @Test
    fun aStillStandsOnItsOneFrame() {
        val ended = player("still.png")
        val blue = awaitFrame(ended)
        assertEquals('b', colour(blue))
        assertEquals(64, blue.width)
        assertEquals(48, blue.height)
        awaitTrue(what = "Ended") { ended.state == VideoPlayer.State.Ended }
        assertNull(ended.durationNanos)

        val looping = player("still.jpg", loop = true)
        assertEquals('r', colour(awaitFrame(looping)))
        awaitTrue(what = "Playing") { looping.state == VideoPlayer.State.Playing }
        Thread.sleep(300)
        assertNull(looping.acquireFrame(), "nothing more to draw")
        assertEquals(0L, looping.positionNanos())
        assertEquals(VideoPlayer.State.Playing, looping.state)
        // Looping off takes effect at the next end of the file, and a still
        // is at its end: the desktop's one lap of 40 ms runs out.
        looping.loop = false
        awaitTrue(what = "Ended once looping is off") { looping.state == VideoPlayer.State.Ended }
    }

    @Test
    fun aDrawnPictureIsUprightWithStraightAlpha() {
        val split = awaitFrame(player("split-v.png"))
        val top = pixel(split, 32, 4)
        val bottom = pixel(split, 32, 44)
        assertTrue(top[0] > 200 && top[2] < 60, "red on top, got ${top.toList()}")
        assertTrue(bottom[2] > 200 && bottom[0] < 60, "blue below, got ${bottom.toList()}")

        val half = pixel(awaitFrame(player("half-red.png")), 32, 24)
        assertTrue(abs(half[3] - 128) <= 2, "half opaque, got ${half.toList()}")
        assertTrue(half[0] > 245 && half[1] < 10 && half[2] < 10, "full red under it, not darkened, got ${half.toList()}")
    }

    @Test
    fun aHardwareBitmapComesOutOfAnImageToo() {
        val p = player("still.png")
        var frame: FrameBitmap? = null
        awaitTrue(what = "a bitmap") {
            frame = p.acquireFrameBitmap()
            frame != null
        }
        frame!!.use {
            assertEquals(64, it.width)
            val px = it.copyPixels()
            val i = (24 * 64 + 32) * 4
            assertTrue((px[i + 2].toInt() and 0xFF) > 200 && (px[i].toInt() and 0xFF) < 60, "blue")
        }
        // Copied out, a translucent picture is straight, as acquireFrame's is.
        val half = player("half-red.png")
        var translucent: FrameBitmap? = null
        awaitTrue(what = "a translucent bitmap") {
            translucent = half.acquireFrameBitmap()
            translucent != null
        }
        translucent!!.use {
            val px = it.copyPixels()
            val i = (24 * 64 + 32) * 4
            val rgba = IntArray(4) { c -> px[i + c].toInt() and 0xFF }
            assertTrue(abs(rgba[3] - 128) <= 2 && rgba[0] > 245, "full red at half opacity, got ${rgba.toList()}")
        }
    }

    @Test
    fun seeksAndStepsLandOnFrames() {
        val p = player("rgb.apng", startPaused = true)
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        val first = awaitFrame(p)
        assertEquals(0L to 'r', first.ptsNanos to colour(first))

        p.seek(150_000_000L)
        val green = awaitFrame(p) { it.ptsNanos != 0L }
        assertEquals(100_000_000L to 'g', green.ptsNanos to colour(green))
        assertEquals(150_000_000L, p.positionNanos())
        assertEquals(VideoPlayer.State.Paused, p.state)

        p.stepForward()
        val blue = awaitFrame(p) { it.ptsNanos != 100_000_000L }
        assertEquals(200_000_000L to 'b', blue.ptsNanos to colour(blue))
        p.stepBackward()
        val back = awaitFrame(p) { it.ptsNanos != 200_000_000L }
        assertEquals(100_000_000L to 'g', back.ptsNanos to colour(back))
        p.stepBackward()
        val red = awaitFrame(p) { it.ptsNanos != 100_000_000L }
        assertEquals(0L to 'r', red.ptsNanos to colour(red))

        p.seek(250_000_000L, exact = false)
        assertEquals('b', colour(awaitFrame(p) { it.ptsNanos == 200_000_000L }))
        assertEquals(VideoPlayer.State.Paused, p.state)
        p.resume()
        awaitTrue(what = "Ended") { p.state == VideoPlayer.State.Ended }
    }

    @Test
    fun pauseHoldsTimeAndRateScalesIt() {
        val p = player("anim.gif")
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
        p.setRate(2f)
        Thread.sleep(100)
        val start = p.positionNanos()
        Thread.sleep(300)
        val advanced = p.positionNanos() - start
        assertTrue(advanced in 450_000_000L..750_000_000L, "advanced $advanced in 0.3 s at 2x")
        p.pause()
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        val held = p.positionNanos()
        Thread.sleep(300)
        assertEquals(held, p.positionNanos())
        p.resume()
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
        awaitTrue(what = "Ended") { p.state == VideoPlayer.State.Ended }
        assertEquals(1_000_000_000L, p.positionNanos())
    }

    @Test
    fun atTwiceTheRateEveryFrameShowsAtHalfItsTime() {
        assumeFalse("every frame at its time is a phone's GPU, not an emulated one", onEmulator())
        val p = player("anim.gif", startPaused = true)
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        // The poster frame taken first, so it cannot be counted as played: a
        // paused start publishes it once and the resume does not again.
        assertEquals(0L, awaitFrame(p).ptsNanos)
        p.setRate(2f)
        // When each frame after the first was first seen, against the resume.
        val arrived = sortedMapOf<Long, Long>()
        val start = System.nanoTime()
        p.resume()
        awaitTrue(what = "Ended") {
            p.acquireFrame()?.let { arrived.putIfAbsent(it.ptsNanos, System.nanoTime()) }
            p.state == VideoPlayer.State.Ended
        }
        assertEquals((1..9).map { it * 100_000_000L }, arrived.keys.toList(), "every frame at 2x")
        for ((pts, at) in arrived) {
            val late = (at - start) - pts / 2
            assertTrue(abs(late) < 35_000_000L, "frame $pts came ${late / 1_000_000} ms off its time at 2x")
        }
    }

    @Test
    fun aHiddenAnimationStopsWithItsPictureByDefault() {
        val p = player("anim.gif", loop = true)
        awaitFrame(p)
        p.reportVisible(false)
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        val stood = p.positionNanos()
        Thread.sleep(400)
        assertEquals(stood, p.positionNanos(), "nothing is heard, so time stops with the picture")
        p.reportVisible(true)
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
        val after = p.positionNanos()
        Thread.sleep(200)
        assertTrue(p.positionNanos() != after, "and runs again")
    }

    @Test
    fun keepTimeRunsOnWithoutFramesAndThePictureRejoinsTheClock() {
        val p = player("anim.gif", loop = true, unwatched = WhenUnwatched.KeepTime)
        awaitFrame(p)
        p.reportVisible(false)
        Thread.sleep(150)
        p.acquireFrame()
        val hiddenAt = p.positionNanos()
        Thread.sleep(500)
        assertNull(p.acquireFrame(), "no frames while hidden")
        val ran = (p.positionNanos() - hiddenAt + 1_000_000_000L) % 1_000_000_000L
        assertTrue(ran in 350_000_000L..700_000_000L, "time ran on, lap included: $ran")
        assertEquals(VideoPlayer.State.Playing, p.state)
        p.reportVisible(true)
        val back = awaitFrame(p)
        val gap = (p.positionNanos() - back.ptsNanos + 1_000_000_000L) % 1_000_000_000L
        assertTrue(gap < 200_000_000L, "rejoined at ${back.ptsNanos} against ${p.positionNanos()}")
    }

    @Test
    fun aSwitchMovesBetweenVideoAndImagesBothWays() {
        val p = player("red-h264.mp4", loop = true)
        assertEquals('r', colour(awaitFrame(p)))
        p.setSource(DeviceFixtures.path("still.png"))
        // The first frame of the new size is the image's, not the video's last one drawn again.
        assertEquals('b', colour(awaitFrame(p) { it.width == 64 }))
        assertEquals("still.png", p.source.fileName.toString())
        assertNull(p.durationNanos)
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
        p.setSource(DeviceFixtures.path("rgb.gif"))
        awaitTrue(what = "the switch") { p.source.fileName.toString() == "rgb.gif" }
        awaitFrame(p) { colour(it) == 'g' }
        // Nothing the idle ExoPlayer reports about the file it stopped overwrites the image's lap.
        assertEquals(300_000_000L, p.durationNanos)
        p.setSource(DeviceFixtures.path("green-vp9.webm"))
        assertEquals('g', colour(awaitFrame(p) { colour(it) == 'g' }))
        awaitTrue(what = "a duration from the video") { p.durationNanos != null }

        // A player that opened on an image starts ExoPlayer only when a video arrives.
        val fromImage = player("rgb.gif", loop = true)
        awaitFrame(fromImage)
        fromImage.setSource(DeviceFixtures.path("red-h264.mp4"))
        assertEquals('r', colour(awaitFrame(fromImage) { it.width == 320 }))
        awaitTrue(what = "Playing") { fromImage.state == VideoPlayer.State.Playing }
        assertNull(fromImage.sourceFailure)
    }

    @Test
    fun aFrameHeldAcrossAChangeOfSizeStaysReadable() {
        val p = player("red-h264.mp4", loop = true)
        var held: FrameBitmap? = null
        awaitTrue(what = "a bitmap") {
            held = p.acquireFrameBitmap()
            held != null
        }
        p.setSource(DeviceFixtures.path("still.png"))
        awaitFrame(p) { it.width == 64 }
        held!!.use {
            val px = it.copyPixels()
            val i = ((it.height / 2) * it.width + it.width / 2) * 4
            assertTrue((px[i].toInt() and 0xFF) > 200, "the held video frame still reads red")
        }
    }

    @Test
    fun soundAloneRefusesAnImageAndRequireRefusesOneToo() {
        val sound = player("sine-aac.m4a", audio = true, loop = true)
        awaitTrue(what = "Playing") { sound.state == VideoPlayer.State.Playing }
        sound.setSource(DeviceFixtures.path("still.png"))
        awaitTrue(what = "the refusal") { sound.sourceFailure != null }
        assertEquals("sine-aac.m4a", sound.source.fileName.toString())

        val required = player("still.png", hardware = HwAccel.REQUIRE)
        awaitTrue(what = "Failed") { required.state is VideoPlayer.State.Failed }
        val cause = assertIs<VideoPlayer.State.Failed>(required.state).cause
        assertNotNull(cause.message)
        assertTrue("REQUIRE" in cause.message!!, cause.message)
    }
}
