package dev.hivens.skinema.audio

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Deterministic [PcmSink] for tests: CI has no audio device, and the
 * clock/pacing logic must be provable without one. Writes are instant
 * and recorded; the played position is either everything-written
 * (default) or whatever the test dictates through [positionFrames] --
 * manual position control is how A/V sync gets proven.
 */
class FakePcmSink : PcmSink {

    var sampleRate = 0
        private set
    var stopped = false
        private set
    var volume = 1f
        private set

    /** open() calls; a track switch reopens the line. */
    var opens = 0
        private set

    /**
     * Writes that arrived while the line was stopped. A stopped line never
     * drains, so on real hardware such a write can block its thread for
     * good; the pipeline must keep this at zero.
     */
    var writesWhileStopped = 0
        private set

    /** When set, [framePosition] returns this instead of frames written. */
    val positionFrames = AtomicLong(-1)

    private val all = ByteArrayOutputStream()
    private val sinceFlush = ByteArrayOutputStream()

    val totalBytes: Int get() = synchronized(all) { all.size() }
    val bytesSinceLastFlush: Int get() = synchronized(all) { sinceFlush.size() }

    override fun open(sampleRate: Int) {
        this.sampleRate = sampleRate
        // Per the contract, open STARTS the device -- a fake that leaves
        // [stopped] untouched would let freeze-across-reopen tests pass
        // vacuously.
        stopped = false
        opens++
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        synchronized(all) {
            if (stopped) writesWhileStopped++
            all.write(data, offset, length)
            sinceFlush.write(data, offset, length)
        }
    }

    override fun stop() {
        stopped = true
    }

    override fun start() {
        stopped = false
    }

    override fun flush() {
        synchronized(all) { sinceFlush.reset() }
    }

    override fun framePosition(): Long {
        val manual = positionFrames.get()
        return if (manual >= 0) manual else (totalBytes / 4).toLong()
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun close() = Unit
}
