package dev.hivens.skinema.libav

/**
 * A pull-style RGBA frame source. [VideoDecoder] is the libav-backed
 * implementation; [dev.hivens.skinema.webp.WebpAnimSource] covers the one
 * format FFmpeg cannot (animated WebP). VideoPlayer paces whichever it
 * gets from [FrameSources] -- the pacing/mailbox layers never know which
 * demuxer feeds them.
 */
interface FrameSource : AutoCloseable {

    /**
     * Decodes and converts the next frame; null at end of stream. When
     * [target] is provided and matches the frame's RGBA size it receives
     * the pixels; otherwise an internal reused buffer backs the result.
     */
    fun nextFrame(target: ByteArray? = null): VideoDecoder.RgbaFrame?

    /**
     * Repositions at-or-before [ptsNanos]; frames then resume from that
     * point, and a caller wanting the exact target decodes forward until
     * the frame's pts reaches it (what VideoPlayer does). Also reopens a
     * drained stream, which is how looping works.
     */
    fun seekTo(ptsNanos: Long)
}
