@file:OptIn(UnstableApi::class)

package dev.hivens.skinema.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import dev.hivens.skinema.android.PcmSinkAudioSink
import dev.hivens.skinema.audio.ChannelPreference
import dev.hivens.skinema.audio.PcmFormat
import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.libav.DeviceFixtures
import java.nio.ByteBuffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A consumer's sink behind the Android player. The double plays in real time:
 * its position advances with the clock while started and freezes while
 * stopped, and a write blocks once a short buffer is full, the way a line does.
 */
class PcmSinkDeviceTest {

    private class PacedSink(private val accepts: (PcmFormat) -> Boolean = { true }) : PcmSink {
        @Volatile var format: PcmFormat? = null
        @Volatile var lastVolume = -1f
        @Volatile var bytes = 0L
        @Volatile var starts = 0
        @Volatile var stops = 0
        val offered = mutableListOf<PcmFormat>()
        private var written = 0L
        private var playedBefore = 0L
        private var startedAt = 0L
        private var running = false
        private val lock = Any()

        private fun played(): Long = synchronized(lock) {
            val f = format ?: return 0L
            val live = if (running) (System.nanoTime() - startedAt) * f.sampleRate / 1_000_000_000L else 0L
            minOf(written, playedBefore + live)
        }

        // A start or stop with no line open: a call out of the contract's order.
        @Volatile var outOfOrder = 0

        override fun open(format: PcmFormat) {
            synchronized(offered) { offered += format }
            if (!accepts(format)) throw IllegalArgumentException("refused $format")
            synchronized(lock) {
                this.format = format
                written = 0; playedBefore = 0; running = true; startedAt = System.nanoTime()
            }
            // Every new line at full gain, which the contract allows: the
            // player owes the volume to each one.
            lastVolume = 1f
        }

        override fun write(data: ByteArray, offset: Int, length: Int) {
            val f = format ?: return
            val frames = length / f.bytesPerFrame
            while (written - played() > f.sampleRate / 10) Thread.sleep(2)
            synchronized(lock) { written += frames }
            bytes += length
        }

        override fun stop() {
            if (format == null) outOfOrder++
            synchronized(lock) {
                if (running) { playedBefore = played(); running = false }
            }
            stops++
        }

        override fun start() {
            if (format == null) outOfOrder++
            synchronized(lock) {
                if (!running) { startedAt = System.nanoTime(); running = true }
            }
            starts++
        }

        override fun flush() {
            synchronized(lock) {
                playedBefore = played()
                written = playedBefore
            }
        }

        override fun framePosition(): Long = played()

        override fun setVolume(volume: Float) {
            lastVolume = volume
        }

        override fun close() {}
    }

    private val players = mutableListOf<VideoPlayer>()

    @AfterTest
    fun closeAll() = players.forEach { it.close() }

    private fun awaitTrue(timeoutMillis: Long = 5_000, what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun soundGoesToTheSinkAndTheSinkIsTheClock() {
        val sink = PacedSink()
        val p = VideoPlayer(DeviceFixtures.path("long-with-sound.mp4"), loop = false, audio = true, sink = sink, volume = 0.4f).also { players += it }
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
        awaitTrue(what = "a frame") { p.acquireFrame() != null }
        val accepted = sink.format ?: fail("the sink was opened")
        assertEquals(accepted, p.activeAudioFormat)
        assertEquals(48_000, accepted.sampleRate)
        awaitTrue(what = "the volume") { sink.lastVolume == 0.4f }
        val before = p.positionNanos()
        Thread.sleep(1_000)
        val advanced = p.positionNanos() - before
        assertTrue(advanced in 700_000_000L..1_300_000_000L, "a second of sink time moved the clock $advanced")
        assertTrue(sink.bytes > 0)
        assertEquals(0, sink.outOfOrder, "starts or stops with no line open")
    }

    @Test
    fun aSwitchToASoundOfAnotherShapeReopensTheSinkAndKeepsPlaying() {
        val sink = PacedSink()
        val p = VideoPlayer(DeviceFixtures.path("two-sounds.mp4"), loop = false, audio = true, sink = sink, volume = 0.4f).also { players += it }
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
        awaitTrue(what = "two sound tracks") { p.audioTracks.size == 2 }
        awaitTrue(what = "the first line") { sink.format?.channels == 2 }
        Thread.sleep(500)
        p.selectAudioTrack(p.audioTracks[1].streamIndex)
        // Reopened while playing: a line opened and then left stopped takes a
        // short buffer and blocks, and the clock stands with it.
        awaitTrue(what = "the second line") { sink.format?.channels == 1 }
        assertEquals(44_100, sink.format?.sampleRate)
        awaitTrue(what = "the volume on the new line") { sink.lastVolume == 0.4f }
        Thread.sleep(300)
        val before = p.positionNanos()
        Thread.sleep(800)
        val advanced = p.positionNanos() - before
        assertTrue(advanced in 500_000_000L..1_100_000_000L, "800 ms after the switch moved the clock $advanced")
    }

    @Test
    fun aRateChangeLeavesThePositionWhereItWas() {
        val sink = PacedSink()
        val p = VideoPlayer(DeviceFixtures.path("long-with-sound.mp4"), loop = false, audio = true, sink = sink).also { players += it }
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
        Thread.sleep(1_500)
        // A second and a half played at 1x, counted again at 2x, would put the
        // clock that far ahead at once.
        val before = p.positionNanos()
        p.setRate(2f)
        Thread.sleep(150)
        val faster = p.positionNanos() - before
        assertTrue(faster in 0L..700_000_000L, "150 ms around a change to 2x moved the clock $faster")
        // And counted at half speed, behind where it stood: the clock would
        // hold still until the device caught up.
        Thread.sleep(300)
        val slowFrom = p.positionNanos()
        p.setRate(0.5f)
        Thread.sleep(400)
        val slower = p.positionNanos() - slowFrom
        assertTrue(slower in 50_000_000L..600_000_000L, "400 ms around a change to 0.5x moved the clock $slower")
    }

    // -- The adapter alone, fed the way ExoPlayer feeds it ----------------------

    private val stereo48k: Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setChannelCount(2).setSampleRate(48_000).build()

    /** Hands [seconds] of silence stamped [ptsUs] to [adapter], waiting for room as ExoPlayer does. */
    private fun feed(adapter: PcmSinkAudioSink, seconds: Double, ptsUs: Long) {
        val bytes = (48_000 * seconds).toInt() * 4
        val buffer = ByteBuffer.wrap(ByteArray(bytes))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!adapter.handleBuffer(buffer, ptsUs, 1)) {
            if (System.nanoTime() > deadline) fail("the adapter took no sound for 5 s")
            Thread.sleep(5)
        }
    }

    @Test
    fun aGapInTheTimesReachesTheClockAtTheFrameItHappensOn() {
        val sink = PacedSink()
        val adapter = PcmSinkAudioSink(sink, ChannelPreference.Source) {}
        try {
            adapter.configure(AudioSink.AudioSinkConfig.Builder(stereo48k).build())
            adapter.play()
            // Half a second, then half a second more stamped a full second in:
            // the next lap of a loop whose sound is shorter than its picture.
            feed(adapter, 0.5, 0L)
            feed(adapter, 0.5, 1_000_000L)
            awaitTrue(what = "0.3 s played") { sink.framePosition() >= 14_400 }
            val before = adapter.getCurrentPositionUs(false)
            assertTrue(before in 250_000L..450_000L, "before the gap the clock read $before us")
            awaitTrue(what = "0.7 s played") { sink.framePosition() >= 33_600 }
            val after = adapter.getCurrentPositionUs(false)
            assertTrue(after in 1_150_000L..1_400_000L, "past the gap the clock read $after us")
        } finally {
            adapter.release()
        }
    }

    @Test
    fun aPlayBeforeTheFormatIsKnownLeavesTheLineRunningOnceItOpens() {
        val sink = PacedSink()
        val adapter = PcmSinkAudioSink(sink, ChannelPreference.Source) {}
        try {
            // A renderer enabled while the player plays is started before its
            // decoder has said what it puts out.
            adapter.play()
            adapter.configure(AudioSink.AudioSinkConfig.Builder(stereo48k).build())
            feed(adapter, 0.3, 0L)
            awaitTrue(what = "the device playing") { sink.framePosition() >= 4_800 }
            assertEquals(0, sink.outOfOrder, "starts or stops with no line open")
        } finally {
            adapter.release()
        }
    }

    @Test
    fun aRefusingSinkIsWalkedDownToTheFloor() {
        val sink = PacedSink { it == PcmFormat.floor(it.sampleRate) }
        val p = VideoPlayer(DeviceFixtures.path("sine-aac.m4a"), loop = false, audio = true, sink = sink).also { players += it }
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
        assertEquals(PcmFormat.floor(48_000), p.activeAudioFormat)
        assertTrue(sink.offered.size > 1, "more than one shape was offered: ${sink.offered}")
        awaitTrue(what = "Ended") { p.state == VideoPlayer.State.Ended }
    }

    @Test
    fun pauseStopsTheSinkAndResumeStartsIt() {
        val sink = PacedSink()
        val p = VideoPlayer(DeviceFixtures.path("long-with-sound.mp4"), loop = false, audio = true, sink = sink).also { players += it }
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
        Thread.sleep(300)
        // Counted from here: the adapter stops the device once right after
        // opening it, which says nothing about the pause.
        val stopsBefore = sink.stops
        p.pause()
        awaitTrue(what = "Paused") { p.state == VideoPlayer.State.Paused }
        awaitTrue(what = "the sink stopped") { sink.stops > stopsBefore }
        val stood = p.positionNanos()
        Thread.sleep(400)
        assertTrue(p.positionNanos() - stood < 60_000_000, "time stood still while paused")
        val startsBefore = sink.starts
        p.resume()
        awaitTrue(what = "the sink started") { sink.starts > startsBefore }
        awaitTrue(what = "time moving") { p.positionNanos() - stood > 200_000_000 }
    }

    @Test
    fun doubleSpeedPlaysTwiceTheMediaThroughTheSameDevice() {
        val sink = PacedSink()
        val p = VideoPlayer(DeviceFixtures.path("long-with-sound.mp4"), loop = false, audio = true, sink = sink).also { players += it }
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
        p.setRate(2f)
        Thread.sleep(400)
        val start = p.positionNanos()
        Thread.sleep(800)
        val advanced = p.positionNanos() - start
        assertTrue(advanced in 1_200_000_000L..2_000_000_000L, "800 ms at 2x moved the clock $advanced")
    }
}
