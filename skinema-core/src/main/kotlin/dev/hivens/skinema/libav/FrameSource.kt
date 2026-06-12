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
     *
     * With [convert] false the frame is decoded but not converted: the
     * result carries pts and dimensions over an empty rgba. That is the
     * seek landing's drop-run -- converting frames that are thrown away
     * costs several times the bare decode. [convertLast] materializes the
     * frame that turned out to be the landing.
     */
    fun nextFrame(target: ByteArray? = null, convert: Boolean = true): VideoDecoder.RgbaFrame?

    /** Converts the most recent [nextFrame] result (either convert mode). */
    fun convertLast(target: ByteArray? = null): VideoDecoder.RgbaFrame

    /**
     * Repositions at-or-before [ptsNanos]; frames then resume from that
     * point, and a caller wanting the exact target decodes forward until
     * the frame's pts reaches it (what VideoPlayer does). Also reopens a
     * drained stream, which is how looping works.
     */
    fun seekTo(ptsNanos: Long)

    /**
     * Container-reported total duration of one lap, or null when the
     * source cannot know it cheaply (animated webp declares none).
     */
    fun durationNanos(): Long? = null

    /** Format-level tags (title, artist, ...); empty when none. */
    fun tags(): Map<String, String> = emptyMap()

    /** Container chapters; empty when none. */
    fun chapters(): List<Chapter> = emptyList()

    /** Encoded cover-art bytes (png/jpeg as stored); null when none. */
    fun coverArt(): ByteArray? = null

    /**
     * Clockwise degrees (0/90/180/270) the frames must be rotated for
     * correct display -- phone footage stores its orientation as
     * metadata and the pixels arrive sideways.
     */
    fun rotationDegrees(): Int = 0

    /** The container's subtitle streams; empty when none. */
    fun subtitleTracks(): List<SubtitleTrack> = emptyList()

    /** Coded video geometry (width to height); null when unknown. */
    fun videoSize(): Pair<Int, Int>? = null
}
