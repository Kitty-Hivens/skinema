package dev.hivens.skinema.libav

import dev.hivens.skinema.core.nanosToPts
import dev.hivens.skinema.core.ptsToNanos
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.file.Path

/**
 * One open video file: demux + decode + RGBA conversion, pull-style via
 * [nextFrame]. Spike-grade (M0): best video stream only, software decode,
 * blocking calls. The backing Arena is confined -- the thread that called
 * [open] owns the session, which is the design intent (one decode thread).
 */
class VideoDecoder private constructor(
    private val arena: Arena,
    private val fmtCtx: MemorySegment,
    private val codecCtx: MemorySegment,
    private val packet: MemorySegment,
    private val frame: MemorySegment,
    val streamIndex: Int,
    val timeBaseNum: Int,
    val timeBaseDen: Int,
    private val duration: Long?,
    private val tags: Map<String, String>,
    private val chapters: List<Chapter>,
    private val coverArt: ByteArray?,
    private val rotationDegrees: Int,
) : FrameSource {

    override fun durationNanos(): Long? = duration
    override fun tags(): Map<String, String> = tags
    override fun chapters(): List<Chapter> = chapters
    override fun coverArt(): ByteArray? = coverArt
    override fun rotationDegrees(): Int = rotationDegrees

    class RgbaFrame internal constructor(
        val width: Int,
        val height: Int,
        /** Presentation time in nanoseconds from stream start. */
        val ptsNanos: Long,
        /**
         * RGBA8888, row-major, tightly packed (stride = width * 4). The
         * array is reused: contents are valid only until the next
         * [nextFrame] call. Consumers that keep a frame must copy.
         */
        val rgba: ByteArray,
    )

    private var draining = false

    // swscale state, (re)built lazily on the first frame and on any
    // mid-stream geometry/format change.
    private var swsCtx = MemorySegment.NULL
    private var swsWidth = 0
    private var swsHeight = 0
    private var swsFormat = Int.MIN_VALUE
    private var swsColorspace = Int.MIN_VALUE
    private var swsRange = Int.MIN_VALUE
    private var dstData = MemorySegment.NULL
    private var dstStride = MemorySegment.NULL
    private var rgbaNative = MemorySegment.NULL
    private var rgbaHeap = ByteArray(0)

    /**
     * Decodes and converts the next frame; null at end of stream. When
     * [target] is provided and matches the frame's RGBA size it receives
     * the pixels (the caller's buffer pool); otherwise an internal reused
     * buffer backs the result.
     */
    override fun nextFrame(target: ByteArray?, convert: Boolean): RgbaFrame? {
        while (true) {
            when (val ret = Libav.avcodecReceiveFrame(codecCtx, frame)) {
                0 -> return if (convert) convertCurrentFrame(target) else metadataOnlyFrame()
                LibavAbi.AVERROR_EAGAIN -> feedOnePacket()
                LibavAbi.AVERROR_EOF -> return null
                else -> Libav.checkAv(ret, "avcodec_receive_frame")
            }
        }
    }

    override fun convertLast(target: ByteArray?): RgbaFrame = convertCurrentFrame(target)

    /** The decoded frame's pts and geometry without touching its pixels. */
    private fun metadataOnlyFrame(): RgbaFrame = RgbaFrame(
        width = frame.get(JAVA_INT, LibavAbi.Frame.WIDTH),
        height = frame.get(JAVA_INT, LibavAbi.Frame.HEIGHT),
        ptsNanos = currentPtsNanos(),
        rgba = NO_PIXELS,
    )

    /**
     * Positions the demuxer at the last keyframe at-or-before [ptsNanos]
     * and resets decoder state. Frames then resume from that keyframe --
     * a caller wanting the exact target decodes forward until the frame's
     * pts reaches it (what VideoPlayer does). Also reopens a drained
     * stream, which is how looping works.
     */
    override fun seekTo(ptsNanos: Long) {
        val ts = nanosToPts(ptsNanos, timeBaseNum, timeBaseDen)
        Libav.checkAv(
            Libav.avSeekFrame(fmtCtx, streamIndex, ts, LibavAbi.AVSEEK_FLAG_BACKWARD),
            "av_seek_frame",
        )
        Libav.avcodecFlushBuffers(codecCtx)
        draining = false
    }

    /**
     * Feeds exactly one packet of this video stream to the decoder; on
     * input EOF sends the flush packet instead, switching to draining.
     */
    private fun feedOnePacket() {
        if (draining) {
            // receive_frame answered EAGAIN after the flush packet -- the
            // decoder contract says that cannot happen; fail loudly rather
            // than spin.
            throw LibavException("decoder demanded input while draining")
        }
        while (true) {
            val ret = Libav.avReadFrame(fmtCtx, packet)
            if (ret < 0) {
                draining = true
                Libav.checkAv(Libav.avcodecSendPacket(codecCtx, MemorySegment.NULL), "avcodec_send_packet(flush)")
                return
            }
            if (packet.get(JAVA_INT, LibavAbi.Packet.STREAM_INDEX) != streamIndex) {
                Libav.avPacketUnref(packet)
                continue
            }
            val sent = Libav.avcodecSendPacket(codecCtx, packet)
            Libav.avPacketUnref(packet)
            Libav.checkAv(sent, "avcodec_send_packet")
            return
        }
    }

    private fun convertCurrentFrame(target: ByteArray?): RgbaFrame {
        val width = frame.get(JAVA_INT, LibavAbi.Frame.WIDTH)
        val height = frame.get(JAVA_INT, LibavAbi.Frame.HEIGHT)
        val format = frame.get(JAVA_INT, LibavAbi.Frame.FORMAT)
        ensureSws(width, height, format)
        ensureColorspaceDetails(width, height)

        Libav.swsScale(
            swsCtx,
            frame.asSlice(LibavAbi.Frame.DATA),
            frame.asSlice(LibavAbi.Frame.LINESIZE),
            0,
            height,
            dstData,
            dstStride,
        )
        val out = target?.takeIf { it.size == rgbaHeap.size } ?: rgbaHeap
        MemorySegment.copy(rgbaNative, JAVA_BYTE, 0, out, 0, out.size)
        return RgbaFrame(width, height, currentPtsNanos(), out)
    }

    private fun currentPtsNanos(): Long {
        val pts = frame.get(JAVA_LONG, LibavAbi.Frame.PTS)
            .takeIf { it != LibavAbi.AV_NOPTS_VALUE }
            ?: frame.get(JAVA_LONG, LibavAbi.Frame.BEST_EFFORT_TIMESTAMP)
        return if (pts == LibavAbi.AV_NOPTS_VALUE) 0L else ptsToNanos(pts, timeBaseNum, timeBaseDen)
    }

    private fun ensureSws(width: Int, height: Int, format: Int) {
        if (swsCtx != MemorySegment.NULL && width == swsWidth && height == swsHeight && format == swsFormat) return
        if (swsCtx != MemorySegment.NULL) Libav.swsFreeContext(swsCtx)

        swsCtx = Libav.swsGetContext(width, height, format, width, height, LibavAbi.AV_PIX_FMT_RGBA, LibavAbi.SWS_BILINEAR)
        if (swsCtx == MemorySegment.NULL) {
            throw LibavException("sws_getContext refused ${width}x$height format=$format")
        }
        swsWidth = width
        swsHeight = height
        swsFormat = format
        // A fresh context starts from swscale's defaults; force the next
        // ensureColorspaceDetails to reapply the stream's own values.
        swsColorspace = Int.MIN_VALUE
        swsRange = Int.MIN_VALUE

        val bytes = width.toLong() * height * 4
        rgbaNative = arena.allocate(bytes)
        rgbaHeap = ByteArray(bytes.toInt())
        // sws_scale takes plane arrays; RGBA is single-plane, slots 1..7 NULL/0.
        dstData = arena.allocate(ADDRESS, 8)
        dstData.setAtIndex(ADDRESS, 0, rgbaNative)
        dstStride = arena.allocate(JAVA_INT, 8)
        dstStride.setAtIndex(JAVA_INT, 0, width * 4)
    }

    /**
     * Keeps the conversion's YUV matrix and sample range in step with what
     * the frames declare. Without this swscale converts everything with
     * its BT.601/limited defaults -- subtly wrong colors on every BT.709
     * (HD) file, crushed levels on full-range streams.
     */
    private fun ensureColorspaceDetails(width: Int, height: Int) {
        val colorspace = frame.get(JAVA_INT, LibavAbi.Frame.COLORSPACE)
        val range = frame.get(JAVA_INT, LibavAbi.Frame.COLOR_RANGE)
        if (colorspace == swsColorspace && range == swsRange) return
        swsColorspace = colorspace
        swsRange = range
        val coefficients = Libav.swsGetCoefficients(swsCoefficientsFor(colorspace, width, height))
        val srcFullRange = if (range == LibavAbi.AVCOL_RANGE_JPEG) 1 else 0
        // RGBA output is always full range. Sources without a YUV matrix
        // (paletted gif, rgba apng) answer a negative and swscale keeps
        // its defaults, which is correct there.
        Libav.swsSetColorspaceDetails(swsCtx, coefficients, srcFullRange, coefficients, 1, 0, SWS_UNIT, SWS_UNIT)
    }

    override fun close() {
        if (swsCtx != MemorySegment.NULL) Libav.swsFreeContext(swsCtx)
        // The free functions take T** and null the pointer; one scratch slot.
        val ptrPtr = arena.allocate(ADDRESS)
        ptrPtr.set(ADDRESS, 0, frame)
        Libav.avFrameFree(ptrPtr)
        ptrPtr.set(ADDRESS, 0, packet)
        Libav.avPacketFree(ptrPtr)
        ptrPtr.set(ADDRESS, 0, codecCtx)
        Libav.avcodecFreeContext(ptrPtr)
        ptrPtr.set(ADDRESS, 0, fmtCtx)
        Libav.avformatCloseInput(ptrPtr)
        arena.close()
    }

    companion object {

        private val NO_PIXELS = ByteArray(0)

        /** 1.0 in swscale's 16.16 fixed point (brightness/contrast/saturation). */
        private const val SWS_UNIT = 1 shl 16

        /**
         * The native vp8/vp9 decoders ignore the webm alpha side-channel
         * (BlockAdditional), silently flattening transparent video to
         * opaque. libvpx decodes those streams straight to yuva420p, so
         * prefer it when present; absent libvpx (a build without it), the
         * default decoder still plays the stream -- just without alpha.
         */
        private fun pickDecoder(arena: Arena, codecpar: MemorySegment, defaultDecoder: MemorySegment): MemorySegment {
            val libvpxName = when (codecpar.get(JAVA_INT, LibavAbi.CodecParameters.CODEC_ID)) {
                LibavAbi.AV_CODEC_ID_VP8 -> "libvpx"
                LibavAbi.AV_CODEC_ID_VP9 -> "libvpx-vp9"
                else -> return defaultDecoder
            }
            val libvpx = Libav.avcodecFindDecoderByName(arena.allocateFrom(libvpxName))
            return if (libvpx == MemorySegment.NULL) defaultDecoder else libvpx
        }

        /** Opens [path] and prepares a decoder for its best video stream. */
        fun open(path: Path): VideoDecoder {
            val arena = Arena.ofConfined()
            var fmtCtx = MemorySegment.NULL
            var codecCtx = MemorySegment.NULL
            try {
                val ctxOut = arena.allocate(ADDRESS)
                Libav.checkAv(
                    Libav.avformatOpenInput(ctxOut, arena.allocateFrom(path.toString())),
                    "avformat_open_input($path)",
                )
                fmtCtx = ctxOut.get(ADDRESS, 0).reinterpret(LibavAbi.FormatContext.SIZEOF)
                Libav.checkAv(Libav.avformatFindStreamInfo(fmtCtx), "avformat_find_stream_info")

                val decoderOut = arena.allocate(ADDRESS)
                val streamIndex = Libav.checkAv(
                    Libav.avFindBestStream(fmtCtx, LibavAbi.AVMEDIA_TYPE_VIDEO, decoderOut),
                    "av_find_best_stream(video)",
                )
                val stream = streamAt(fmtCtx, streamIndex)
                if (stream.get(JAVA_INT, LibavAbi.Stream.DISPOSITION) and LibavAbi.AV_DISPOSITION_ATTACHED_PIC != 0) {
                    // The only "video" is the cover art (an mp3/flac with a
                    // picture): playing it would end the player at its one
                    // frame while the sound runs on. Refuse, so the player
                    // takes the frameless path; the cover ships as bytes.
                    throw LibavException("the only video stream of $path is an attached picture")
                }
                val timeBaseNum = stream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE)
                val timeBaseDen = stream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE + 4)
                val codecpar = stream.get(ADDRESS, LibavAbi.Stream.CODECPAR)
                    .reinterpret(LibavAbi.CodecParameters.SIZEOF)

                val decoder = pickDecoder(arena, codecpar, decoderOut.get(ADDRESS, 0))
                codecCtx = Libav.avcodecAllocContext3(decoder)
                if (codecCtx == MemorySegment.NULL) throw LibavException("avcodec_alloc_context3 returned NULL")
                Libav.checkAv(Libav.avcodecParametersToContext(codecCtx, codecpar), "avcodec_parameters_to_context")
                // The context defaults to a single decode thread; "auto"
                // sizes to the machine. On 1080p AV1 that is the difference
                // between a multi-second seek landing and a sub-second one.
                Libav.checkAv(
                    Libav.avOptSet(codecCtx, arena.allocateFrom("threads"), arena.allocateFrom("auto")),
                    "av_opt_set(threads)",
                )
                Libav.checkAv(Libav.avcodecOpen2(codecCtx, decoder), "avcodec_open2")

                val packet = Libav.avPacketAlloc().reinterpret(LibavAbi.Packet.SIZEOF)
                val frame = Libav.avFrameAlloc().reinterpret(LibavAbi.Frame.SIZEOF)
                if (packet == MemorySegment.NULL || frame == MemorySegment.NULL) {
                    throw LibavException("av_packet_alloc/av_frame_alloc returned NULL")
                }

                val duration = containerDurationNanos(fmtCtx, stream, timeBaseNum, timeBaseDen)
                return VideoDecoder(
                    arena, fmtCtx, codecCtx, packet, frame, streamIndex, timeBaseNum, timeBaseDen,
                    duration,
                    containerTags(fmtCtx, arena),
                    containerChapters(fmtCtx, arena),
                    attachedCoverArt(fmtCtx),
                    displayRotationDegrees(codecpar),
                )
            } catch (t: Throwable) {
                val ptrPtr = arena.allocate(ADDRESS)
                if (codecCtx != MemorySegment.NULL) {
                    ptrPtr.set(ADDRESS, 0, codecCtx)
                    Libav.avcodecFreeContext(ptrPtr)
                }
                if (fmtCtx != MemorySegment.NULL) {
                    ptrPtr.set(ADDRESS, 0, fmtCtx)
                    Libav.avformatCloseInput(ptrPtr)
                }
                arena.close()
                throw t
            }
        }
    }
}

/**
 * The SWS_CS_* coefficient table for a frame's declared matrix. Streams
 * that declare nothing take the convention players agree on: HD geometry
 * means BT.709, smaller means BT.601.
 */
internal fun swsCoefficientsFor(colorspace: Int, width: Int, height: Int): Int = when (colorspace) {
    LibavAbi.AVCOL_SPC_BT709 -> LibavAbi.SWS_CS_ITU709
    LibavAbi.AVCOL_SPC_FCC -> LibavAbi.SWS_CS_FCC
    LibavAbi.AVCOL_SPC_BT470BG, LibavAbi.AVCOL_SPC_SMPTE170M -> LibavAbi.SWS_CS_ITU601
    LibavAbi.AVCOL_SPC_SMPTE240M -> LibavAbi.SWS_CS_SMPTE240M
    LibavAbi.AVCOL_SPC_BT2020_NCL, LibavAbi.AVCOL_SPC_BT2020_CL -> LibavAbi.SWS_CS_BT2020
    else -> if (width >= 1280 || height >= 720) LibavAbi.SWS_CS_ITU709 else LibavAbi.SWS_CS_ITU601
}

/**
 * Clockwise degrees the consumer must rotate frames for correct display,
 * normalized to 0/90/180/270 -- phone footage carries its orientation as
 * a display-matrix on the stream, and the pixels arrive sideways.
 * av_display_rotation_get reports the matrix's COUNTERclockwise rotation;
 * display applies the inverse. Snapped to the quarter grid: free-angle
 * matrices exist in theory, never in the consumer's files (ROADMAP
 * section 8).
 */
internal fun displayRotationDegrees(codecpar: MemorySegment): Int {
    val sideData = codecpar.get(ADDRESS, LibavAbi.CodecParameters.CODED_SIDE_DATA)
    val count = codecpar.get(JAVA_INT, LibavAbi.CodecParameters.NB_CODED_SIDE_DATA)
    if (sideData == MemorySegment.NULL || count == 0) return 0
    val entry = Libav.avPacketSideDataGet(sideData, count, LibavAbi.AV_PKT_DATA_DISPLAYMATRIX)
    if (entry == MemorySegment.NULL) return 0
    val matrix = entry.reinterpret(LibavAbi.PacketSideData.SIZEOF).get(ADDRESS, LibavAbi.PacketSideData.DATA)
    if (matrix == MemorySegment.NULL) return 0
    val ccw = Libav.avDisplayRotationGet(matrix)
    if (ccw.isNaN()) return 0
    val quarters = Math.round(-ccw / 90.0).toInt()
    return ((quarters % 4) + 4) * 90 % 360
}

/**
 * Container-reported duration: the AVFormatContext value (microseconds)
 * when present, the stream's own (its time_base) as the fallback, null
 * when the container does not know. Unknowns appear as AV_NOPTS or
 * non-positive values depending on the demuxer; both read as null.
 */
internal fun containerDurationNanos(
    fmtCtx: MemorySegment,
    stream: MemorySegment,
    timeBaseNum: Int,
    timeBaseDen: Int,
): Long? {
    val container = fmtCtx.get(JAVA_LONG, LibavAbi.FormatContext.DURATION)
    if (container != LibavAbi.AV_NOPTS_VALUE && container > 0) return container * 1_000L
    val own = stream.get(JAVA_LONG, LibavAbi.Stream.DURATION)
    if (own != LibavAbi.AV_NOPTS_VALUE && own > 0) return ptsToNanos(own, timeBaseNum, timeBaseDen)
    return null
}

/** The stream at [index] of an opened format context. */
internal fun streamAt(fmtCtx: MemorySegment, index: Int): MemorySegment {
    val streams = fmtCtx.get(ADDRESS, LibavAbi.FormatContext.STREAMS)
        .reinterpret((index + 1L) * ADDRESS.byteSize())
    return streams.getAtIndex(ADDRESS, index.toLong()).reinterpret(LibavAbi.Stream.SIZEOF)
}

/** A single value out of an AVDictionary; null for NULL dict or absent key. */
internal fun dictValue(dict: MemorySegment, key: MemorySegment): String? {
    if (dict == MemorySegment.NULL) return null
    val entry = Libav.avDictGet(dict, key)
    if (entry == MemorySegment.NULL) return null
    val value = entry.reinterpret(LibavAbi.DictEntry.SIZEOF).get(ADDRESS, LibavAbi.DictEntry.VALUE)
    return if (value == MemorySegment.NULL) null else value.reinterpret(Long.MAX_VALUE).getString(0)
}

/** The container's format-level tags (title, artist, ...), demuxer-cased. */
internal fun containerTags(fmtCtx: MemorySegment, arena: Arena): Map<String, String> {
    val dict = fmtCtx.get(ADDRESS, LibavAbi.FormatContext.METADATA)
    if (dict == MemorySegment.NULL) return emptyMap()
    val emptyKey = arena.allocateFrom("")
    val tags = mutableMapOf<String, String>()
    var entry = MemorySegment.NULL
    while (true) {
        entry = Libav.avDictGet(dict, emptyKey, entry, LibavAbi.AV_DICT_IGNORE_SUFFIX)
        if (entry == MemorySegment.NULL) return tags
        val sized = entry.reinterpret(LibavAbi.DictEntry.SIZEOF)
        val key = sized.get(ADDRESS, LibavAbi.DictEntry.KEY)
        val value = sized.get(ADDRESS, LibavAbi.DictEntry.VALUE)
        if (key != MemorySegment.NULL && value != MemorySegment.NULL) {
            tags[key.reinterpret(Long.MAX_VALUE).getString(0)] = value.reinterpret(Long.MAX_VALUE).getString(0)
        }
    }
}

/** The container's chapter list, titles included. */
internal fun containerChapters(fmtCtx: MemorySegment, arena: Arena): List<Chapter> {
    val count = fmtCtx.get(JAVA_INT, LibavAbi.FormatContext.NB_CHAPTERS)
    if (count == 0) return emptyList()
    val titleKey = arena.allocateFrom("title")
    val array = fmtCtx.get(ADDRESS, LibavAbi.FormatContext.CHAPTERS)
        .reinterpret(count.toLong() * ADDRESS.byteSize())
    val chapters = mutableListOf<Chapter>()
    for (i in 0 until count) {
        val chapter = array.getAtIndex(ADDRESS, i.toLong()).reinterpret(LibavAbi.Chapter.SIZEOF)
        val num = chapter.get(JAVA_INT, LibavAbi.Chapter.TIME_BASE)
        val den = chapter.get(JAVA_INT, LibavAbi.Chapter.TIME_BASE + 4)
        chapters += Chapter(
            startNanos = ptsToNanos(chapter.get(JAVA_LONG, LibavAbi.Chapter.START), num, den),
            endNanos = ptsToNanos(chapter.get(JAVA_LONG, LibavAbi.Chapter.END), num, den),
            title = dictValue(chapter.get(ADDRESS, LibavAbi.Chapter.METADATA), titleKey),
        )
    }
    return chapters
}

/**
 * The first attached picture's encoded bytes (png/jpeg as stored) --
 * the cover art of mp3/flac/m4a. The consumer decodes them; shipping
 * raw bytes keeps the image-decoder choice theirs. Null when none.
 */
internal fun attachedCoverArt(fmtCtx: MemorySegment): ByteArray? {
    for (i in 0 until fmtCtx.get(JAVA_INT, LibavAbi.FormatContext.NB_STREAMS)) {
        val stream = streamAt(fmtCtx, i)
        if (stream.get(JAVA_INT, LibavAbi.Stream.DISPOSITION) and LibavAbi.AV_DISPOSITION_ATTACHED_PIC == 0) continue
        // attached_pic is an AVPacket embedded by value in the stream.
        val size = stream.get(JAVA_INT, LibavAbi.Stream.ATTACHED_PIC + LibavAbi.Packet.SIZE)
        if (size <= 0) continue
        val data = stream.get(ADDRESS, LibavAbi.Stream.ATTACHED_PIC + LibavAbi.Packet.DATA)
        if (data == MemorySegment.NULL) continue
        val bytes = ByteArray(size)
        MemorySegment.copy(data.reinterpret(size.toLong()), JAVA_BYTE, 0, bytes, 0, size)
        return bytes
    }
    return null
}
