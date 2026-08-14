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

    fun open(path: Path, hardware: HwAccel = HwAccel.OFF): FrameSource {
        // Animated WebP rides libwebp, which has no hardware path; the
        // hardware policy applies to the libav decoder only.
        //
        // Not FFmpeg 9's own webp_anim decoder, despite it being correct on
        // frames, timestamps, variable frame durations and alpha (measured).
        // Its demuxer does not implement seeking: av_seek_frame reports
        // success and leaves the stream drained, and rewinding the byte
        // stream plus avformat_flush does not restart it either. seekTo(0) is
        // how looping works, and a looping animation is what this path is
        // mostly for.
        if (Webp.available && isWebp(path)) return WebpAnimSource.open(path)
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
