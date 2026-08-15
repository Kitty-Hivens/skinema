package dev.hivens.skinema.libav

import java.nio.file.Files
import java.nio.file.Path

/**
 * Picks the [FrameSource] for a file. One implementation covers every
 * format now: FFmpeg 9 decodes animated WebP itself, so the libwebp stack
 * the animated path used to need is gone from the bundles.
 */
object FrameSources {

    fun open(path: Path, hardware: HwAccel = HwAccel.OFF): FrameSource {
        return VideoDecoder.open(path, hardware)
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
