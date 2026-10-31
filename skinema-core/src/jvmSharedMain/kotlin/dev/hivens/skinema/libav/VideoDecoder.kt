package dev.hivens.skinema.libav

import java.nio.file.Path

/**
 * One open video file, pull-style via [nextFrame]: the best video stream,
 * decoded and converted to RGBA8888. Blocking calls, owned by the thread
 * that opened it. The RGBA is the same whichever decoder produced it.
 *
 * Each platform decodes through its own engine, and its contract is
 * documented on that platform's implementation.
 */
expect class VideoDecoder : FrameSource {

    val streamIndex: Int
    val timeBaseNum: Int
    val timeBaseDen: Int

    /** One decoded frame. The [rgba] array is reused and valid until the next [nextFrame]. */
    class RgbaFrame {
        val width: Int
        val height: Int

        /** Presentation time in nanoseconds from stream start. */
        val ptsNanos: Long

        /** RGBA8888, row-major, tightly packed (stride = width * 4). */
        val rgba: ByteArray
    }

    override fun nextFrame(target: ByteArray?, convert: Boolean): RgbaFrame?
    override fun convertLast(target: ByteArray?): RgbaFrame
    override fun seekTo(ptsNanos: Long)
    override fun seekBefore(ptsNanos: Long)
    override fun durationNanos(): Long?
    override fun tags(): Map<String, String>
    override fun chapters(): List<Chapter>
    override fun coverArt(): ByteArray?
    override fun rotationDegrees(): Int
    override fun subtitleTracks(): List<SubtitleTrack>
    override fun videoSize(): Pair<Int, Int>?
    override fun hardwareActive(): Boolean
    override fun captionBytes(): ByteArray?
    override fun close()

    companion object {
        /** Opens [path] and prepares a decoder for its best video stream. */
        fun open(path: Path, hardware: HwAccel = HwAccel.OFF): VideoDecoder

        /** The same, over a custom byte [source]: the decoder pulls bytes through its callbacks. */
        fun open(source: MediaSource, hardware: HwAccel = HwAccel.OFF): VideoDecoder
    }
}
