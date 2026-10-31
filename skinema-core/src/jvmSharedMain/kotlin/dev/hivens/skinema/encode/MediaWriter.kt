package dev.hivens.skinema.encode

import java.nio.file.Path

/**
 * Encodes pushed RGBA8888 frames, and optionally interleaved S16LE stereo
 * PCM, to a muxed file whose container the output extension picks. Frames
 * and samples are pushed from the one thread that opened the writer;
 * [finish] drains the encoders and writes the trailer.
 *
 * Each platform writes through its own engine, and its contract is
 * documented on that platform's implementation.
 */
expect class MediaWriter : AutoCloseable {

    /** Encodes one tightly packed RGBA8888 frame presented at [ptsNanos], in non-decreasing order. */
    fun writeFrame(rgba: ByteArray, ptsNanos: Long)

    /** Appends interleaved S16LE stereo PCM, timed by the running sample count. */
    fun writeAudio(pcm: ByteArray)

    /** Drains the encoders and writes the container trailer. Call once, before [close]. */
    fun finish()

    override fun close()

    companion object {
        /** Opens [path] for [video] and, when given, [audio]. */
        fun open(path: Path, video: VideoEncodeConfig, audio: AudioEncodeConfig? = null): MediaWriter

        /** Opens [path] for [audio] alone: a file with no picture in it. */
        fun open(path: Path, audio: AudioEncodeConfig): MediaWriter
    }
}
