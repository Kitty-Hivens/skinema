package dev.hivens.skinema.libav

import dev.hivens.skinema.audio.PcmFormat
import java.nio.file.Path

/**
 * The audio half of a file, pull-style via [nextChunk]: decoded PCM in the
 * file's own shape until [convertLastAs] asks for another. The thread that
 * opened it owns it, and a file without an audio stream is a normal
 * condition ([openOrNull] returns null), not an error. The rate is always the
 * stream's own; nothing here resamples.
 *
 * On the desktop this is FFmpeg, and the shape is whatever the decoder emits,
 * 24-bit content arriving as 32-bit integer. On Android it is the platform
 * extractor and a MediaCodec decoder, which produce 16-bit integer or float;
 * a compressed source deeper than 16 bits is asked for float, and FLAC
 * arrives as 16-bit, because the platform's FLAC extractor narrows it before
 * any decoder sees it. Conversion is done in Kotlin. Chapters there are read
 * as [VideoDecoder] reads them.
 */
expect class AudioDecoder : AutoCloseable {

    /** The stream actually opened: the best-stream pick or the request. */
    val streamIndex: Int

    /** Total duration, or null when the container declares none. */
    val durationNanos: Long?

    /** Every audio stream the container carries, [streamIndex] included. */
    val tracks: List<AudioTrack>

    /** Format-level tags. */
    val tags: Map<String, String>

    /** Container chapters. */
    val chapters: List<Chapter>

    /** Encoded cover-art bytes as stored in the file, or null. */
    val coverArt: ByteArray?

    /** One decoded chunk. The array is reused and valid until the next [nextChunk]. */
    class PcmChunk {
        /** Interleaved PCM in [format]; only the first [byteCount] bytes are meaningful. */
        val pcm: ByteArray
        val byteCount: Int

        /** Presentation time of the chunk's first sample. */
        val ptsNanos: Long

        /** The shape of the bytes in [pcm]. */
        val format: PcmFormat

        /** Shorthand for the rate of [format]. */
        val sampleRate: Int
    }

    /** Decodes and converts the next chunk; null at end of stream. */
    fun nextChunk(): PcmChunk?

    /** Repositions at or before [ptsNanos]; also reopens a drained stream. */
    fun seekTo(ptsNanos: Long)

    /** Converts the chunk [nextChunk] last returned into [format], and every chunk after it. */
    fun convertLastAs(format: PcmFormat): PcmChunk

    override fun close()

    companion object {
        /** Opens an audio stream of [path], the demuxer's pick when [streamIndex] is null; null when there is none. */
        fun openOrNull(path: Path, streamIndex: Int? = null): AudioDecoder?

        /** The same, over a custom byte [source]. */
        fun openOrNull(source: MediaSource, streamIndex: Int? = null): AudioDecoder?
    }
}
