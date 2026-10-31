package dev.hivens.skinema.encode

import java.nio.file.Path

/**
 * Encodes pushed RGBA8888 frames, and optionally interleaved S16LE stereo
 * PCM, to a muxed file whose container the output extension picks. The
 * player inverted: frames and samples are pushed from the one thread that
 * opened the writer, [finish] drains the encoders and writes the trailer, and
 * [close] releases everything, finishing first if nobody did, so a writer
 * left in a `use` block that threw still leaves a file that plays.
 *
 * Fail-closed: a refusal throws [dev.hivens.skinema.libav.LibavException] and
 * leaves nothing behind, the output file included. The argument checks (even
 * dimensions, a positive rate, whole PCM frames) throw
 * [IllegalArgumentException]. Frames are converted to YUV in the matrix the
 * geometry implies (BT.709 for HD, BT.601 below), limited range, and the file
 * is tagged with it, so a player reading the tag and one guessing from the
 * size agree.
 *
 * On the desktop this is FFmpeg: every container the bundle muxes (mp4/mov,
 * mkv, webm, .opus, .flac, .wav), encoder options honoured, hardware encode
 * fail-closed when a GPU encoder is named. On Android it is the platform's
 * MediaCodec encoders and MediaMuxer: mp4/m4a/mov, webm, ogg/opus and 3gp,
 * with .flac and .wav written by the library; Matroska is refused by name,
 * since the platform does not write it, and encoder options and the VAAPI
 * device are ignored. A codec the device has no encoder for is refused by
 * name on both.
 */
expect class MediaWriter : AutoCloseable {

    /**
     * Encodes one tightly packed RGBA8888 frame presented at [ptsNanos].
     * Frames come in non-decreasing pts order. Refused by name on a writer
     * opened for sound alone.
     */
    fun writeFrame(rgba: ByteArray, ptsNanos: Long)

    /**
     * Appends interleaved S16LE stereo PCM, timed by the running sample count
     * from the first sample. Refused by name on a writer opened without sound.
     */
    fun writeAudio(pcm: ByteArray)

    /** Drains the encoders and writes the container trailer. Call once, before [close]. */
    fun finish()

    override fun close()

    companion object {
        /** Opens [path] for [video] and, when given, [audio]. */
        fun open(path: Path, video: VideoEncodeConfig, audio: AudioEncodeConfig? = null): MediaWriter

        /**
         * Opens [path] for [audio] alone: a file with no picture in it, which is
         * what .opus, .flac and .wav are for. A separate entry rather than a
         * nullable video, so a writer with no streams at all cannot be asked for.
         */
        fun open(path: Path, audio: AudioEncodeConfig): MediaWriter
    }
}
