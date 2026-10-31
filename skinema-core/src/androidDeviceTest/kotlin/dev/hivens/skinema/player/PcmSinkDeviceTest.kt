package dev.hivens.skinema.player

import dev.hivens.skinema.audio.PcmFormat
import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.libav.DeviceFixtures
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

        override fun open(format: PcmFormat) {
            synchronized(offered) { offered += format }
            if (!accepts(format)) throw IllegalArgumentException("refused $format")
            synchronized(lock) {
                this.format = format
                written = 0; playedBefore = 0; running = true; startedAt = System.nanoTime()
            }
        }

        override fun write(data: ByteArray, offset: Int, length: Int) {
            val f = format ?: return
            val frames = length / f.bytesPerFrame
            while (written - played() > f.sampleRate / 10) Thread.sleep(2)
            synchronized(lock) { written += frames }
            bytes += length
        }

        override fun stop() {
            synchronized(lock) {
                if (running) { playedBefore = played(); running = false }
            }
            stops++
        }

        override fun start() {
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
