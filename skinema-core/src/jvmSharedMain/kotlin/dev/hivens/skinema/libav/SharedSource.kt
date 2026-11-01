package dev.hivens.skinema.libav

import java.io.IOException

/**
 * One seekable [MediaSource] read by several readers at once, each through a
 * cursor of its own.
 *
 * A file can be opened by every side that needs it, and each open has its
 * own position. A source has one position, so the cursors take turns: under
 * one lock, a cursor puts the source where it last left off and reads. That
 * is what lets the picture, the sound and the subtitles each run their own
 * demuxer over a stream exactly as they do over a file, and it keeps the
 * promise that calls into one source are never concurrent.
 *
 * A cursor's seek moves only the cursor. The source is moved at the next
 * read, and a source that then lands somewhere else fails that read rather
 * than handing over bytes from the wrong place.
 *
 * Two ways to let go of it. [release] is the owner saying it is done: the
 * source is closed once the last cursor has been closed too, so a side still
 * winding down after a switch is not cut off mid-read. [close] closes it now,
 * without the lock, which is how a teardown breaks a read blocked inside the
 * source out of it.
 */
internal class SharedSource(private val source: MediaSource) : AutoCloseable {

    private val lock = Any()

    // Where the source itself stands. Guarded by [lock].
    private var position = 0L

    // Cursors handed out and not yet closed, and whether the owner let go.
    // Guarded by [counts].
    private val counts = Any()
    private var openCursors = 0
    private var released = false

    @Volatile
    private var closed = false

    fun cursor(): MediaSource {
        synchronized(counts) { openCursors++ }
        return Cursor()
    }

    /** The owner is done: the source closes when the last cursor does. */
    fun release() {
        val last = synchronized(counts) {
            released = true
            openCursors == 0
        }
        if (last) close()
    }

    override fun close() {
        synchronized(counts) {
            if (closed) return
            closed = true
        }
        runCatching { source.close() }
    }

    private fun cursorClosed() {
        val last = synchronized(counts) {
            openCursors--
            released && openCursors == 0
        }
        if (last) close()
    }

    private inner class Cursor : MediaSource {
        // This reader's own position. A cursor belongs to one reader on one
        // thread, so it needs no lock of its own.
        private var at = 0L
        private var done = false

        override fun read(dst: ByteArray, offset: Int, length: Int): Int {
            synchronized(lock) {
                if (closed) throw IOException("the source was closed")
                if (position != at) {
                    val landed = source.seek(at)
                    if (landed != at) throw IOException("the source was asked to move to $at and answered $landed")
                    position = at
                }
                val n = source.read(dst, offset, length)
                if (n > 0) {
                    at += n
                    position += n
                }
                return n
            }
        }

        override fun seek(position: Long): Long {
            if (position < 0) return -1
            at = position
            return position
        }

        override fun size(): Long = synchronized(lock) { source.size() }

        override fun close() {
            if (done) return
            done = true
            cursorClosed()
        }
    }

    companion object {
        /**
         * Whether [source] can be read from more than one place, asked once
         * before anything reads it: a source at its start that answers a seek
         * to zero with zero can seek. One that answers -1 reads forward only.
         */
        fun canSeek(source: MediaSource): Boolean = source.seek(0) == 0L
    }
}
