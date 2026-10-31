package dev.hivens.skinema.player

import dev.hivens.skinema.core.MediaClock
import dev.hivens.skinema.libav.DeviceFixtures
import dev.hivens.skinema.libav.HwAccel
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class VideoPlayerDeviceTest {

    private val players = mutableListOf<VideoPlayer>()

    private fun player(
        name: String,
        loop: Boolean = false,
        audio: Boolean = false,
        startPaused: Boolean = false,
        unwatched: WhenUnwatched = WhenUnwatched.FollowSound,
        hardware: HwAccel = HwAccel.AUTO,
        explicitClock: MediaClock? = null,
    ): VideoPlayer = VideoPlayer(
        path = DeviceFixtures.path(name), loop = loop, audio = audio, startPaused = startPaused,
        unwatched = unwatched, hardware = hardware, explicitClock = explicitClock,
    ).also { players += it }

    @AfterTest
    fun closeAll() = players.forEach { it.close() }

    private fun awaitTrue(timeoutMillis: Long = 5_000, what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $what")
            Thread.sleep(10)
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

    private fun centre(f: VideoPlayer.FrameSlot): IntArray {
        val i = ((f.height / 2) * f.width + f.width / 2) * 4
        return IntArray(3) { f.rgba[i + it].toInt() and 0xFF }
    }

    @Test
    fun aFilePlaysToItsEndHandingOutFramesInOrder() {
        val p = player("red-h264.mp4")
        val first = awaitFrame(p)
        assertEquals(320, first.width)
        assertEquals(240, first.height)
        val c = centre(first)
        assertTrue(c[0] > 230 && c[1] < 25 && c[2] < 25, "red, got ${c.toList()}")
        var last = first.ptsNanos
        var rising = 0
        awaitTrue(5_000, "the end") {
            p.acquireFrame()?.let { f ->
                if (f.ptsNanos > last) rising++
                last = f.ptsNanos
            }
            p.state == VideoPlayer.State.Ended
        }
        assertTrue(rising >= 5, "frames came in rising pts order, saw $rising")
        val duration = p.durationNanos ?: fail("a duration")
        assertTrue(abs(duration - 1_000_000_000L) < 50_000_000, "duration $duration")
        assertEquals(0, p.rotationDegrees)
    }

    @Test
    fun aPausedStartShowsItsFirstFrame() {
        val p = player("red-h264.mp4", startPaused = true)
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        val poster = awaitFrame(p)
        assertTrue(poster.ptsNanos < 100_000_000, "the first frame, got ${poster.ptsNanos}")
        Thread.sleep(300)
        assertEquals(VideoPlayer.State.Paused, p.state)
        assertTrue(p.positionNanos() < 100_000_000, "time stood still: ${p.positionNanos()}")
    }

    @Test
    fun anExactSeekLandsOnTheTargetAndAnInexactOneOnTheKeyframe() {
        val p = player("long-with-sound.mp4", startPaused = true)
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        p.seek(2_500_000_000L, exact = true)
        val exact = awaitFrame(p) { abs(it.ptsNanos - 2_500_000_000L) < 40_000_000 }
        assertTrue(abs(exact.ptsNanos - 2_500_000_000L) < 40_000_000)
        awaitTrue(what = "Paused after the landing") { p.state == VideoPlayer.State.Paused }
        p.seek(2_500_000_000L, exact = false)
        val keyframe = awaitFrame(p) { it.ptsNanos != exact.ptsNanos }
        // Keyframes every 30 frames: at 0, 1, 2 and 3 seconds.
        assertTrue(abs(keyframe.ptsNanos - 2_000_000_000L) < 40_000_000 || abs(keyframe.ptsNanos - 3_000_000_000L) < 40_000_000, "keyframe at ${keyframe.ptsNanos}")
    }

    @Test
    fun aLoopingFileTurnsTheLapAndLoopIsLive() {
        val p = player("red-h264.mp4", loop = true)
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
        Thread.sleep(1_500)
        assertEquals(VideoPlayer.State.Playing, p.state)
        assertTrue(p.positionNanos() < 1_000_000_000L, "the lap turned: ${p.positionNanos()}")
        p.loop = false
        awaitTrue(3_000, "Ended once looping is off") { p.state == VideoPlayer.State.Ended }
    }

    @Test
    fun aSeekRevivesAnEndedPlayer() {
        val p = player("red-h264.mp4")
        awaitTrue(what = "Ended") { p.state == VideoPlayer.State.Ended }
        p.seek(0)
        awaitTrue(what = "Playing again") { p.state == VideoPlayer.State.Playing }
    }

    @Test
    fun rateScalesTime() {
        val p = player("long-with-sound.mp4")
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
        p.setRate(2f)
        Thread.sleep(200)
        val start = p.positionNanos()
        Thread.sleep(1_000)
        val advanced = p.positionNanos() - start
        assertEquals(2f, p.rate)
        assertTrue(advanced in 1_500_000_000L..2_500_000_000L, "advanced $advanced in a second at 2x")
    }

    @Test
    fun soundTracksAreListedOnlyWithSoundOn() {
        val silent = player("red-with-sound.mp4")
        awaitTrue(what = "Playing") { silent.state != VideoPlayer.State.Opening }
        assertTrue(silent.audioTracks.isEmpty())
        val heard = player("red-with-sound.mp4", audio = true)
        awaitTrue(what = "the track list") { heard.audioTracks.isNotEmpty() }
        assertEquals(1, heard.audioTracks.size)
        assertEquals(heard.audioTracks.single().streamIndex, heard.activeAudioTrack)
        awaitTrue(what = "the device format") { heard.activeAudioFormat != null }
        assertEquals(48_000, heard.activeAudioFormat!!.sampleRate)
    }

    @Test
    fun aFileWithNoPicturePlaysFrameless() {
        val p = player("sine-aac.m4a", audio = true)
        awaitTrue(what = "Ended") { p.state == VideoPlayer.State.Ended }
        assertNull(p.acquireFrame())
    }

    @Test
    fun anExplicitClockAndAMissingFileFailByName() {
        val clocked = player("red-h264.mp4", explicitClock = object : MediaClock {
            override val isPaused = false
            override fun start(atMediaNanos: Long) {}
            override fun pause() {}
            override fun resume() {}
            override fun seek(mediaNanos: Long) {}
            override fun mediaNanos() = 0L
        })
        awaitTrue(what = "Failed") { clocked.state is VideoPlayer.State.Failed }
        val missing = VideoPlayer(Path.of("/nonexistent/skinema.mp4")).also { players += it }
        awaitTrue(what = "Failed") { missing.state is VideoPlayer.State.Failed }
    }

    @Test
    fun freezeStopsTimeForAHiddenPictureAndTheReturnResumesIt() {
        val p = player("long-with-sound.mp4", unwatched = WhenUnwatched.Freeze)
        awaitFrame(p)
        p.reportVisible(false)
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        val stood = p.positionNanos()
        Thread.sleep(500)
        assertTrue(abs(p.positionNanos() - stood) < 50_000_000, "time stood still")
        p.reportVisible(true)
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
    }

    @Test
    fun keepTimeRunsOnWithoutPicturesAndThePictureRejoinsTheClock() {
        val p = player("long-with-sound.mp4", audio = true, unwatched = WhenUnwatched.KeepTime)
        awaitFrame(p)
        p.reportVisible(false)
        Thread.sleep(300)
        p.acquireFrame()
        val hiddenAt = p.positionNanos()
        Thread.sleep(1_000)
        assertNull(p.acquireFrame(), "no pictures while hidden")
        assertTrue(p.positionNanos() - hiddenAt > 700_000_000L, "time ran on: ${p.positionNanos() - hiddenAt}")
        assertEquals(VideoPlayer.State.Playing, p.state)
        p.reportVisible(true)
        val back = awaitFrame(p)
        assertTrue(abs(back.ptsNanos - p.positionNanos()) < 300_000_000L, "rejoined at ${back.ptsNanos} against ${p.positionNanos()}")
    }

    @Test
    fun aSwitchTakesAGoodFileAndRefusesABadOne() {
        val p = player("red-h264.mp4", loop = true)
        awaitFrame(p)
        p.setSource(Path.of("/nonexistent/skinema.mp4"))
        awaitTrue(what = "the refusal") { p.sourceFailure != null }
        assertEquals("red-h264.mp4", p.source.fileName.toString())
        assertFalse(p.state is VideoPlayer.State.Failed)
        p.setSource(DeviceFixtures.path("green-vp9.webm"))
        awaitTrue(what = "the switch") { p.source.fileName.toString() == "green-vp9.webm" }
        val green = awaitFrame(p) { val c = centre(it); c[1] > 200 && c[0] < 40 }
        assertNotNull(green)
        assertNull(p.sourceFailure)
    }

    @Test
    fun aSwitchStraightAfterConstructionKeepsThePictureShape() {
        val p = player("red-h264.mp4", loop = true)
        p.setSource(DeviceFixtures.path("green-vp9.webm"))
        awaitTrue(what = "the switch") { p.source.fileName.toString() == "green-vp9.webm" || p.sourceFailure != null }
        assertNull(p.sourceFailure, "a picture player took a picture file")
        assertNotNull(awaitFrame(p) { val c = centre(it); c[1] > 200 && c[0] < 40 })
    }

    @Test
    fun stepsMoveOneFrameEachWayAndStayPaused() {
        val p = player("long-with-sound.mp4", startPaused = true)
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        p.seek(1_500_000_000L)
        val at = awaitFrame(p) { abs(it.ptsNanos - 1_500_000_000L) < 40_000_000 }
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        p.stepForward()
        val next = awaitFrame(p) { it.ptsNanos > at.ptsNanos }
        assertTrue(abs(next.ptsNanos - at.ptsNanos - 33_333_333) < 2_000_000, "one frame on: ${next.ptsNanos - at.ptsNanos}")
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        p.stepBackward()
        val back = awaitFrame(p) { it.ptsNanos < next.ptsNanos }
        assertEquals(at.ptsNanos, back.ptsNanos)
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
    }

    @Test
    fun awaitChangeWakesOnAFrameAndCloseSettlesClosed() {
        val p = player("red-h264.mp4")
        val seen = p.changeCount
        assertTrue(p.awaitChange(seen, 3_000_000_000L) != seen)
        p.close()
        assertIs<VideoPlayer.State.Closed>(p.state)
    }

    @Test
    fun theHardwarePolicyIsHonoured() {
        val soft = player("red-h264.mp4", hardware = HwAccel.OFF)
        awaitFrame(soft)
        assertFalse(soft.hardwareActive)
        val auto = player("red-h264.mp4", hardware = HwAccel.AUTO)
        awaitFrame(auto)
        assertTrue(auto.hardwareActive, "a phone decodes H.264 on its hardware")
    }
}
