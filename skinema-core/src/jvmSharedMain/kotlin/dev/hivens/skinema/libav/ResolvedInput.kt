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

    class Live(val source: MediaSource) : ResolvedInput {
        override val label: String get() = "the stream"

        override fun release() = close()

        override fun close() {
            runCatching { source.close() }
        }
    }
}

/** Resolves this input, asking a stream once whether it can seek. */
internal fun MediaInput.resolve(): ResolvedInput = when (this) {
    is MediaInput.File -> ResolvedInput.File(path)
    is MediaInput.Stream ->
        if (SharedSource.canSeek(source)) ResolvedInput.Seekable(SharedSource(source)) else ResolvedInput.Live(source)
}
