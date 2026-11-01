package dev.hivens.skinema.player

import dev.hivens.skinema.libav.AudioDecoder
import dev.hivens.skinema.libav.DeviceFixtures
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.libav.MediaInput
import dev.hivens.skinema.libav.MediaSource
import dev.hivens.skinema.libav.VideoDecoder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A [MediaSource] that reads forward only, the shape of a live stream, through
 * the Android player and decoders: Media3 reads it through a tape that keeps
 * its start, and the decoders through Media3's extractor.
 */
class LiveStreamDeviceTest {

    private class LiveSource(private val data: ByteArray) : MediaSource {
        private var pos = 0

        @Volatile
        var closed = false

        override fun read(dst: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, dst, offset, n)
            pos += n
            return n
        }

        override fun close() {
            closed = true
        }
    }

    private val players = mutableListOf<VideoPlayer>()

    @AfterTest
    fun closeAll() = players.forEach { it.close() }

    private fun live(name: String) = LiveSource(Files.readAllBytes(DeviceFixtures.path(name)))

    private fun awaitTrue(timeoutMillis: Long = 8_000, what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun centre(f: VideoPlayer.FrameSlot): IntArray {
        val i = ((f.height / 2) * f.width + f.width / 2) * 4
        return IntArray(3) { f.rgba[i + it].toInt() and 0xFF }
    }

    @Test
    fun aLiveStreamPlaysAndEndsAtItsEndLoopOrNot() {
        val source = live("live-with-sound.mkv")
        val p = VideoPlayer(MediaInput.Stream(source), loop = true, audio = true, hardware = HwAccel.AUTO).also { players += it }
        awaitTrue(what = "a frame") { p.acquireFrame() != null }
        assertFalse(p.seekable, "a forward-only stream says it cannot seek")
        awaitTrue(what = "the sound track") { p.audioTracks.isNotEmpty() }
        awaitTrue(what = "Ended, since a live stream cannot turn") { p.state == VideoPlayer.State.Ended }
    }

    @Test
    fun seeksDoNothingOnALiveStream() {
        val source = live("live-with-sound.mkv")
        val p = VideoPlayer(MediaInput.Stream(source), startPaused = true, hardware = HwAccel.AUTO).also { players += it }
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        p.seek(2_000_000_000L)
        p.seekBy(1_000_000_000L)
        p.stepBackward()
        Thread.sleep(400)
        assertEquals(VideoPlayer.State.Paused, p.state, "nothing moved the player")
        assertTrue(p.positionNanos() < 300_000_000L, "the position stayed put: ${p.positionNanos()}")
    }

    @Test
    fun aSwitchRightAfterALiveOpenWaitsForItsShapeAndIsTaken() {
        val p = VideoPlayer(MediaInput.Stream(live("green-live.webm")), loop = false, hardware = HwAccel.AUTO).also { players += it }
        // Asked before the stream's track report can have arrived: the shape a
        // switch keeps is only known from it.
        p.setSource(MediaInput.File(DeviceFixtures.path("red-h264.mp4")))
        awaitTrue(what = "the switch or its refusal") { p.source is MediaInput.File || p.sourceFailure != null }
        assertEquals(null, p.sourceFailure, "a picture player took a picture file")
        awaitTrue(what = "red") { p.acquireFrame()?.let { centre(it)[0] > 200 } == true }
    }

    @Test
    fun aSwitchGoesFromAFileToALiveStreamAndBackAndClosesIt() {
        val p = VideoPlayer(MediaInput.File(DeviceFixtures.path("red-h264.mp4")), loop = true, hardware = HwAccel.AUTO).also { players += it }
        awaitTrue(what = "the file") { p.acquireFrame() != null }
        val source = live("green-live.webm")
        val toStream = MediaInput.Stream(source)
        p.setSource(toStream)
        awaitTrue(what = "the stream taken") { p.source == toStream || p.sourceFailure != null }
        assertEquals(null, p.sourceFailure, "the stream was refused")
        awaitTrue(what = "green") { p.acquireFrame()?.let { val c = centre(it); c[1] > 200 && c[0] < 40 } == true }
        assertFalse(p.seekable)
        p.setSource(MediaInput.File(DeviceFixtures.path("red-h264.mp4")))
        awaitTrue(what = "the file again") { p.source is MediaInput.File }
        awaitTrue(what = "the stream closed") { source.closed }
    }

    @Test
    fun closingThePlayerClosesTheLiveStream() {
        val source = live("live-with-sound.mkv")
        val p = VideoPlayer(MediaInput.Stream(source), loop = true, hardware = HwAccel.AUTO)
        awaitTrue(what = "a frame") { p.acquireFrame() != null }
        p.close()
        awaitTrue(what = "the stream closed") { source.closed }
    }

    @Test
    fun theDecodersReadALiveStream() {
        val frames = VideoDecoder.open(live("green-live.webm"), HwAccel.AUTO).use { d ->
            generateSequence { d.nextFrame(convert = false) }.count()
        }
        assertEquals(15, frames, "a second at 15 fps, read forward only")
        val sound = assertNotNull(AudioDecoder.openOrNull(live("centre-5.1.ogg")), "the stream's sound")
        sound.use { a ->
            var chunks = 0
            while (a.nextChunk() != null) chunks++
            assertTrue(chunks > 0, "chunks came out of a forward-only stream")
        }
    }
}
