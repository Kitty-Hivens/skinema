package dev.hivens.skinema.libav

import java.nio.file.Path

/**
 * A [MediaInput] as the readers open it: a file each side opens on its own, a
 * seekable stream each side reads through a cursor, or a stream that reads
 * forward only and has to be demuxed once.
 *
 * The owner lets go of it with [release] when it moves off it, and [close]s
 * it at a teardown. For a file both do nothing.
 */
internal sealed interface ResolvedInput {

    /** What a refusal names it by. */
    val label: String

    fun release() {}

    fun close() {}

    class File(val path: Path) : ResolvedInput {
        override val label: String get() = path.toString()
    }

    class Seekable(val shared: SharedSource) : ResolvedInput {
        override val label: String get() = "the stream"

        override fun release() = shared.release()

        override fun close() = shared.close()
    }

    /**
     * A stream that reads forward only. It can be read once, so the platform
     * opens one reader over it ([attach]) that every side shares, on the
     * first side's call, and the others get the same one.
     */
    class Live(val source: MediaSource) : ResolvedInput {
        override val label: String get() = "the stream"

        private val lock = Any()

        @Volatile
        private var attached: AutoCloseable? = null

        @Volatile
        private var closed = false

        /** The one reader over [source], opened by [open] on the first call. */
        @Suppress("UNCHECKED_CAST")
        fun <T : AutoCloseable> attach(open: (MediaSource) -> T): T = synchronized(lock) {
            if (closed) throw LibavException("the stream was closed")
            (attached as T?)?.let { return it }
            val opened = open(source)
            // A close that came while the reader was opening (it reads the
            // header, which can block) finds nothing to close but the source,
            // so the reader is closed here instead of handed out.
            if (closed) {
                runCatching { opened.close() }
                throw LibavException("the stream was closed")
            }
            attached = opened
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
    }
}

/** Resolves this input, asking a stream once whether it can seek. */
internal fun MediaInput.resolve(): ResolvedInput = when (this) {
    is MediaInput.File -> ResolvedInput.File(path)
    is MediaInput.Stream ->
        if (SharedSource.canSeek(source)) ResolvedInput.Seekable(SharedSource(source)) else ResolvedInput.Live(source)
}
