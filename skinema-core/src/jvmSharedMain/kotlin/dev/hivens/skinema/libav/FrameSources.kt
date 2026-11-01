package dev.hivens.skinema.libav

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
}
