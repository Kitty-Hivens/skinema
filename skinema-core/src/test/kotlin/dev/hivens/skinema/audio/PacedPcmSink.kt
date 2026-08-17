package dev.hivens.skinema.audio

/**
 * [PcmSink] that plays in real time -- the one property of a line neither
 * other fake has. [write] blocks until the device has room for the bytes,
 * and [framePosition] advances with the wall clock at the sample rate, so
 * media time runs at the speed of sound rather than at the speed of the
 * writer. [FakePcmSink] accepts everything instantly and calls every
 * written frame played, which hides every end-of-stream timing question;
 * [BoundedPcmSink] blocks but only moves when a test hands it frames.
 *
 * Running dry is modelled too: the position stops at the last written
 * frame, and the frames written after that start playing from the moment
 * they arrive -- an underrun costs its own length, as on a device.
 *
 * What it does NOT model: a device that goes away. [release] and [close]
 * are the teardown hatch that frees a parked writer, not a yanked DAC --
 * the position stays where it was rather than vanishing with the line, and
 * a reopen after one keeps letting everything through. [BoundedPcmSink]
 * with `reopenable = false` is the double for device loss.
 */
class PacedPcmSink(private val bufferFrames: Long) : PcmSink {

    // java.lang.Object, not Any: wait/notifyAll are not on kotlin.Any.
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()

    private var written = 0L
    private var playedAtMark = 0L
    private var markWall = System.nanoTime()
    private var running = false
    private var released = false
    private var sampleRate = 0

    override fun open(sampleRate: Int) {
        synchronized(lock) {
            this.sampleRate = sampleRate
            // A fresh line starts empty and at frame position zero.
            written = 0
            playedAtMark = 0
            markWall = System.nanoTime()
            // Per the contract, open() STARTS the device. A line opened
            // after a release is a working line again, or a reopen test
            // would pass against a sink that swallows everything.
            running = true
            released = false
            lock.notifyAll()
        }
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        var remaining = (length / BYTES_PER_FRAME).toLong()
        synchronized(lock) {
            while (remaining > 0) {
                if (released) {
                    // Mark first, or the position races forward from a stale
                    // one and reports frames as played that never were.
                    mark()
                    written += remaining
                    return
                }
                mark()
                val free = bufferFrames - (written - playedAtMark)
                if (free <= 0) {
                    lock.wait(drainWaitMillis(1L - free))
                    continue
                }
                val accepted = minOf(free, remaining)
                written += accepted
                remaining -= accepted
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            mark()
            running = false
        }
    }

    override fun start() {
        synchronized(lock) {
            mark()
            running = true
            lock.notifyAll()
        }
    }

    override fun flush() {
        synchronized(lock) {
            mark()
            // What the line had accepted but not played is discarded.
            written = playedAtMark
            lock.notifyAll()
        }
    }

    override fun framePosition(): Long = synchronized(lock) { playedFrames() }

    override fun setVolume(volume: Float) = Unit

    /** Lifts the bound and wakes every parked writer so Close can be processed. */
    fun release() {
        synchronized(lock) {
            released = true
            lock.notifyAll()
        }
    }

    override fun close() = release()

    private fun playedFrames(): Long {
        if (!running || sampleRate == 0) return playedAtMark
        val advanced = playedAtMark + (System.nanoTime() - markWall) * sampleRate / 1_000_000_000L
        return if (advanced >= written) written else advanced
    }

    /**
     * Freezes the played counter at "now" so the next interval is measured
     * from here. Taken before every change to [written]: without it the
     * time a dry device spent waiting for data would count as played.
     */
    private fun mark() {
        playedAtMark = playedFrames()
        markWall = System.nanoTime()
    }

    private fun drainWaitMillis(frames: Long): Long =
        (frames * 1_000L / sampleRate.coerceAtLeast(1) + 1).coerceIn(1L, 50L)

    private companion object {
        /** S16LE stereo: 2 bytes x 2 channels per sample frame. */
        const val BYTES_PER_FRAME = 4
    }
}
