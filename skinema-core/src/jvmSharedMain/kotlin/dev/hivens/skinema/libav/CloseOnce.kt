package dev.hivens.skinema.libav

/**
 * A consumer's source, closed once however many of a teardown's paths reach
 * it: the contract does not ask a [MediaSource] to tolerate a second close,
 * and a reader breaking a blocked read, a switch letting go and a teardown
 * can each get there.
 */
internal class CloseOnce(private val inner: MediaSource) : MediaSource {
    @Volatile
    private var done = false

    override fun read(dst: ByteArray, offset: Int, length: Int): Int = inner.read(dst, offset, length)

    override fun seek(position: Long): Long = inner.seek(position)

    override fun size(): Long = inner.size()

    override fun close() {
        synchronized(this) {
            if (done) return
            done = true
        }
        inner.close()
    }
}

/** A reader over a stream that can be told to stop at once, without waiting for anything. */
internal interface Interruptible {
    /** Breaks every read in flight and stops new ones; the full close follows on the owner's thread. */
    fun interrupt()
}
