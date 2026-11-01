package dev.hivens.skinema.player

import dev.hivens.skinema.libav.DeviceFixtures
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.libav.MediaInput
import dev.hivens.skinema.libav.MediaSource
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Android player over a [MediaSource] that can seek: Media3, the
 * extractor and the retriever each read through a cursor of their own. Each
 * source fails the test if two calls into it ever overlap.
 */
class StreamPlayerDeviceTest {

    private class MemorySource(private val data: ByteArray) : MediaSource {
        private var pos = 0
        private val inside = AtomicInteger(0)
        val overlaps = AtomicInteger(0)

        @Volatile
        var closed = false

        private inline fun <T> guarded(block: () -> T): T {
            if (inside.incrementAndGet() > 1) overlaps.incrementAndGet()
            try {
                return block()
            } finally {
                inside.decrementAndGet()
            }
        }

        override fun read(dst: ByteArray, offset: Int, length: Int): Int = guarded {
            if (pos >= data.size) return@guarded -1
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, dst, offset, n)
            pos += n
            n
        }

        override fun seek(position: Long): Long = guarded {
            if (position < 0 || position > data.size) return@guarded -1L
            pos = position.toInt()
            position
        }

        override fun size(): Long = guarded { data.size.toLong() }

        override fun close() {
            closed = true
        }
    }

    private val players = mutableListOf<VideoPlayer>()

    @AfterTest
    fun closeAll() = players.forEach { it.close() }

    private fun stream(name: String) = MemorySource(Files.readAllBytes(DeviceFixtures.path(name)))

    private fun awaitTrue(timeoutMillis: Long = 5_000, what: String, condition: () -> Boolean) {
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
    fun aSeekableStreamPlaysItsPicture() {
        val source = stream("red-h264.mp4")
        val p = VideoPlayer(MediaInput.Stream(source), loop = true, hardware = HwAccel.AUTO).also { players += it }
        var frame: VideoPlayer.FrameSlot? = null
        awaitTrue(what = "a frame") { p.acquireFrame()?.also { frame = it } != null }
        assertTrue(centre(frame!!)[0] > 200, "the stream's own red")
        assertTrue(p.seekable)
        assertEquals(0, source.overlaps.get(), "calls into the stream overlapped")
    }

    @Test
    fun anExactSeekOnASeekableStreamLandsOnItsFrame() {
        val source = stream("long-with-sound.mp4")
        val p = VideoPlayer(MediaInput.Stream(source), startPaused = true, hardware = HwAccel.AUTO).also { players += it }
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        p.seek(2_500_000_000L, exact = true)
        awaitTrue(what = "the landing") { p.acquireFrame()?.let { abs(it.ptsNanos - 2_500_000_000L) < 40_000_000 } == true }
        assertEquals(0, source.overlaps.get(), "calls into the stream overlapped")
    }

    @Test
    fun aSoundTrackSwitchOnASeekableStreamTakes() {
        val source = stream("two-sounds.mp4")
        val p = VideoPlayer(MediaInput.Stream(source), loop = true, audio = true, hardware = HwAccel.AUTO).also { players += it }
        awaitTrue(what = "two sound tracks") { p.audioTracks.size == 2 }
        val second = p.audioTracks[1].streamIndex
        p.selectAudioTrack(second)
        awaitTrue(what = "the second track") { p.activeAudioTrack == second }
        assertEquals(0, source.overlaps.get(), "calls into the stream overlapped")
    }

    @Test
    fun aSwitchGoesFromAFileToAStreamAndBackAndLetsTheStreamGo() {
        val p = VideoPlayer(MediaInput.File(DeviceFixtures.path("red-h264.mp4")), loop = true, hardware = HwAccel.AUTO).also { players += it }
        awaitTrue(what = "the file") { p.acquireFrame() != null }
        val source = stream("green-vp9.webm")
        val toStream = MediaInput.Stream(source)
        p.setSource(toStream)
        awaitTrue(what = "the stream taken") { p.source == toStream || p.sourceFailure != null }
        assertEquals(null, p.sourceFailure, "the stream was refused")
        awaitTrue(what = "green") { p.acquireFrame()?.let { val c = centre(it); c[1] > 200 && c[0] < 40 } == true }
        p.setSource(MediaInput.File(DeviceFixtures.path("red-h264.mp4")))
        awaitTrue(what = "the file again") { p.source is MediaInput.File }
        awaitTrue(what = "the stream closed") { source.closed }
        assertEquals(0, source.overlaps.get(), "calls into the stream overlapped")
    }

    @Test
    fun closingThePlayerClosesTheStream() {
        val source = stream("red-h264.mp4")
        val p = VideoPlayer(MediaInput.Stream(source), loop = true, hardware = HwAccel.AUTO)
        awaitTrue(what = "a frame") { p.acquireFrame() != null }
        p.close()
        awaitTrue(what = "the stream closed") { source.closed }
    }
}
