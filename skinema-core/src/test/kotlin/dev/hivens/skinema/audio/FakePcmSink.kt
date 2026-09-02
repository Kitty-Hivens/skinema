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

    @Volatile
    var volume = 1f
        private set

    /**
     * The gain standing when the first write of the CURRENT line arrived, or
     * -1f before one has.
     *
     * What a player asked to start quiet has to get right is not the volume
     * eventually but the volume when the first sample goes in: a line set
     * afterwards has already played a chunk at whatever the device gave it.
     * Reset by [open], so a reopened line is judged on its own.
     */
    @Volatile
    var volumeAtFirstWrite = -1f
        private set

    /** open() calls; a track switch reopens the line. */
    var opens = 0
        private set

    /** flush() calls; seeks and tempo changes are observable through it. */
    @Volatile
    var flushes = 0
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

    /**
     * When set, the next [open] throws and clears the flag -- the device that
     * will not come back at the new track's rate. A track switch opens the
     * line before it commits to anything, so this is where that failure
     * lands. Off by default; [BoundedPcmSink] models the permanent version.
     */
    var failNextOpen = false

    override fun open(sampleRate: Int) {
        if (failNextOpen) {
            failNextOpen = false
            throw IllegalStateException("device refused the new rate")
        }
        this.sampleRate = sampleRate
        // Per the contract, open STARTS the device -- a fake that leaves
        // [stopped] untouched would let freeze-across-reopen tests pass
        // vacuously.
        stopped = false
        // And a fresh line counts its frames from zero. The written bytes are
        // kept, because tests assert on them across the reopen; only the
        // playhead restarts, which is what a track switch rebases against.
        openedAtFrames = (totalBytes / 4).toLong()
        volumeAtFirstWrite = -1f
        opens++
    }

    private var openedAtFrames = 0L

    /**
     * When set, the next [write] throws and clears the flag -- a line whose
     * device stopped taking sound. A real JavaSound line reports that as a
     * short count rather than a throw, and [JavaSoundSink] turns the short
     * count into this.
     */
    @Volatile
    var failNextWrite = false

    override fun write(data: ByteArray, offset: Int, length: Int) {
        if (failNextWrite) {
            failNextWrite = false
            throw IllegalStateException("the audio line took 0 of $length bytes")
        }
        synchronized(all) {
            if (volumeAtFirstWrite < 0f) volumeAtFirstWrite = volume
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
        synchronized(all) {
            sinceFlush.reset()
            flushes++
        }
    }

    /**
     * framePosition() calls. The clock samples the device for every reading,
     * from several threads, so a test that wants to know whether the player
     * still reaches into a sink it has handed back counts these rather than
     * guessing from behaviour.
     */
    val positionReads = java.util.concurrent.atomic.AtomicInteger(0)

    override fun framePosition(): Long {
        positionReads.incrementAndGet()
        val manual = positionFrames.get()
        return if (manual >= 0) manual else (totalBytes / 4).toLong() - openedAtFrames
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    /** close() calls; the pipeline must close a sink it was handed, once. */
    var closes = 0
        private set

    override fun close() {
        closes++
    }
}
