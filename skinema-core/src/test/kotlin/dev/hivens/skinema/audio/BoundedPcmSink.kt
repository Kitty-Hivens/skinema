package dev.hivens.skinema.audio

/**
 * [PcmSink] with a real line's blocking shape, which [FakePcmSink]'s
 * instant writes cannot model: a bounded buffer that [write] fills and
 * blocks on, and a played position that advances only when the test
 * consumes frames by hand. Built for scenarios that need the audio
 * thread genuinely stuck -- pinning the loop-wrap park open, proving
 * stopped-write invariants.
 */
class BoundedPcmSink(private val capacityFrames: Long) : PcmSink {

    private val lock = Object()
    private var writtenFrames = 0L
    private var consumedFrames = 0L
    private var released = false

    var sampleRate = 0
        private set

    /** True while a writer waits for buffer space -- the audio thread is parked. */
    @Volatile
    var writerParked = false
        private set

    override fun open(sampleRate: Int) {
        this.sampleRate = sampleRate
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        var remaining = (length / BYTES_PER_FRAME).toLong()
        synchronized(lock) {
            while (remaining > 0) {
                if (released) {
                    writerParked = false
                    writtenFrames += remaining
                    return
                }
                val free = capacityFrames - (writtenFrames - consumedFrames)
                if (free <= 0) {
                    writerParked = true
                    lock.wait(100)
                    continue
                }
                writerParked = false
                val accepted = minOf(free, remaining)
                writtenFrames += accepted
                remaining -= accepted
            }
        }
    }

    override fun stop() = Unit

    override fun start() = Unit

    /** Flush count; seek coalescing shows up as one flush per burst. */
    @Volatile
    var flushes = 0
        private set

    override fun flush() {
        synchronized(lock) {
            writtenFrames = consumedFrames
            flushes++
            lock.notifyAll()
        }
    }

    override fun framePosition(): Long = synchronized(lock) { consumedFrames }

    override fun setVolume(volume: Float) = Unit

    /** Plays out everything queued except the last [tailFrames]. */
    fun consumeAllButTail(tailFrames: Long) {
        synchronized(lock) {
            val target = writtenFrames - tailFrames
            if (target > consumedFrames) {
                consumedFrames = target
                lock.notifyAll()
            }
        }
    }

    /** Plays out at most [frames] -- a rate-limited test "DAC". */
    fun consume(frames: Long) {
        synchronized(lock) {
            val target = minOf(writtenFrames, consumedFrames + frames)
            if (target > consumedFrames) {
                consumedFrames = target
                lock.notifyAll()
            }
        }
    }

    /**
     * Lifts the bound and wakes every parked writer/drainer so the
     * pipeline can process Close; call before closing the player.
     */
    fun release() {
        synchronized(lock) {
            released = true
            lock.notifyAll()
        }
    }

    override fun close() {
        release()
    }

    private companion object {
        /** S16LE stereo: 2 bytes x 2 channels per sample frame. */
        const val BYTES_PER_FRAME = 4
    }
}
