package dev.hivens.skinema.player

import dev.hivens.skinema.core.MediaClock
import dev.hivens.skinema.libav.DeviceFixtures
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.libav.hasHardwareDecoder
import dev.hivens.skinema.libav.onEmulator
import org.junit.Assume.assumeFalse
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
        val clockCause = assertIs<VideoPlayer.State.Failed>(clocked.state).cause
        assertTrue("explicitClock" in clockCause.message.orEmpty(), "the refusal names the parameter: ${clockCause.message}")
        val missing = VideoPlayer(Path.of("/nonexistent/skinema.mp4")).also { players += it }
        awaitTrue(what = "Failed") { missing.state is VideoPlayer.State.Failed }
    }

    @Test
    fun aFailedPlayerTakesNoMoreFilesAndKeepsItsFailureThroughTheClose() {
        // With sound on, so the file switched to below is one this player's
        // shape takes, and only the failure can refuse it.
        val missing = VideoPlayer(Path.of("/nonexistent/skinema.m4a"), audio = true).also { players += it }
        awaitTrue(what = "Failed") { missing.state is VideoPlayer.State.Failed }
        // The desktop's thread has ended by now, and a switch finds nothing to run it.
        missing.setSource(DeviceFixtures.path("sine-aac.m4a"))
        Thread.sleep(1_500)
        assertEquals("skinema.m4a", missing.source.fileName.toString(), "the switch was not taken")
        assertTrue(missing.audioTracks.isEmpty(), "nothing plays after the failure")
        assertIs<VideoPlayer.State.Failed>(missing.state)
        missing.close()
        assertIs<VideoPlayer.State.Failed>(missing.state, "the failure is what a consumer polls for, closed or not")
    }

    @Test
    fun aSoundFileWithSoundOffFails() {
        val p = player("sine-aac.m4a", audio = false)
        awaitTrue(what = "Failed") { p.state is VideoPlayer.State.Failed }
    }

    @Test
    fun aSoundPlayerKeepsItsShapeThroughAVideo() {
        val p = player("sine-aac.m4a", audio = true, loop = true)
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
        p.setSource(DeviceFixtures.path("red-with-sound.mp4"))
        awaitTrue(what = "the switch") { p.source.fileName.toString() == "red-with-sound.mp4" }
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
        Thread.sleep(500)
        assertNull(p.acquireFrame(), "a sound player plays a video frameless")
        // Still a sound player, so a sound file is taken.
        p.setSource(DeviceFixtures.path("chapters.m4a"))
        awaitTrue(what = "the second switch") { p.source.fileName.toString() == "chapters.m4a" || p.sourceFailure != null }
        assertNull(p.sourceFailure, "a sound player took a sound file")
    }

    @Test
    fun freezeStopsTimeForAHiddenPictureAndTheReturnResumesIt() {
        val p = player("long-with-sound.mp4", unwatched = WhenUnwatched.Freeze)
        awaitFrame(p)
        p.reportVisible(false)
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        // Read the moment the state says Paused: the clock stops with it, not
        // when ExoPlayer later reports that it stopped. A frame already on its
        // way still lands and sets the position to itself, so a frame's worth
        // of movement is allowed.
        val stood = p.positionNanos()
        Thread.sleep(500)
        assertTrue(abs(p.positionNanos() - stood) < 50_000_000, "time stood still from the pause on: moved ${(p.positionNanos() - stood) / 1_000_000} ms")
        p.reportVisible(true)
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
    }

    @Test
    fun aSwitchWhileFrozenForAHiddenPictureStaysStopped() {
        val p = player("long-with-sound.mp4", loop = true, unwatched = WhenUnwatched.Freeze)
        awaitFrame(p)
        p.reportVisible(false)
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        p.setSource(DeviceFixtures.path("red-with-sound.mp4"))
        awaitTrue(what = "the switch") { p.source.fileName.toString() == "red-with-sound.mp4" }
        awaitTrue(what = "the new file open") { p.state != VideoPlayer.State.Opening }
        val stood = p.positionNanos()
        Thread.sleep(500)
        assertEquals(VideoPlayer.State.Paused, p.state, "nobody is watching the new file either")
        assertTrue(abs(p.positionNanos() - stood) < 50_000_000, "time stood still")
        p.reportVisible(true)
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
    }

    @Test
    fun aPictureKeptOffForAHiddenVideoComesBackAfterAnImageInBetween() {
        val p = player("long-with-sound.mp4", loop = true, unwatched = WhenUnwatched.KeepTime)
        awaitFrame(p)
        p.reportVisible(false)
        Thread.sleep(200)
        p.setSource(DeviceFixtures.path("rgb.gif"))
        awaitTrue(what = "the image") { p.source.fileName.toString() == "rgb.gif" }
        p.reportVisible(true)
        awaitFrame(p) { it.width == 64 }
        p.setSource(DeviceFixtures.path("red-h264.mp4"))
        awaitTrue(what = "the video") { p.source.fileName.toString() == "red-h264.mp4" }
        // Watched again, so the video's picture is on.
        val red = awaitFrame(p) { it.width == 320 }
        assertTrue(centre(red)[0] > 200, "the video's own picture")
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
        // A step back decodes toward the shown frame on a second decoder beside
        // the player's. The emulator's host-side decoder loses its parameter
        // sets when two run at once and the player's then stalls; a phone's
        // do not. The fallback path is covered there by the test that makes
        // the discovery fail.
        assumeFalse("the emulator's decoder does not run two instances reliably", onEmulator())
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
    fun aStepBackWhoseDiscoveryFailsStillLandsOnThePreviousFrame() {
        val p = player("long-with-sound.mp4", startPaused = true)
        // The decode that finds the previous frame can fail (a codec the
        // device will not open a second of, a file gone): the step then falls
        // back to the track's rate, and still moves.
        p.previousFrameFinder = { _, _ -> error("no decoder for the discovery") }
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        p.seek(1_500_000_000L)
        val at = awaitFrame(p) { abs(it.ptsNanos - 1_500_000_000L) < 40_000_000 }
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        p.stepBackward()
        val back = awaitFrame(p) { it.ptsNanos < at.ptsNanos }
        assertTrue(abs(at.ptsNanos - back.ptsNanos - 33_333_333) < 2_000_000, "one frame back: ${at.ptsNanos - back.ptsNanos}")
    }

    @Test
    fun quickStepsAreTakenOneFrameEachInTheOrderAsked() {
        // A step back decodes toward the shown frame on a second decoder beside
        // the player's. The emulator's host-side decoder loses its parameter
        // sets when two run at once and the player's then stalls; a phone's
        // do not. The fallback path is covered there by the test that makes
        // the discovery fail.
        assumeFalse("the emulator's decoder does not run two instances reliably", onEmulator())
        val p = player("long-with-sound.mp4", startPaused = true)
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        p.seek(1_500_000_000L)
        val at = awaitFrame(p) { abs(it.ptsNanos - 1_500_000_000L) < 40_000_000 }
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        // Two presses before the first lands: two frames on, and the
        // position is the frame on screen.
        p.stepForward()
        p.stepForward()
        val two = lastFrameWithin(p, 1_500) ?: fail("frames after two steps")
        assertTrue(abs(two - at.ptsNanos - 66_666_666) < 2_000_000, "two frames on: ${two - at.ptsNanos}")
        assertTrue(abs(p.positionNanos() - two) < 2_000_000, "the position is the frame shown: ${p.positionNanos() - two}")
        // Back, then forward, straight after each other: where it started.
        p.stepBackward()
        p.stepForward()
        val again = lastFrameWithin(p, 2_000) ?: fail("frames after back and forth")
        assertEquals(two, again, "back and forth lands where it was")
        // A pause on a player standing on a frame leaves the position on it.
        p.pause()
        Thread.sleep(200)
        assertTrue(abs(p.positionNanos() - again) < 2_000_000, "the position after a pause is the frame shown: ${p.positionNanos() - again}")
    }

    @Test
    fun aStepBackPressedWhilePlayingLandsOneFrameBehindTheLastShown() {
        assumeFalse("the emulator's decoder does not run two instances reliably", onEmulator())
        val p = player("long-with-sound.mp4")
        awaitFrame(p)
        Thread.sleep(1_000)
        // Every frame published from here on, the one still on its way when
        // the press pauses the player among them.
        var highest = 0L
        var last = 0L
        val until = System.nanoTime() + 300_000_000L
        while (System.nanoTime() < until) {
            p.acquireFrame()?.let { highest = maxOf(highest, it.ptsNanos); last = it.ptsNanos }
            Thread.sleep(5)
        }
        p.stepBackward()
        // Waited for rather than timed: the step back decodes toward the frame
        // on a second decoder first, which takes longer on a busy device.
        val end = System.nanoTime() + 6_000_000_000L
        var settledAt = Long.MAX_VALUE
        while (System.nanoTime() < minOf(end, settledAt)) {
            p.acquireFrame()?.let {
                highest = maxOf(highest, it.ptsNanos)
                last = it.ptsNanos
                if (last < highest && settledAt == Long.MAX_VALUE) settledAt = System.nanoTime() + 500_000_000L
            }
            Thread.sleep(5)
        }
        assertEquals(VideoPlayer.State.Paused, p.state)
        assertTrue(abs(highest - last - 33_333_333) < 2_000_000, "one frame behind the last shown: landed ${(highest - last) / 1_000} us behind")
    }

    @Test
    fun aStepAskedForWhileASeekLandsStepsFromWhereTheSeekLanded() {
        assumeFalse("the emulator's decoder does not run two instances reliably", onEmulator())
        val p = player("long-with-sound.mp4", startPaused = true)
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        awaitFrame(p)
        p.seek(2_000_000_000L)
        p.stepBackward()
        val landed = lastFrameWithin(p, 3_000) ?: fail("frames after the seek and the step")
        assertTrue(abs(landed - (2_000_000_000L - 33_333_333)) < 2_000_000, "one frame before the seek's frame: $landed")
    }

    @Test
    fun aStepForwardOffTheLastFrameEndsAndTheNextStepStillGoes() {
        val p = player("red-h264.mp4", startPaused = true)
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        p.seek(970_000_000L)
        val lastFrame = awaitFrame(p) { it.ptsNanos > 900_000_000L }
        awaitTrue(what = "Paused on the last frame") { p.state == VideoPlayer.State.Paused }
        // Nothing after the last frame: the seek runs off the end without a
        // READY, and the queue must not wait on it.
        p.stepForward()
        awaitTrue(3_000, "the step off the end to settle") { p.state != VideoPlayer.State.Seeking && p.state != VideoPlayer.State.Opening }
        p.seek(500_000_000L)
        val back = awaitFrame(p) { it.ptsNanos < lastFrame.ptsNanos }
        p.stepForward()
        val next = awaitFrame(p) { it.ptsNanos > back.ptsNanos }
        assertTrue(abs(next.ptsNanos - back.ptsNanos - 33_333_333) < 2_000_000, "a later step still goes: ${next.ptsNanos - back.ptsNanos}")
    }

    @Test
    fun aSwitchToAContainerOnlyMedia3ReadsIsTaken() {
        val p = player("red-h264.mp4", loop = true)
        awaitFrame(p)
        // FLV plays through Media3, and the platform extractor does not open
        // it: a switch checked with the platform's refused it.
        p.setSource(DeviceFixtures.path("red.flv"))
        awaitTrue(what = "the switch or its refusal") { p.source.fileName.toString() == "red.flv" || p.sourceFailure != null }
        assertNull(p.sourceFailure, "a file the player plays was taken")
        assertTrue(centre(awaitFrame(p))[0] > 200, "the FLV's own red")
    }

    /** The pts of the last frame published within [millis], or null when none was. */
    private fun lastFrameWithin(p: VideoPlayer, millis: Long): Long? {
        var last: Long? = null
        val until = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < until) {
            p.acquireFrame()?.let { last = it.ptsNanos }
            Thread.sleep(5)
        }
        return last
    }

    @Test
    fun aSideLoadedSubtitleFileKeepsTheSoundTrackChosenBeforeIt() {
        val p = player("two-sounds.mp4", loop = true, audio = true)
        awaitTrue(what = "two sound tracks") { p.audioTracks.size == 2 }
        val second = p.audioTracks[1].streamIndex
        p.selectAudioTrack(second)
        awaitTrue(what = "the second track") { p.activeAudioTrack == second }
        // Attached by preparing the item again, which renames every track
        // group; the choice made before is made again on the new ones.
        val added = p.addExternalSubtitles(DeviceFixtures.path("subs.srt"))
        assertEquals(1, added.size)
        awaitTrue(what = "the prepare to land") { p.state == VideoPlayer.State.Playing }
        Thread.sleep(1_500)
        assertEquals(second, p.activeAudioTrack, "the chosen sound track after the side-load")
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
        // AUTO takes the hardware decoder where there is one (every phone,
        // for H.264) and says software where there is none (an emulator).
        assertEquals(hasHardwareDecoder("video/avc"), auto.hardwareActive, "AUTO and what this device has")
        // AV1 in hardware is still rare on phones: REQUIRE either decodes it
        // there or fails by name, and never plays on without a picture.
        val required = player("blue-av1.mp4", hardware = HwAccel.REQUIRE)
        awaitTrue(what = "a picture or the failure") { required.state is VideoPlayer.State.Failed || required.acquireFrame() != null }
        val state = required.state
        if (state is VideoPlayer.State.Failed) {
            assertTrue("REQUIRE" in state.cause.message.orEmpty(), "the failure names the policy: ${state.cause.message}")
        } else {
            assertTrue(required.hardwareActive)
        }
        val software = player("blue-av1.mp4", hardware = HwAccel.AUTO)
        assertTrue(centre(awaitFrame(software))[2] > 200, "AUTO falls back to software")
    }
}
