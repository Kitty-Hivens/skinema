package dev.hivens.skinema.encode

import dev.hivens.skinema.libav.LibavException
import java.nio.file.Path

/** Not implemented on Android yet: every open is refused by name. */
actual class MediaWriter private constructor() : AutoCloseable {

    actual fun writeFrame(rgba: ByteArray, ptsNanos: Long): Unit = throw unsupported()

    actual fun writeAudio(pcm: ByteArray): Unit = throw unsupported()

    actual fun finish(): Unit = throw unsupported()

    actual override fun close() {}

    actual companion object {
        actual fun open(path: Path, video: VideoEncodeConfig, audio: AudioEncodeConfig?): MediaWriter = throw unsupported()

        actual fun open(path: Path, audio: AudioEncodeConfig): MediaWriter = throw unsupported()

        private fun unsupported() = LibavException("MediaWriter is not implemented on Android yet")
    }
}
