package dev.hivens.skinema.libav

import dev.hivens.skinema.webp.Webp
import dev.hivens.skinema.webp.WebpAnimSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * Picks the [FrameSource] for a file: RIFF/WEBP goes to libwebp when its
 * bindings loaded (FFmpeg only decodes still WebP), everything else --
 * and WebP on builds without libwebpdemux -- goes to libav.
 */
object FrameSources {

    fun open(path: Path): FrameSource {
        if (Webp.available && isWebp(path)) return WebpAnimSource.open(path)
        return VideoDecoder.open(path)
    }

    private fun isWebp(path: Path): Boolean = runCatching {
        Files.newInputStream(path).use { input ->
            val head = input.readNBytes(12)
            head.size == 12 &&
                head.copyOfRange(0, 4).contentEquals(RIFF) &&
                head.copyOfRange(8, 12).contentEquals(WEBP)
        }
    }.getOrDefault(false)

    private val RIFF = "RIFF".toByteArray()
    private val WEBP = "WEBP".toByteArray()
}
