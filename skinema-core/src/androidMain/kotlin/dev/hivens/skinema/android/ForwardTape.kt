package dev.hivens.skinema.android

import dev.hivens.skinema.libav.MediaSource
import java.io.IOException

/**
 * A stream that reads forward only, made readable more than once near its
 * start.
 *
 * Several readers look at a stream before it plays and each starts at byte
 * zero: Media3 sniffs the format, a switch probes the file in a throwaway
 * player, and the player then prepares for real. So the tape records the
 * stream's first [capBytes] as they go past and serves any reader inside
 * them from memory. Past that, a reader can only be where the stream is: the
 * one that is reading it on. A reader asked for an offset behind that and
 * past the recording is refused with an [IOException], which Media3 turns
 * into a failure, and that is the right answer for a forward-only stream
 * asked to go back.
 *
 * Calls into the source never overlap: every read takes the tape's lock.
 * [close] does not, since closing the source is how a read blocked inside it
 * is broken out of.
 */
internal class ForwardTape(private val source: MediaSource, private val capBytes: Int = DEFAULT_CAP_BYTES) : AutoCloseable {

    private val lock = Any()

    // Guarded by [lock].
    private var recorded = ByteArray(INITIAL_BYTES)
    private var recordedLength = 0
    private var recording = true
    private var edge = 0L
    private var ended = false

    @Volatile
    private var closed = false

    /** A reader of its own, starting at byte zero. Closing it does nothing. */
    fun reader(): MediaSource = Reader()

    override fun close() {
        if (closed) return
        closed = true
        runCatching { source.close() }
    }

    private inner class Reader : MediaSource {
        private var at = 0L

        override fun read(dst: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            synchronized(lock) {
                if (closed) throw IOException("the stream was closed")
                if (at < recordedLength) {
                    val n = minOf(length.toLong(), recordedLength - at).toInt()
                    System.arraycopy(recorded, at.toInt(), dst, offset, n)
                    at += n
                    return n
                }
                if (at != edge) {
                    throw IOException(
                        "the stream reads forward only and was asked for offset $at, " +
                            "behind where it is ($edge) and past the $recordedLength bytes kept of its start",
                    )
                }
                if (ended) return -1
                val n = source.read(dst, offset, length)
                if (n < 0) {
                    ended = true
                    return -1
                }
                if (recording) record(dst, offset, n)
                edge += n
                at += n
                return n
            }
        }

        override fun seek(position: Long): Long = synchronized(lock) {
            if (position < 0) return -1
            if (position <= recordedLength || position == edge) {
                at = position
                position
            } else {
                -1
            }
        }
    }

    private fun record(bytes: ByteArray, offset: Int, n: Int) {
        if (recordedLength + n > capBytes) {
            recording = false
            return
        }
        if (recordedLength + n > recorded.size) {
            recorded = recorded.copyOf(minOf(maxOf(recorded.size * 2, recordedLength + n), capBytes))
        }
        System.arraycopy(bytes, offset, recorded, recordedLength, n)
        recordedLength += n
    }

    companion object {
        /** Enough for any header and a probe's sniffing, and a fixed cost per stream. */
        const val DEFAULT_CAP_BYTES = 8 shl 20
        private const val INITIAL_BYTES = 64 * 1024
    }
}
