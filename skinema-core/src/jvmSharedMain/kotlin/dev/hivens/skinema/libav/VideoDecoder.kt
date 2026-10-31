package dev.hivens.skinema.libav

import java.nio.file.Path

/**
 * One open video file, pull-style via [nextFrame]: the best video stream,
 * decoded and converted to RGBA8888. Blocking calls, owned by the thread that
 * opened it. The colour handling is the same on both platforms (the declared
 * matrix and range, BT.709 for HD that declares nothing, HDR tone-mapped to
 * SDR by the same curve), and the RGBA is the same whichever decoder produced
 * it. A file with no picture throws [NoVideoStreamException]; any other
 * refusal throws [LibavException].
 *
 * On the desktop this is FFmpeg, with the hardware decode [HwAccel] asks for.
 * On Android it is the platform extractor and a MediaCodec decoder, converted
 * on the CPU; timestamps are the extractor's microseconds, so the time base
 * is 1/1000000. Android gives no closed captions ([captionBytes] is null),
 * and a [MediaSource] there must be able to seek, because the platform
 * extractor reads by offset. The platform reads no chapters, so this library
 * does: Matroska and WebM chapters, and MP4 and QuickTime chapter tracks or
 * Nero lists; other containers have none on Android.
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
