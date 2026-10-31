package dev.hivens.skinema.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.hivens.skinema.Debug
import dev.hivens.skinema.android.RgbaToYuv
import dev.hivens.skinema.android.YuvMatrix
import dev.hivens.skinema.libav.LibavException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Android writer: the platform's MediaCodec encoders and MediaMuxer.
 *
 * RGBA frames are converted to YUV on the CPU, in the matrix the desktop
 * writer uses for the geometry, and the file is tagged with it. Sound is
 * interleaved S16LE stereo, as on the desktop.
 *
 * Containers by extension: mp4/m4a/m4v/mov, webm, ogg/opus and 3gp through
 * the muxer; .flac and .wav written here. Matroska (.mkv) is refused by name:
 * the platform muxer does not write it. Codec names are the desktop's encoder
 * names or the family names both platforms take (see [VideoEncodeConfig]),
 * mapped onto the platform's codec types; a type the device has no encoder
 * for is refused by name. Encoder options and the VAAPI device are ignored:
 * the platform encoders have neither.
 */
actual class MediaWriter private constructor(
    private val sink: Sink,
    private val video: Encoder?,
    private val audio: Encoder?,
    private val videoConfig: VideoEncodeConfig?,
    private val audioConfig: AudioEncodeConfig?,
    private val pcmOnly: Boolean,
) : AutoCloseable {

    private val converter = videoConfig?.let { RgbaToYuv(it.width, it.height) }
    private var samplesQueued = 0L
    private var finished = false
    private var closed = false

    actual fun writeFrame(rgba: ByteArray, ptsNanos: Long) {
        check(!closed) { "writeFrame after close()" }
        check(!finished) { "writeFrame after finish()" }
        val v = video ?: throw LibavException("this MediaWriter has no video stream")
        val config = checkNotNull(videoConfig)
        require(rgba.size == config.width * config.height * 4) {
            "frame must be ${config.width * config.height * 4} RGBA bytes, got ${rgba.size}"
        }
        val index = v.inputSlot(sink)
        val image = v.codec.getInputImage(index) ?: throw LibavException("the encoder hands out no input image")
        checkNotNull(converter).convert(rgba, image)
        v.codec.queueInputBuffer(index, 0, config.width * config.height * 3 / 2, ptsNanos / NANOS_PER_MICRO, 0)
        v.drain(sink, final = false)
    }

    actual fun writeAudio(pcm: ByteArray) {
        check(!closed) { "writeAudio after close()" }
        check(!finished) { "writeAudio after finish()" }
        val config = audioConfig ?: throw LibavException("this MediaWriter has no audio stream")
        require(pcm.size % BYTES_PER_AUDIO_FRAME == 0) { "PCM must be whole S16LE stereo frames, got ${pcm.size} bytes" }
        if (pcmOnly) {
            sink.writePcm(pcm)
            return
        }
        val a = checkNotNull(audio)
        var offset = 0
        while (offset < pcm.size) {
            val index = a.inputSlot(sink)
            val buffer = a.codec.getInputBuffer(index) ?: throw LibavException("the audio encoder hands out no input buffer")
            buffer.clear()
            val n = minOf(buffer.remaining() / BYTES_PER_AUDIO_FRAME * BYTES_PER_AUDIO_FRAME, pcm.size - offset)
            buffer.put(pcm, offset, n)
            a.codec.queueInputBuffer(index, 0, n, samplesQueued * 1_000_000L / config.sampleRate, 0)
            samplesQueued += n / BYTES_PER_AUDIO_FRAME
            offset += n
            a.drain(sink, final = false)
        }
    }

    /**
     * Safe to call again after it threw, as on the desktop: each step that
     * already happened (an end of stream queued, an encoder drained, the
     * container closed) is not asked for twice, so a retry, or the close
     * after a failed finish, resumes where the failure left off.
     */
    actual fun finish() {
        check(!closed) { "finish after close()" }
        if (finished) return
        for (encoder in listOfNotNull(video, audio)) {
            if (encoder.endQueued) continue
            val index = encoder.inputSlot(sink)
            val pts = if (encoder === audio) samplesQueued * 1_000_000L / checkNotNull(audioConfig).sampleRate else 0L
            encoder.codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            encoder.endQueued = true
        }
        for (encoder in listOfNotNull(video, audio)) encoder.drain(sink, final = true)
        sink.finish()
        finished = true
    }

    actual override fun close() {
        if (closed) return
        // A writer closed without finish() still leaves a file that plays, as
        // on the desktop: best effort, since whatever aborted the write may
        // refuse this too.
        if (!finished) runCatching { finish() }.onFailure { Debug.trace("finish on close", it) }
        closed = true
        for (encoder in listOfNotNull(video, audio)) {
            runCatching { encoder.codec.stop() }
            runCatching { encoder.codec.release() }
        }
        runCatching { sink.release() }
    }

    /** One encoder and the track its output became. */
    private class Encoder(val codec: MediaCodec) {
        private val info = MediaCodec.BufferInfo()
        var track = -1
        var ended = false
        var endQueued = false

        /** An input buffer index, draining output while the encoder has none free. */
        fun inputSlot(sink: Sink): Int {
            val deadline = System.nanoTime() + STALL_NANOS
            while (true) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) return index
                drain(sink, final = false)
                if (System.nanoTime() > deadline) throw LibavException("the encoder took no input for ${STALL_NANOS / 1_000_000_000} s")
            }
        }

        /** Hands every ready packet to [sink]; with [final], until the end of stream comes out. */
        fun drain(sink: Sink, final: Boolean) {
            val deadline = System.nanoTime() + STALL_NANOS
            while (!ended) {
                val index = codec.dequeueOutputBuffer(info, if (final) TIMEOUT_US else 0L)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = sink.addTrack(codec.outputFormat)
                    index < 0 -> {
                        if (!final) return
                        if (System.nanoTime() > deadline) throw LibavException("the encoder did not finish within ${STALL_NANOS / 1_000_000_000} s")
                    }
                    else -> {
                        val buffer = codec.getOutputBuffer(index) ?: throw LibavException("the encoder handed out no output buffer")
                        if (info.size > 0) {
                            buffer.position(info.offset).limit(info.offset + info.size)
                            sink.write(this, buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                    }
                }
            }
        }
    }

    /** Where encoded packets go. */
    private interface Sink {
        fun addTrack(format: MediaFormat): Int
        fun write(encoder: Encoder, buffer: ByteBuffer, info: MediaCodec.BufferInfo)
        fun writePcm(pcm: ByteArray) {
            throw LibavException("this container takes no raw PCM")
        }
        fun finish()
        fun release()
    }

    /**
     * MediaMuxer, which takes every track before the first sample: packets
     * that come out before the last track's format are held and written once
     * it starts.
     */
    private class MuxerSink(
        private val muxer: MediaMuxer,
        private val expectedTracks: Int,
        private val path: Path,
        private val container: String,
    ) : Sink {
        private var added = 0
        private var started = false
        private var stopped = false
        private var refusal: LibavException? = null
        private val held = ArrayList<Triple<Encoder, ByteArray, MediaCodec.BufferInfo>>()

        override fun addTrack(format: MediaFormat): Int {
            refusal?.let { throw it }
            // What the platform muxer takes in each container is its own
            // business and differs by release; its refusal is an
            // IllegalStateException, said here as the contract says refusals.
            val track = try {
                muxer.addTrack(format)
            } catch (e: IllegalStateException) {
                throw refuse(format, e)
            } catch (e: IllegalArgumentException) {
                throw refuse(format, e)
            }
            if (++added == expectedTracks) {
                muxer.start()
                started = true
                for ((encoder, bytes, info) in held) muxer.writeSampleData(encoder.track, ByteBuffer.wrap(bytes), info)
                held.clear()
            }
            return track
        }

        /** Kept, so every later packet is refused the same way rather than held without end. */
        private fun refuse(format: MediaFormat, cause: Exception): LibavException =
            LibavException("the platform muxer does not take ${format.getString(MediaFormat.KEY_MIME)} in .$container")
                .apply { initCause(cause) }
                .also { refusal = it }

        override fun write(encoder: Encoder, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            refusal?.let { throw it }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
            if (started) {
                muxer.writeSampleData(encoder.track, buffer, info)
            } else {
                val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                val copy = MediaCodec.BufferInfo().apply { set(0, bytes.size, info.presentationTimeUs, info.flags) }
                held += Triple(encoder, bytes, copy)
            }
        }

        override fun finish() {
            if (started && !stopped) {
                muxer.stop()
                stopped = true
            }
        }

        override fun release() {
            runCatching { muxer.release() }
            // Never started, it wrote no file worth keeping: a refusal leaves
            // nothing behind.
            if (!started) runCatching { Files.deleteIfExists(path) }
        }
    }

    /**
     * A raw FLAC stream: the encoder's codec-config bytes are the stream
     * header (the marker and the STREAMINFO block), and every packet after
     * them is a frame.
     */
    private class FlacSink(path: Path) : Sink {
        private val file = RandomAccessFile(path.toFile(), "rw").apply { setLength(0) }
        private var headerWritten = false
        private var done = false

        override fun addTrack(format: MediaFormat): Int {
            if (!headerWritten) format.getByteBuffer("csd-0")?.let { writeHeader(it) }
            return 0
        }

        override fun write(encoder: Encoder, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                if (!headerWritten) writeHeader(buffer)
                return
            }
            check(headerWritten) { "the FLAC encoder produced frames before its header" }
            val bytes = ByteArray(buffer.remaining()).also(buffer::get)
            file.write(bytes)
        }

        private fun writeHeader(csd: ByteBuffer) {
            val bytes = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
            if (bytes.size < 4 || String(bytes, 0, 4, Charsets.US_ASCII) != "fLaC") file.write("fLaC".toByteArray(Charsets.US_ASCII))
            file.write(bytes)
            headerWritten = true
        }

        override fun finish() {
            if (done) return
            file.close()
            done = true
        }

        override fun release() {
            runCatching { file.close() }
        }
    }

    /** A WAV file of the S16LE stereo the writer is fed, sizes patched in at the end. */
    private class WavSink(path: Path, private val sampleRate: Int) : Sink {
        private val file = RandomAccessFile(path.toFile(), "rw").apply {
            setLength(0)
            write(header(0))
        }
        private var dataBytes = 0L

        override fun addTrack(format: MediaFormat): Int = 0
        override fun write(encoder: Encoder, buffer: ByteBuffer, info: MediaCodec.BufferInfo) = Unit

        override fun writePcm(pcm: ByteArray) {
            file.write(pcm)
            dataBytes += pcm.size
        }

        private var done = false

        override fun finish() {
            if (done) return
            file.seek(0)
            file.write(header(dataBytes))
            file.close()
            done = true
        }

        override fun release() {
            runCatching { file.close() }
        }

        private fun header(data: Long): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt((36 + data).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(2)
            putInt(sampleRate); putInt(sampleRate * BYTES_PER_AUDIO_FRAME); putShort(BYTES_PER_AUDIO_FRAME.toShort()); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(data.toInt())
        }.array()
    }

    actual companion object {
        private const val NANOS_PER_MICRO = 1_000L
        private const val BYTES_PER_AUDIO_FRAME = 4
        private const val TIMEOUT_US = 10_000L
        private const val STALL_NANOS = 10_000_000_000L

        actual fun open(path: Path, video: VideoEncodeConfig, audio: AudioEncodeConfig?): MediaWriter = openWriter(path, video, audio)

        actual fun open(path: Path, audio: AudioEncodeConfig): MediaWriter = openWriter(path, null, audio)

        /** The platform codec type for a desktop encoder name or a family name; null when there is none. */
        internal fun videoMime(name: String): String? = when (name) {
            "h264", "libx264", "h264_vaapi", "libopenh264" -> MediaFormat.MIMETYPE_VIDEO_AVC
            "hevc", "libx265", "hevc_vaapi" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            "av1", "libsvtav1", "libaom-av1", "librav1e" -> MediaFormat.MIMETYPE_VIDEO_AV1
            "vp9", "libvpx-vp9" -> MediaFormat.MIMETYPE_VIDEO_VP9
            "vp8", "libvpx" -> MediaFormat.MIMETYPE_VIDEO_VP8
            else -> null
        }

        internal fun audioMime(name: String): String? = when (name) {
            "aac", "libfdk_aac" -> MediaFormat.MIMETYPE_AUDIO_AAC
            "opus", "libopus" -> MediaFormat.MIMETYPE_AUDIO_OPUS
            "flac" -> MediaFormat.MIMETYPE_AUDIO_FLAC
            else -> null
        }

        private fun openWriter(path: Path, video: VideoEncodeConfig?, audio: AudioEncodeConfig?): MediaWriter {
            require(video != null || audio != null) { "a writer needs at least one stream" }
            if (video != null) {
                require(video.width > 0 && video.height > 0) { "width/height must be positive" }
                require(video.width % 2 == 0 && video.height % 2 == 0) {
                    "chroma-subsampled encoding needs even dimensions, got ${video.width}x${video.height}"
                }
                require(video.fps > 0) { "fps must be positive" }
            }
            val extension = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase().orEmpty()
            // Everything that can be refused without touching the file is
            // refused first: a refusal leaves nothing behind, and a file that
            // was there before the call is not this writer's to delete.
            checkContainer(extension, video, audio)
            if (extension == "wav") {
                val a = checkNotNull(audio)
                return MediaWriter(WavSink(path, a.sampleRate), null, null, null, a, pcmOnly = true)
            }
            val codecs = ArrayList<MediaCodec>()
            var sink: Sink? = null
            // Set once the file has been created or truncated, which is when a
            // refusal owes its removal.
            var created = false
            try {
                val videoEncoder = video?.let { config ->
                    val mime = videoMime(config.codecName) ?: throw LibavException("no Android codec type for '${config.codecName}'")
                    val format = MediaFormat.createVideoFormat(mime, config.width, config.height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                        setInteger(MediaFormat.KEY_BIT_RATE, (if (config.bitRate > 0) config.bitRate else defaultVideoBitRate(config)).toInt())
                        setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                        // The matrix the frames are converted with, and the
                        // limited range they are written in, said in the file.
                        val matrix = YuvMatrix.of(null, config.width, config.height)
                        setInteger(
                            MediaFormat.KEY_COLOR_STANDARD,
                            if (matrix == YuvMatrix.BT709) MediaFormat.COLOR_STANDARD_BT709 else MediaFormat.COLOR_STANDARD_BT601_NTSC,
                        )
                        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    }
                    Encoder(createEncoder(format, config.codecName).also(codecs::add))
                }
                val audioEncoder = audio?.let { config ->
                    val mime = audioMime(config.codecName) ?: throw LibavException("no Android codec type for '${config.codecName}'")
                    val format = MediaFormat.createAudioFormat(mime, config.sampleRate, 2).apply {
                        if (mime != MediaFormat.MIMETYPE_AUDIO_FLAC) {
                            setInteger(MediaFormat.KEY_BIT_RATE, (if (config.bitRate > 0) config.bitRate else defaultAudioBitRate(mime)).toInt())
                        }
                        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        }
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
                    }
                    Encoder(createEncoder(format, config.codecName).also(codecs::add))
                }
                val format = muxerFormat(extension)
                created = true
                sink = if (extension == "flac") FlacSink(path) else MuxerSink(MediaMuxer(path.toString(), format), codecs.size, path, extension)
                return MediaWriter(sink, videoEncoder, audioEncoder, video, audio, pcmOnly = false)
            } catch (t: Throwable) {
                for (codec in codecs) runCatching { codec.release() }
                runCatching { sink?.release() }
                if (created) runCatching { Files.deleteIfExists(path) }
                throw t
            }
        }

        /**
         * The refusals a container and the codecs asked for make before
         * anything opens: a container this platform has no writer for, and a
         * codec the container cannot hold where the platform muxer is known to
         * refuse it. MP4 and 3GP take different codecs on different releases,
         * so what they refuse is learnt from the muxer as the first packet
         * arrives, and refused by name then.
         */
        private fun checkContainer(extension: String, video: VideoEncodeConfig?, audio: AudioEncodeConfig?) {
            val videoType = video?.let { videoMime(it.codecName) ?: throw LibavException("no Android codec type for '${it.codecName}'") }
            val audioType = audio?.let { a ->
                if (extension == "wav") null else audioMime(a.codecName) ?: throw LibavException("no Android codec type for '${a.codecName}'")
            }
            when (extension) {
                "wav" -> {
                    if (video != null) throw LibavException("a .wav file carries no picture")
                    val a = checkNotNull(audio)
                    if (a.codecName != "pcm_s16le") throw LibavException("a .wav file is written as pcm_s16le, not '${a.codecName}'")
                }
                "flac" -> if (video != null || audioType != MediaFormat.MIMETYPE_AUDIO_FLAC) {
                    throw LibavException("a .flac file carries FLAC sound and nothing else")
                }
                "mkv", "mka" -> throw LibavException("Matroska (.$extension) cannot be written on Android: the platform muxer does not write it")
                "webm" -> {
                    videoType?.takeIf { it != MediaFormat.MIMETYPE_VIDEO_VP8 && it != MediaFormat.MIMETYPE_VIDEO_VP9 }
                        ?.let { throw LibavException("a .webm file carries VP8 or VP9 video, not '${video.codecName}'") }
                    audioType?.takeIf { it != MediaFormat.MIMETYPE_AUDIO_OPUS }
                        ?.let { throw LibavException("a .webm file carries Opus sound here, not '${audio.codecName}'") }
                }
                "ogg", "opus" -> {
                    if (video != null) throw LibavException("an .$extension file carries no picture")
                    if (audioType != MediaFormat.MIMETYPE_AUDIO_OPUS) throw LibavException("an .$extension file carries Opus sound, not '${audio?.codecName}'")
                }
                else -> muxerFormat(extension)
            }
        }

        private fun muxerFormat(extension: String): Int = when (extension) {
            "mp4", "m4a", "m4v", "mov" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            "webm" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            "ogg", "opus" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
            "3gp" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP
            "flac" -> -1
            else -> throw LibavException("no Android container for '.$extension'")
        }

        /** The device's encoder for [format], configured and started, or a refusal naming what was asked. */
        private fun createEncoder(format: MediaFormat, asked: String): MediaCodec {
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
                ?: throw LibavException("no encoder on this device takes ${format.getString(MediaFormat.KEY_MIME)} (asked for '$asked')")
            val codec = MediaCodec.createByCodecName(name)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            } catch (e: Exception) {
                codec.release()
                throw LibavException("the encoder $name refused ${format.getString(MediaFormat.KEY_MIME)}: ${e.message}")
            }
            return codec
        }

        /** About a tenth of a bit per pixel per frame, which reads clean for ordinary footage. */
        private fun defaultVideoBitRate(config: VideoEncodeConfig): Long =
            (config.width.toLong() * config.height * config.fps / 10).coerceAtLeast(200_000L)

        private fun defaultAudioBitRate(mime: String): Long = if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS) 96_000L else 128_000L
    }
}
