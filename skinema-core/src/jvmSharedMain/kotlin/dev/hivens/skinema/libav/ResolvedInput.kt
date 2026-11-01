package dev.hivens.skinema.libav

import java.nio.file.Path

/**
 * A [MediaInput] as the readers open it: a file each side opens on its own, a
 * seekable stream each side reads through a cursor, or a stream that reads
 * forward only and has to be demuxed once.
 *
 * The owner lets go of it with [release] when it moves off it, and [close]s
 * it at a teardown. [interrupt] is the first thing a teardown does, from
 * whatever thread asks for the close: it breaks a read blocked inside the
 * consumer's source at once and waits for nothing, so a stalled stream cannot
 * hold the threads reading it. For a file all three do nothing.
 */
internal sealed interface ResolvedInput {

    /** What a refusal names it by. */
    val label: String

    fun release() {}

    fun close() {}

    fun interrupt() {}

    class File(val path: Path) : ResolvedInput {
        override val label: String get() = path.toString()
    }

    class Seekable(val shared: SharedSource) : ResolvedInput {
        override val label: String get() = "the stream"

        override fun release() = shared.release()

        override fun close() = shared.close()

        // Closing the source is all a break needs here, and it takes no lock.
        override fun interrupt() = shared.close()
    }

    /**
     * A stream that reads forward only. It can be read once, so the platform
     * opens one reader over it ([attach]) that every side shares, on the
     * first side's call, and the others get the same one.
     */
    class Live(source: MediaSource) : ResolvedInput {
        override val label: String get() = "the stream"

        /** The consumer's source, closed once whichever path gets there first. */
        val source: MediaSource = CloseOnce(source)

        private val lock = Any()

        @Volatile
        private var attached: AutoCloseable? = null

        // Why the one open failed. A failed open has read part of the stream
        // and closed it, so a second side trying again would only fail on what
        // is left with a less useful error: it gets the first one.
        @Volatile
        private var failure: Throwable? = null

        @Volatile
        private var closed = false

        /** The one reader over [source], opened by [open] on the first call. */
        @Suppress("UNCHECKED_CAST")
        fun <T : AutoCloseable> attach(open: (MediaSource) -> T): T = synchronized(lock) {
            if (closed) throw LibavException("the stream was closed")
            failure?.let { throw it }
            (attached as T?)?.let { return it }
            val opened = try {
                open(source)
            } catch (t: Throwable) {
                failure = t
                throw t
            }
            // Published before the check, as [close] writes its flag before it
            // reads this: whichever runs second sees the other, so a close
            // landing while the reader opened (the header read can block) never
            // closes only the source and leaves the reader open.
            attached = opened
            if (closed) {
                runCatching { opened.close() }
                throw LibavException("the stream was closed")
            }
            opened
        }

        /** The reader, when one has been opened. */
        fun attachedOrNull(): AutoCloseable? = attached

        override fun release() = close()

        /**
         * Without the lock, which an open blocked inside the source holds:
         * closing the source is what breaks that read out of it.
         */
        override fun close() {
            if (closed) return
            closed = true
            val reader = attached
            if (reader != null) runCatching { reader.close() } else runCatching { source.close() }
        }

        override fun interrupt() {
            (attached as? Interruptible)?.interrupt()
            runCatching { source.close() }
        }
    }
}

/**
 * Resolves this input, asking a stream once whether it can seek. A stream
 * that throws when asked is closed before the throw goes on: whoever was
 * handed it owns it, and nothing else will.
 */
internal fun MediaInput.resolve(): ResolvedInput = when (this) {
    is MediaInput.File -> ResolvedInput.File(path)
    is MediaInput.Stream -> {
        val seekable = try {
            SharedSource.canSeek(source)
        } catch (t: Throwable) {
            runCatching { source.close() }
            throw t
        }
        if (seekable) ResolvedInput.Seekable(SharedSource(source)) else ResolvedInput.Live(source)
    }
}

/** Closes the stream behind an input nothing is going to resolve: a switch dropped or refused before it opened. */
internal fun MediaInput.discard() {
    if (this is MediaInput.Stream) runCatching { source.close() }
}
