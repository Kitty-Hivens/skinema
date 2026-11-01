package dev.hivens.skinema.libav

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.hivens.skinema.android.ContainerInfo
import dev.hivens.skinema.android.Input
import dev.hivens.skinema.android.PcmConvert
import dev.hivens.skinema.android.audioTracks
import dev.hivens.skinema.android.firstTrack
import dev.hivens.skinema.android.intOrNull
import dev.hivens.skinema.android.layoutFor
import dev.hivens.skinema.android.longOrNull
import dev.hivens.skinema.android.openDecoder
import dev.hivens.skinema.android.openExtractor
import dev.hivens.skinema.audio.PcmEncoding
import dev.hivens.skinema.audio.PcmFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The audio half of a file on Android: the platform extractor and a
 * MediaCodec decoder, PCM out in the shape the codec produced it until
 * [convertLastAs] asks for another.
 *
 * The platform decoders produce 16-bit integer or float, and more channels
 * than two only where the codec carries them; a 24-bit lossless file comes
 * out as float rather than as 32-bit integer, which carries those 24 bits
 * exactly.
 */
actual class AudioDecoder private constructor(
    private val input: Input,
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    actual val streamIndex: Int,
    actual val durationNanos: Long?,
    actual val tracks: List<AudioTrack>,
    actual val tags: Map<String, String>,
    actual val chapters: List<Chapter>,
    actual val coverArt: ByteArray?,
) : AutoCloseable {

    actual class PcmChunk internal constructor(
        actual val pcm: ByteArray,
        actual val byteCount: Int,
        actual val ptsNanos: Long,
        actual val format: PcmFormat,
    ) {
        actual val sampleRate: Int get() = format.sampleRate
    }

    private val bufferInfo = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false

    private var sourceFormat: PcmFormat? = null
    private var outFormat: PcmFormat? = null

    // The last decoded buffer in the codec's own shape, kept so
    // convertLastAs can convert it again without decoding twice.
    private var raw = ByteArray(0)
    private var rawLength = 0
    private var rawPtsNanos = 0L
    private var rawFormat: PcmFormat? = null
    private var converted = ByteArray(0)

    actual fun nextChunk(): PcmChunk? {
        var idleSince = System.nanoTime()
        while (!outputDone) {
            if (feedInput()) idleSince = System.nanoTime()
            val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sourceFormat = pcmFormatOf(codec.outputFormat)
                index < 0 -> if (System.nanoTime() - idleSince > STALL_NANOS) {
                    throw LibavException("the audio decoder produced nothing for ${STALL_NANOS / 1_000_000_000} s")
                }
                else -> {
                    idleSince = System.nanoTime()
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    val size = bufferInfo.size
                    if (size == 0) {
                        codec.releaseOutputBuffer(index, false)
                        continue
                    }
                    val source = sourceFormat ?: pcmFormatOf(codec.outputFormat).also { sourceFormat = it }
                    val buffer = codec.getOutputBuffer(index)
                        ?: throw LibavException("the audio decoder handed out no output buffer")
                    copyOut(buffer, size, source)
                    codec.releaseOutputBuffer(index, false)
                    rawPtsNanos = bufferInfo.presentationTimeUs * NANOS_PER_MICRO
                    return chunk(outFormat ?: rawFormat!!)
                }
            }
        }
        return null
    }

    actual fun convertLastAs(format: PcmFormat): PcmChunk {
        checkNotNull(rawFormat) { "no frame has been decoded to convert" }
        outFormat = format
        return chunk(format)
    }

    actual fun seekTo(ptsNanos: Long) {
        extractor.seekTo(ptsNanos.coerceAtLeast(0) / NANOS_PER_MICRO, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
    }

    /**
     * Copies the codec's buffer out in the shape [source] names. Packed
     * 24-bit is the one shape the PCM seam has no member for, so it widens to
     * 32-bit integer here, high bytes first filled, the way the desktop
     * carries 24-bit content.
     */
    private fun copyOut(buffer: ByteBuffer, size: Int, source: PcmFormat) {
        buffer.position(bufferInfo.offset)
        if (source.significantBits == 24 && source.encoding == PcmEncoding.S32LE && packed24) {
            val samples = size / 3
            ensureRaw(samples * 4)
            var d = 0
            repeat(samples) {
                raw[d] = 0
                raw[d + 1] = buffer.get()
                raw[d + 2] = buffer.get()
                raw[d + 3] = buffer.get()
                d += 4
            }
            rawLength = samples * 4
        } else {
            ensureRaw(size)
            buffer.get(raw, 0, size)
            rawLength = size
        }
        rawFormat = source
    }

    private fun ensureRaw(bytes: Int) {
        if (raw.size < bytes) raw = ByteArray(maxOf(bytes, raw.size * 2))
    }

    // Set by pcmFormatOf when the codec reports packed 24-bit.
    private var packed24 = false

    private fun chunk(target: PcmFormat): PcmChunk {
        val source = rawFormat!!
        // The rate is the stream's, never the caller's.
        val shape = if (target.sampleRate == source.sampleRate) target else target.copy(sampleRate = source.sampleRate)
        if (shape == source) return PcmChunk(raw, rawLength, rawPtsNanos, source)
        val frames = rawLength / source.bytesPerFrame
        val needed = frames * shape.bytesPerFrame
        if (converted.size < needed) converted = ByteArray(maxOf(needed, converted.size * 2))
        val written = PcmConvert.convert(raw, rawLength, source, shape, converted)
        return PcmChunk(converted, written, rawPtsNanos, shape)
    }

    private fun pcmFormatOf(format: MediaFormat): PcmFormat {
        val rate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: throw LibavException("the audio decoder reports no sample rate")
        val channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: throw LibavException("the audio decoder reports no channel count")
        val pcm = format.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
        packed24 = pcm == ENCODING_PCM_24BIT_PACKED
        val (encoding, bits) = when (pcm) {
            AudioFormat.ENCODING_PCM_8BIT -> PcmEncoding.U8 to 8
            AudioFormat.ENCODING_PCM_FLOAT -> PcmEncoding.F32LE to 32
            ENCODING_PCM_32BIT -> PcmEncoding.S32LE to 32
            ENCODING_PCM_24BIT_PACKED -> PcmEncoding.S32LE to 24
            else -> PcmEncoding.S16LE to 16
        }
        return PcmFormat(rate, channels, layoutFor(channels), encoding, bits)
    }

    private fun feedInput(): Boolean {
        if (inputDone) return false
        val index = codec.dequeueInputBuffer(0)
        if (index < 0) return false
        val buffer = codec.getInputBuffer(index) ?: throw LibavException("the audio decoder handed out no input buffer")
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
        return true
    }

    private val closed = AtomicBoolean(false)

    actual override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        input.close()
    }

    actual companion object {
        private const val NANOS_PER_MICRO = 1_000L
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val STALL_NANOS = 10_000_000_000L

        // AudioFormat constants that arrive at API 31, named for the floor of 29.
        private const val ENCODING_PCM_24BIT_PACKED = 21
        private const val ENCODING_PCM_32BIT = 22

        /**
         * Whether a decoder would narrow this source: a compressed track that
         * says it is deeper than 16 bits. A raw PCM track is never asked: the
         * platform's raw decoder passes the bytes through as they are and would
         * only relabel them.
         */
        private fun deeperThan16(format: MediaFormat): Boolean {
            if (format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_RAW) return false
            return (format.intOrNull(KEY_BITS_PER_SAMPLE) ?: 16) > 16
        }

        // The extractor's own key for a lossless track's depth; MediaFormat names no constant for it.
        private const val KEY_BITS_PER_SAMPLE = "bits-per-sample"

        actual fun openOrNull(path: Path, streamIndex: Int?): AudioDecoder? = openFrom(Input.File(path), streamIndex)

        actual fun openOrNull(source: MediaSource, streamIndex: Int?): AudioDecoder? = openFrom(Input.Source(source), streamIndex)

        private fun openFrom(input: Input, streamIndex: Int?): AudioDecoder? {
            val extractor = try {
                openExtractor(input)
            } catch (t: Throwable) {
                input.close()
                throw t
            }
            try {
                val tracks = extractor.audioTracks()
                val chosen = if (streamIndex == null) {
                    extractor.firstTrack("audio/")
                } else {
                    if (tracks.none { it.streamIndex == streamIndex }) {
                        throw LibavException("stream $streamIndex is not an audio track of ${input.label}")
                    }
                    streamIndex
                }
                if (chosen < 0) {
                    runCatching { extractor.release() }
                    input.close()
                    return null
                }
                extractor.selectTrack(chosen)
                val format = extractor.getTrackFormat(chosen)
                // A decoder left to itself narrows a deeper source to 16 bits;
                // asked for float, it carries all of them.
                if (deeperThan16(format)) format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
                // Audio decode is software on every device that matters, and
                // the policy word is the video side's; any decoder will do.
                val codec = openDecoder(format, HwAccel.AUTO) { c -> c.configure(format, null, null, 0) }
                val info = try {
                    ContainerInfo.read(input)
                } catch (t: Throwable) {
                    runCatching { codec.release() }
                    throw t
                }
                return AudioDecoder(
                    input, extractor, codec, chosen,
                    format.longOrNull(MediaFormat.KEY_DURATION)?.let { it * NANOS_PER_MICRO },
                    tracks, info.tags, info.chapters, info.coverArt,
                )
            } catch (t: Throwable) {
                runCatching { extractor.release() }
                input.close()
                throw t
            }
        }
    }
}
