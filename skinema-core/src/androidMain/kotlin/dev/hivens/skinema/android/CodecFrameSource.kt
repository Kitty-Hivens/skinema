package dev.hivens.skinema.android

import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import dev.hivens.skinema.libav.Chapter
import dev.hivens.skinema.libav.FrameSource
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.libav.LibavException
import dev.hivens.skinema.libav.NoVideoStreamException
import dev.hivens.skinema.libav.SubtitleTrack
import dev.hivens.skinema.libav.ToneMapper
import dev.hivens.skinema.libav.VideoDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Video through the platform extractor and a MediaCodec decoder in buffer
 * mode, converted to RGBA on the CPU from the flexible YUV image the codec
 * hands out: what [VideoDecoder] decodes a video with on Android.
 *
 * Converting here rather than on the GPU keeps the colour handling identical
 * to the desktop's and needs no graphics context, so it works on any thread
 * and in a test. Timestamps are the extractor's, in microseconds.
 */
internal class CodecFrameSource private constructor(
    private val input: Input,
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    private val trackFormat: MediaFormat,
    val streamIndex: Int,
    private val info: ContainerInfo,
    private val subtitles: List<SubtitleTrack>,
) : FrameSource {

    private val bufferInfo = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false

    // The output buffer the last nextFrame returned, held until the next call
    // so convertLast can still read it.
    private var heldIndex = -1
    private var heldImage: Image? = null
    private var heldPtsNanos = 0L

    private var outputFormat: MediaFormat? = null
    private var converter: YuvToRgba? = null
    private var converterKey: Triple<YuvMatrix, Boolean, Int>? = null
    private var rgba = ByteArray(0)
    private var wide = ShortArray(0)
    private var toneMapper: ToneMapper? = null
    private var toneMapperTransfer: Int? = null

    // A live muxer's file declares zero, which is no duration at all.
    private val declaredDuration: Long? = trackFormat.longOrNull(MediaFormat.KEY_DURATION)?.takeIf { it > 0 }?.let { it * NANOS_PER_MICRO }

    @Volatile
    private var observedDuration: Long? = null
    private var lastFrameEndNanos = 0L
    private var previousPtsNanos = -1L
    private var lastGapNanos = 0L

    // How long a frame stays, for where the last one ends: the declared rate,
    // or failing that the gap the frames before it kept.
    private val declaredFrameNanos: Long? = (trackFormat.numberOrNull(MediaFormat.KEY_FRAME_RATE))
        ?.takeIf { it > 0.0 }?.let { (1_000_000_000.0 / it).toLong() }

    override fun durationNanos(): Long? = declaredDuration ?: observedDuration
    override fun tags(): Map<String, String> = info.tags
    override fun chapters(): List<Chapter> = info.chapters
    override fun coverArt(): ByteArray? = info.coverArt
    override fun subtitleTracks(): List<SubtitleTrack> = subtitles
    override fun captionBytes(): ByteArray? = null
    override fun hardwareActive(): Boolean = codec.codecInfo.isHardwareAccelerated

    /**
     * The platform's rotation is already the clockwise turn to apply, which
     * is the contract here; it is snapped to the quarter grid the desktop
     * snaps to.
     */
    override fun rotationDegrees(): Int {
        val degrees = trackFormat.intOrNull(MediaFormat.KEY_ROTATION) ?: return 0
        return (Math.floorMod(degrees, 360) + 45) / 90 % 4 * 90
    }

    override fun videoSize(): Pair<Int, Int>? {
        val w = trackFormat.intOrNull(MediaFormat.KEY_WIDTH) ?: return null
        val h = trackFormat.intOrNull(MediaFormat.KEY_HEIGHT) ?: return null
        return w to h
    }

    override fun nextFrame(target: ByteArray?, convert: Boolean): VideoDecoder.RgbaFrame? {
        releaseHeld()
        var idleSince = System.nanoTime()
        while (!outputDone) {
            if (feedInput()) idleSince = System.nanoTime()
            val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                index < 0 -> {
                    // A codec that takes no input and gives no output for this
                    // long is wedged, and a pull-style decoder that waits on it
                    // forever is a hang the caller cannot get out of.
                    if (System.nanoTime() - idleSince > STALL_NANOS) {
                        throw LibavException("the decoder produced nothing for ${STALL_NANOS / 1_000_000_000} s")
                    }
                }
                else -> {
                    idleSince = System.nanoTime()
                    val end = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (end) outputDone = true
                    if (bufferInfo.size == 0) {
                        codec.releaseOutputBuffer(index, false)
                        continue
                    }
                    heldIndex = index
                    heldPtsNanos = bufferInfo.presentationTimeUs * NANOS_PER_MICRO
                    noteFrameEnd(heldPtsNanos)
                    return if (convert) convertHeld(target) else metadataFrame()
                }
            }
        }
        if (observedDuration == null && lastFrameEndNanos > 0) observedDuration = lastFrameEndNanos
        return null
    }

    /** Where the frame at [pts] ends, as the desktop counts a duration it had to observe: its start and its stay. */
    private fun noteFrameEnd(pts: Long) {
        if (previousPtsNanos >= 0 && pts > previousPtsNanos) lastGapNanos = pts - previousPtsNanos
        previousPtsNanos = pts
        val end = pts + (declaredFrameNanos ?: lastGapNanos)
        if (end > lastFrameEndNanos) lastFrameEndNanos = end
    }

    override fun convertLast(target: ByteArray?): VideoDecoder.RgbaFrame {
        check(heldIndex >= 0) { "no frame has been decoded to convert" }
        return convertHeld(target)
    }

    override fun seekTo(ptsNanos: Long) = seekToMicros(ptsNanos.coerceAtLeast(0) / NANOS_PER_MICRO)

    /**
     * One microsecond earlier, which is one whole unit of this decoder's time
     * base and so lands on the keyframe before the one standing on [ptsNanos].
     */
    override fun seekBefore(ptsNanos: Long) =
        seekToMicros((ptsNanos.coerceAtLeast(0) / NANOS_PER_MICRO - 1).coerceAtLeast(0))

    private fun seekToMicros(micros: Long) {
        releaseHeld()
        previousPtsNanos = -1L
        extractor.seekTo(micros, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
    }

    /** Queues one sample when the codec has room; true when anything moved. */
    private fun feedInput(): Boolean {
        if (inputDone) return false
        val index = codec.dequeueInputBuffer(0)
        if (index < 0) return false
        val buffer = codec.getInputBuffer(index) ?: throw LibavException("the decoder handed out no input buffer")
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

    private fun image(): Image {
        heldImage?.let { return it }
        val image = codec.getOutputImage(heldIndex)
            ?: throw LibavException("the decoder hands out no readable image for its frames")
        heldImage = image
        return image
    }

    private fun metadataFrame(): VideoDecoder.RgbaFrame {
        val crop = image().cropRect
        return VideoDecoder.RgbaFrame(crop.width(), crop.height(), heldPtsNanos, NO_PIXELS)
    }

    private fun convertHeld(target: ByteArray?): VideoDecoder.RgbaFrame {
        val image = image()
        val crop: Rect = image.cropRect
        val width = crop.width()
        val height = crop.height()
        val bytes = width * height * 4
        if (rgba.size != bytes) rgba = ByteArray(bytes)
        val out = target?.takeIf { it.size == bytes } ?: rgba
        val format = outputFormat ?: trackFormat
        val matrix = YuvMatrix.of(format.intOrNull(MediaFormat.KEY_COLOR_STANDARD), width, height)
        val fullRange = format.intOrNull(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
        val convert = converterFor(matrix, fullRange, image.format)
        val transfer = format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
        when {
            image.format == P010 -> {
                val pixels = width * height
                if (wide.size != pixels * 4) wide = ShortArray(pixels * 4)
                convert.convertP010(image, crop, wide)
                toneMapperFor(transfer).toneMap(wide, out, pixels)
            }
            image.format == ImageFormat.YUV_420_888 -> convert.convert(image, crop, out)
            else -> throw LibavException("the decoder hands out frames in image format ${image.format}, which is not YUV 4:2:0")
        }
        return VideoDecoder.RgbaFrame(width, height, heldPtsNanos, out)
    }

    private fun converterFor(matrix: YuvMatrix, fullRange: Boolean, imageFormat: Int): YuvToRgba {
        val key = Triple(matrix, fullRange, imageFormat)
        converter?.let { if (converterKey == key) return it }
        return YuvToRgba(matrix, fullRange).also {
            converter = it
            converterKey = key
        }
    }

    /**
     * The curve for [transfer]. A P010 frame that declares no HDR transfer is
     * read as PQ, the transfer ten-bit consumer video carries far more often
     * than HLG; a ten-bit SDR stream would need the SDR path instead, and the
     * platform hands P010 out only when it was asked for, which is only for a
     * stream declaring PQ or HLG.
     */
    private fun toneMapperFor(transfer: Int?): ToneMapper {
        toneMapper?.let { if (toneMapperTransfer == transfer) return it }
        val curve = if (transfer == MediaFormat.COLOR_TRANSFER_HLG) ToneMapper.HdrTransfer.HLG else ToneMapper.HdrTransfer.PQ
        return ToneMapper(curve).also {
            toneMapper = it
            toneMapperTransfer = transfer
        }
    }

    private fun releaseHeld() {
        heldImage?.close()
        heldImage = null
        if (heldIndex >= 0) {
            codec.releaseOutputBuffer(heldIndex, false)
            heldIndex = -1
        }
    }

    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { releaseHeld() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        input.close()
    }

    companion object {
        private const val NANOS_PER_MICRO = 1_000L
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val STALL_NANOS = 10_000_000_000L
        private val NO_PIXELS = ByteArray(0)

        /** ImageFormat.YCBCR_P010, named here because the constant arrives at API 33. */
        private const val P010 = 0x36

        /** A decoder for [input]'s video; closing the source closes [input]. */
        fun open(input: Input, hardware: HwAccel): CodecFrameSource {
            val extractor = try {
                openExtractor(input)
            } catch (t: Throwable) {
                input.close()
                throw t
            }
            var codec: MediaCodec? = null
            try {
                val track = extractor.firstTrack("video/")
                if (track < 0) throw NoVideoStreamException("${input.label} carries no video stream")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val transfer = format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
                val hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
                // P010 out for HDR where the platform can be asked for it, so
                // the tone mapper gets the ten bits the transfer was encoded
                // in. Below API 33 an HDR stream converts through the 8-bit
                // path and plays washed out, the way the desktop does when its
                // 16-bit context cannot be built.
                val colorFormat = if (hdr && Build.VERSION.SDK_INT >= 33) {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
                } else {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                }
                codec = openDecoder(format, hardware) { c ->
                    val configured = MediaFormat(format)
                    configured.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                    c.configure(configured, null, null, 0)
                }
                return CodecFrameSource(
                    input, extractor, codec, format, track,
                    ContainerInfo.read(input),
                    extractor.subtitleTracks(),
                )
            } catch (t: Throwable) {
                codec?.let { runCatching { it.release() } }
                runCatching { extractor.release() }
                input.close()
                throw t
            }
        }
    }
}
