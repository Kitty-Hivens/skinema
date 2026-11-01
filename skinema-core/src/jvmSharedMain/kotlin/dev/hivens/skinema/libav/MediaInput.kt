package dev.hivens.skinema.libav

import java.nio.file.Path

/**
 * What a player plays or a transcoder reads: a file, or bytes a consumer
 * hands over.
 */
sealed interface MediaInput {

    /** A file on disk. */
    data class File(val path: Path) : MediaInput

    /**
     * Bytes from a [MediaSource]: a download, memory, segments a streaming
     * companion fetches. The reader that takes it owns it from then on and
     * closes it when it is done with it.
     */
    data class Stream(val source: MediaSource) : MediaInput
}
