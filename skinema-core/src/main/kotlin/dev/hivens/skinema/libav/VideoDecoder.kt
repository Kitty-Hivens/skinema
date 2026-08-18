package dev.hivens.skinema.libav

import dev.hivens.skinema.core.nanosToPts
import dev.hivens.skinema.core.ptsToNanos
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One open video file: demux + decode + RGBA conversion, pull-style via
 * [nextFrame]. Spike-grade (M0): best video stream only, software decode,
 * blocking calls. The backing Arena is confined -- the thread that called
 * [open] owns the session, which is the design intent (one decode thread).
 */
class VideoDecoder private constructor(
    private val arena: Arena,
    private var fmtCtx: MemorySegment,
    private val codecCtx: MemorySegment,
    private val packet: MemorySegment,
    private val frame: MemorySegment,
    val streamIndex: Int,
    val timeBaseNum: Int,
    val timeBaseDen: Int,
    private val startTimeNanos: Long,
    private val duration: Long?,
    private val tags: Map<String, String>,
    private val chapters: List<Chapter>,
    private val coverArt: ByteArray?,
    private val rotationDegrees: Int,
    private val subtitleTracks: List<SubtitleTrack>,
    private val videoSize: Pair<Int, Int>?,
    // AV_PIX_FMT_NONE for software; otherwise the GPU surface format decoded
    // frames arrive in, downloaded to a CPU frame before conversion.
    private val hwPixFmt: Int,
    // AVBufferRef* to the hw device this decoder owns, unref'd at close; NULL
    // for software.
    private val hwDeviceCtx: MemorySegment,
    // The custom byte source backing this decoder (freed at close, after the
    // format context); null for a file-Path decoder.
    private val avioSource: AvioSource?,
    // The file this decoder can reopen to restart a demuxer that cannot seek;
    // null when the bytes come from a MediaSource, which seeks on its own.
    private val reopenPath: String?,
) : FrameSource {

    // The container's duration when it declares one, otherwise what a full
    // decode revealed. Animated WebP declares none -- FFmpeg reports N/A --
    // so the only honest number comes from having played it once, which a
    // looping player pays for anyway.
    override fun durationNanos(): Long? = duration ?: observedDuration

    @Volatile
    private var observedDuration: Long? = null
    private var lastFrameEndNanos = 0L
    override fun tags(): Map<String, String> = tags
    override fun chapters(): List<Chapter> = chapters
    override fun coverArt(): ByteArray? = coverArt
    override fun rotationDegrees(): Int = rotationDegrees
    override fun subtitleTracks(): List<SubtitleTrack> = subtitleTracks
    override fun videoSize(): Pair<Int, Int>? = videoSize
    override fun hardwareActive(): Boolean = hwPixFmt != LibavAbi.AV_PIX_FMT_NONE

    /** The GPU surface a device was opened for; AV_PIX_FMT_NONE for software. */
    internal fun negotiatedSurfaceFormat(): Int = hwPixFmt

    /**
     * What the last decoded frame actually arrived in. The pair with
     * [negotiatedSurfaceFormat] is the only proof the negotiation took: a
     * decoder that loses it decodes in software and says nothing, because
     * every other signal is read off the request rather than off a frame.
     */
    internal fun lastFrameFormat(): Int = frame.get(JAVA_INT, LibavAbi.Frame.FORMAT)

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

    // Hardware decode: when [hwPixFmt] is set, decoded frames land in GPU
    // memory and are downloaded into this reused frame before conversion;
    // allocated on the first hw frame.
    private var swFrame = MemorySegment.NULL

    // swscale state, (re)built lazily on the first frame and on any
    // mid-stream geometry/format change.
    private var swsCtx = MemorySegment.NULL
    private var swsWidth = 0
    private var swsHeight = 0
    private var swsFormat = Int.MIN_VALUE
    private var swsColorspace = Int.MIN_VALUE
    private var swsRange = Int.MIN_VALUE
    /**
     * Holds the buffers whose size is the current geometry's, so they can be
     * released when it changes. The session arena cannot free one segment, and
     * these were taken from it: a stream that switches resolution -- MPEG-TS,
     * an adaptive segment boundary -- kept every buffer it had ever used for
     * the life of the decoder, several megabytes a switch and no ceiling. That
     * ends as a native out-of-memory, which arrives as a killed process rather
     * than an exception something could catch.
     */
    private var swsArena = Arena.ofConfined()
    private var dstData = MemorySegment.NULL
    private var dstStride = MemorySegment.NULL
    private var rgbaNative = MemorySegment.NULL
    private var rgbaHeap = ByteArray(0)

    // HDR (PQ/HLG) path: a parallel swscale context outputs 16-bit RGBA64
    // so the transfer is inverted before 8-bit quantization, then a pure-
    // Kotlin ToneMapper writes the SDR 8-bit result into its OWN hdrOutHeap
    // -- never the SDR rgbaHeap, whose size ensureSws caches and early-
    // returns on (a shared buffer would mis-size a later SDR frame). Built
    // lazily on the first HDR frame; hdrFallback latches the SDR path if the
    // 16-bit context cannot be created.
    private var hdrCtx = MemorySegment.NULL
    private var hdrWidth = 0
    private var hdrHeight = 0
    private var hdrFormat = Int.MIN_VALUE
    private var hdrColorspace = Int.MIN_VALUE
    private var hdrRange = Int.MIN_VALUE
    private var hdrTrc = Int.MIN_VALUE
    /** The same, for the tone-mapping path's own geometry-sized buffers. */
    private var hdrArena = Arena.ofConfined()
    private var hdrNative = MemorySegment.NULL
    private var hdrDstData = MemorySegment.NULL
    private var hdrDstStride = MemorySegment.NULL
    private var hdrShorts = ShortArray(0)
    private var hdrOutHeap = ByteArray(0)
    private var toneMapper: ToneMapper? = null
    private var hdrFallback = false

    /**
     * Decodes and converts the next frame; null at end of stream. When
     * [target] is provided and matches the frame's RGBA size it receives
     * the pixels (the caller's buffer pool); otherwise an internal reused
     * buffer backs the result.
     */
    override fun nextFrame(target: ByteArray?, convert: Boolean): RgbaFrame? {
        while (true) {
            when (val ret = Libav.avcodecReceiveFrame(codecCtx, frame)) {
                0 -> {
                    noteFrameEnd()
                    return if (convert) convertCurrentFrame(target) else metadataOnlyFrame()
                }
                LibavAbi.AVERROR_EAGAIN -> feedOnePacket()
                LibavAbi.AVERROR_EOF -> {
                    if (observedDuration == null && lastFrameEndNanos > 0) {
                        observedDuration = lastFrameEndNanos
                    }
                    return null
                }
                else -> Libav.checkAv(ret, "avcodec_receive_frame")
            }
        }
    }

    override fun convertLast(target: ByteArray?): RgbaFrame = convertCurrentFrame(target)

    /**
     * Where the frame just received stops being shown. Read off the frame
     * rather than inferred from the gap to the next one, because the last
     * frame of a stream has no next one -- and on a format whose frames carry
     * unequal durations that gap is not a constant to extrapolate from.
     */
    private fun noteFrameEnd() {
        val d = frame.get(JAVA_LONG, LibavAbi.Frame.DURATION)
        val end = currentPtsNanos(frame) + if (d > 0) ptsToNanos(d, timeBaseNum, timeBaseDen) else 0L
        if (end > lastFrameEndNanos) lastFrameEndNanos = end
    }

    /**
     * The decoded frame's pts and geometry without touching its pixels.
     * Reads the raw decoded [frame] -- a hw frame carries both -- with NO
     * GPU->CPU transfer, keeping the convert=false drop-run cheap on the
     * hardware path too.
     */
    private fun metadataOnlyFrame(): RgbaFrame = RgbaFrame(
        width = frame.get(JAVA_INT, LibavAbi.Frame.WIDTH),
        height = frame.get(JAVA_INT, LibavAbi.Frame.HEIGHT),
        ptsNanos = currentPtsNanos(frame),
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
        // Re-apply the container start_time the timeline was normalized
        // against before handing the target to the demuxer.
        seekToUnit(nanosToPts(ptsNanos + startTimeNanos, timeBaseNum, timeBaseDen))
    }

    /**
     * One whole time-base unit earlier, which is the smallest step that
     * actually moves the demuxer. [nanosToPts] rounds to the nearest unit, so
     * a target expressed a nanosecond earlier maps to the very same unit --
     * and AVSEEK_FLAG_BACKWARD then lands on the keyframe standing on it,
     * which for a step backward is the frame it is trying to get behind.
     */
    override fun seekBefore(ptsNanos: Long) {
        val unit = nanosToPts(ptsNanos + startTimeNanos, timeBaseNum, timeBaseDen) - 1
        seekToUnit(unit.coerceAtLeast(0))
    }

    private fun seekToUnit(ts: Long) {
        val seeked = Libav.avSeekFrame(fmtCtx, streamIndex, ts, LibavAbi.AVSEEK_FLAG_BACKWARD)
        avioSource?.throwIfFailed() // a source error inside the seek upcall, as itself
        Libav.checkAv(seeked, "av_seek_frame")
        Libav.avcodecFlushBuffers(codecCtx)
        draining = false
        restartStage = 0
    }

    /**
     * How far the restart escalation has gone for the seek in flight: 0 before
     * either escape, 1 after the byte rewind, 2 after the demuxer replacement.
     *
     * Armed by every seek and disarmed by the first packet that follows one,
     * which is what keeps an ordinary end of stream an end of stream: a seek
     * that landed somewhere real reads something, and from that packet on a
     * read that fails is the file running out. It used to be armed only by a
     * seek to zero, on the same reasoning applied one step too early -- so
     * the loop wrap worked and every other seek did not. Measured on animated
     * WebP, whose demuxer answers a seek, reports success and stays drained:
     * a scrub to any position but the beginning handed back nothing at all,
     * for the rest of the session.
     */
    private var restartStage = 2

    /**
     * Restarts the demuxer by moving the byte stream back to the beginning.
     *
     * Not every demuxer implements seeking: FFmpeg 9's animated-WebP demuxer
     * accepts av_seek_frame, reports success and leaves the stream drained,
     * which silently turns looping -- seekTo(0) is the loop primitive -- into
     * a single play. Rewinding the bytes and flushing the demuxer's own state
     * restarts it. Reached only after a seek to zero found nothing to read, so
     * a demuxer that seeks properly never takes this path.
     */
    private fun rewindToStart(): Boolean {
        val pb = fmtCtx.get(ADDRESS, LibavAbi.FormatContext.PB)
        if (pb == MemorySegment.NULL) return false
        if (Libav.avioSeek(pb, 0L, 0) < 0) return false
        Libav.avformatFlush(fmtCtx)
        Libav.avcodecFlushBuffers(codecCtx)
        draining = false
        return true
    }

    /**
     * Restarts by replacing the demuxer outright, for the formats where
     * neither seeking nor rewinding the bytes brings one back: FFmpeg 9's
     * animated-WebP demuxer accepts a seek, reports success and stays
     * drained, and flushing it does not help either.
     *
     * Only the demuxer is replaced. The codec context outlives it -- it is
     * independent once opened, and the file has not changed, so the stream it
     * was configured for is the same stream. The hardware device, the frame
     * and packet buffers and the arena are all untouched.
     *
     * Not reachable from a MediaSource decoder: that one seeks through the
     * consumer's own callbacks, and reopening a path it never had is not a
     * thing this can do.
     */
    // Allocated once, not per reopen. A looping animated WebP takes this path
    // on every lap, and a fresh scratch slot and path string each time is a
    // slow leak out of an arena that only frees when the session does.
    private val reopenScratch: MemorySegment by lazy { arena.allocate(ADDRESS) }
    private val reopenPathNative: MemorySegment by lazy { arena.allocateFrom(reopenPath!!) }

    private fun reopenDemuxer(): Boolean {
        val path = reopenPath ?: return false
        val ptrPtr = reopenScratch
        ptrPtr.set(ADDRESS, 0, fmtCtx)
        Libav.avformatCloseInput(ptrPtr)

        // avformat_close_input freed the context; the field still points at
        // it, and close() frees whatever it finds there. Drop the handle
        // before anything can leave this function -- the throw below above
        // all, which a deleted or unmounted file reaches on a format that
        // takes this path every lap. Left dangling it was a double free, and
        // a double free here takes the JVM down rather than raising.
        fmtCtx = MemorySegment.NULL
        val ctxOut = ptrPtr
        if (Libav.avformatOpenInput(ctxOut, reopenPathNative) < 0) {
            // The old context is gone either way; a decoder that cannot
            // reopen its own file is finished, and saying so beats handing
            // back a silently empty stream.
            throw LibavException("reopening $path to restart a non-seekable demuxer failed")
        }
        fmtCtx = ctxOut.get(ADDRESS, 0).reinterpret(LibavAbi.FormatContext.SIZEOF)
        Libav.checkAv(Libav.avformatFindStreamInfo(fmtCtx), "avformat_find_stream_info(reopen)")
        Libav.avcodecFlushBuffers(codecCtx)
        draining = false
        return true
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
            // A seek to the start that found nothing to read gets two
            // escapes, cheapest first: rewind the byte stream, then replace
            // the demuxer. Each is tried once, so a demuxer that seeks
            // properly never reaches either and a broken one cannot spin.
            if (ret < 0 && restartStage < 2) {
                restartStage++
                val restarted = if (restartStage == 1) rewindToStart() else reopenDemuxer()
                if (restarted) continue
            }
            if (ret < 0) {
                // The demuxer stopped: a real EOF, or our AvioSource caught a
                // MediaSource exception and signalled EOF to get off the native
                // stack. Resurface that here so it fails closed, not silently.
                avioSource?.throwIfFailed()
                draining = true
                Libav.checkAv(Libav.avcodecSendPacket(codecCtx, MemorySegment.NULL), "avcodec_send_packet(flush)")
                return
            }
            restartStage = 2
            if (packet.get(JAVA_INT, LibavAbi.Packet.STREAM_INDEX) != streamIndex ||
                packet.get(JAVA_INT, LibavAbi.Packet.SIZE) == 0
            ) {
            // An empty packet is not a packet. avcodec_send_packet takes a
            // NULL one as the flush signal and refuses a zero-length one that
            // still carries a data pointer -- EINVAL, which this loop turned
            // into a decode failure. Formats emit them: Theora writes one per
            // repeated frame, so nine of the ten packets of a static clip are
            // empty and playback died on the second. FFmpeg reports one frame
            // for that file and so do we now, by skipping them the way a
            // packet from another stream is skipped.
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
        val src = mapHwFrame()
        val width = src.get(JAVA_INT, LibavAbi.Frame.WIDTH)
        val height = src.get(JAVA_INT, LibavAbi.Frame.HEIGHT)
        val format = src.get(JAVA_INT, LibavAbi.Frame.FORMAT)
        val trc = src.get(JAVA_INT, LibavAbi.Frame.COLOR_TRC)
        if (!hdrFallback && isHdrTransfer(trc) && ensureHdr(src, width, height, format, trc)) {
            return toneMappedFrame(src, width, height, target)
        }
        ensureSws(width, height, format)
        ensureColorspaceDetails(src, width, height)

        Libav.swsScale(
            swsCtx,
            src.asSlice(LibavAbi.Frame.DATA),
            src.asSlice(LibavAbi.Frame.LINESIZE),
            0,
            height,
            dstData,
            dstStride,
        )
        val out = target?.takeIf { it.size == rgbaHeap.size } ?: rgbaHeap
        MemorySegment.copy(rgbaNative, JAVA_BYTE, 0, out, 0, out.size)
        return RgbaFrame(width, height, currentPtsNanos(src), out)
    }

    /**
     * The frame to read pixels and colour metadata from: the decoded
     * [frame] for software, or -- when hardware decode put it in GPU
     * memory -- a CPU copy downloaded with av_hwframe_transfer_data into
     * the reused [swFrame]. A frame the decoder produced in software anyway
     * (a per-frame hw fallback, a non-hw stream) passes straight through,
     * so swscale always sees a readable software format.
     */
    private fun mapHwFrame(): MemorySegment {
        if (hwPixFmt == LibavAbi.AV_PIX_FMT_NONE) return frame
        if (frame.get(JAVA_INT, LibavAbi.Frame.FORMAT) != hwPixFmt) return frame
        if (swFrame == MemorySegment.NULL) {
            swFrame = Libav.avFrameAlloc().reinterpret(LibavAbi.Frame.SIZEOF)
            if (swFrame == MemorySegment.NULL) throw LibavException("av_frame_alloc(sw) returned NULL")
        }
        Libav.avFrameUnref(swFrame)
        // A download failure is fatal by design (see HwAccel): once decoding
        // is on the GPU there is no in-place software recovery, so it surfaces
        // as a decode error rather than a silent fallback.
        Libav.checkAv(Libav.avHwframeTransferData(swFrame, frame), "av_hwframe_transfer_data")
        // Transfer copies pixels only; pts, colourspace, range and transfer
        // characteristics ride along so HDR detection and the YUV matrix
        // still read the right values off the downloaded frame.
        Libav.avFrameCopyProps(swFrame, frame)
        return swFrame
    }

    private fun currentPtsNanos(src: MemorySegment): Long {
        val pts = src.get(JAVA_LONG, LibavAbi.Frame.PTS)
            .takeIf { it != LibavAbi.AV_NOPTS_VALUE }
            ?: src.get(JAVA_LONG, LibavAbi.Frame.BEST_EFFORT_TIMESTAMP)
        if (pts == LibavAbi.AV_NOPTS_VALUE) return 0L
        // Normalize to a zero origin: a nonzero container start_time (TS,
        // IPTV) offsets every pts, while duration is the unoffset span.
        return (ptsToNanos(pts, timeBaseNum, timeBaseDen) - startTimeNanos).coerceAtLeast(0L)
    }

    private fun ensureSws(width: Int, height: Int, format: Int) {
        if (swsCtx != MemorySegment.NULL && width == swsWidth && height == swsHeight && format == swsFormat) return
        if (swsCtx != MemorySegment.NULL) Libav.swsFreeContext(swsCtx)

        swsCtx = Libav.swsGetContext(width, height, format, width, height, LibavAbi.AV_PIX_FMT_RGBA, LibavAbi.SWS_BILINEAR)
        if (swsCtx == MemorySegment.NULL) {
            throw LibavException("sws_getContext refused ${width}x$height format=$format")
        }
        swsArena.close()
        swsArena = Arena.ofConfined()
        val bytes = width.toLong() * height * 4
        // swscale's packed-output writer emits whole SIMD blocks, rounding the
        // row width up to the block, so it spills past the last row for a width
        // that is not block-aligned (e.g. 1080). Pad the native destination so
        // the spill lands in slack, not the next heap allocation -- an unpadded
        // buffer corrupts the heap, surfacing as an abort far from here.
        rgbaNative = swsArena.allocate(bytes + SWS_WRITE_PADDING)
        rgbaHeap = ByteArray(bytes.toInt())
        // sws_scale takes plane arrays; RGBA is single-plane, slots 1..7 NULL/0.
        dstData = swsArena.allocate(ADDRESS, 8)
        dstData.setAtIndex(ADDRESS, 0, rgbaNative)
        dstStride = swsArena.allocate(JAVA_INT, 8)
        dstStride.setAtIndex(JAVA_INT, 0, width * 4)
        // Cached LAST, once everything it describes exists. Published first,
        // an OutOfMemoryError on the heap buffer above -- 33 MB at 4K, 132 at
        // 8K -- left the cache saying "this context matches" while dstData
        // still pointed into an arena that had just been closed, so every
        // later frame took the early return and died on sws_scale instead.
        swsWidth = width
        swsHeight = height
        swsFormat = format
        // A fresh context starts from swscale's defaults; force the next
        // ensureColorspaceDetails to reapply the stream's own values.
        swsColorspace = Int.MIN_VALUE
        swsRange = Int.MIN_VALUE
    }

    /**
     * Keeps the conversion's YUV matrix and sample range in step with what
     * the frames declare. Without this swscale converts everything with
     * its BT.601/limited defaults -- subtly wrong colors on every BT.709
     * (HD) file, crushed levels on full-range streams.
     */
    private fun ensureColorspaceDetails(src: MemorySegment, width: Int, height: Int) {
        val colorspace = src.get(JAVA_INT, LibavAbi.Frame.COLORSPACE)
        val range = src.get(JAVA_INT, LibavAbi.Frame.COLOR_RANGE)
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

    /** PQ and HLG are the only transfers the tone-mapper handles. */
    private fun isHdrTransfer(trc: Int): Boolean =
        trc == LibavAbi.AVCOL_TRC_SMPTE2084 || trc == LibavAbi.AVCOL_TRC_ARIB_STD_B67

    /**
     * Prepares the HDR path for the current frame: a parallel swscale
     * context that outputs 16-bit RGBA64 (so the transfer can be inverted
     * before 8-bit quantization), the matching BT.2020 colourspace details,
     * the staging buffers, and a [ToneMapper] for the transfer. Returns
     * false -- and latches [hdrFallback] -- if the 16-bit context cannot be
     * built, so playback degrades to the (washed-out) SDR path instead of
     * failing. Rebuilds on any geometry/format/colour change, like ensureSws.
     */
    private fun ensureHdr(src: MemorySegment, width: Int, height: Int, format: Int, trc: Int): Boolean {
        val colorspace = src.get(JAVA_INT, LibavAbi.Frame.COLORSPACE)
        val range = src.get(JAVA_INT, LibavAbi.Frame.COLOR_RANGE)
        if (hdrCtx != MemorySegment.NULL && width == hdrWidth && height == hdrHeight && format == hdrFormat &&
            trc == hdrTrc && colorspace == hdrColorspace && range == hdrRange
        ) {
            return true
        }
        if (hdrCtx != MemorySegment.NULL) {
            Libav.swsFreeContext(hdrCtx)
            hdrCtx = MemorySegment.NULL
        }
        val ctx = Libav.swsGetContext(
            width, height, format, width, height, LibavAbi.AV_PIX_FMT_RGBA64LE, LibavAbi.SWS_BILINEAR,
        )
        if (ctx == MemorySegment.NULL) return fallBackFromHdr("sws_getContext(RGBA64) refused")
        // The 16-bit context needs the same matrix + range as the 8-bit one;
        // swscale converts the matrix, never the transfer (that is the
        // ToneMapper's job). Without this the YUV->RGB range is wrong and
        // every decoded nit is off.
        val coefficients = Libav.swsGetCoefficients(swsCoefficientsFor(colorspace, width, height))
        val srcFullRange = if (range == LibavAbi.AVCOL_RANGE_JPEG) 1 else 0
        if (Libav.swsSetColorspaceDetails(ctx, coefficients, srcFullRange, coefficients, 1, 0, SWS_UNIT, SWS_UNIT) < 0) {
            Libav.swsFreeContext(ctx)
            return fallBackFromHdr("sws_setColorspaceDetails(RGBA64) refused")
        }
        hdrCtx = ctx
        val pixels = width.toLong() * height
        hdrArena.close()
        hdrArena = Arena.ofConfined()
        hdrNative = hdrArena.allocate(pixels * 8 + SWS_WRITE_PADDING) // RGBA64 (4ch x 2B) + swscale block spill
        hdrShorts = ShortArray((pixels * 4).toInt())
        hdrOutHeap = ByteArray((pixels * 4).toInt())
        hdrDstData = hdrArena.allocate(ADDRESS, 8)
        hdrDstData.setAtIndex(ADDRESS, 0, hdrNative)
        hdrDstStride = hdrArena.allocate(JAVA_INT, 8)
        hdrDstStride.setAtIndex(JAVA_INT, 0, width * 8)
        toneMapper = ToneMapper(
            if (trc == LibavAbi.AVCOL_TRC_ARIB_STD_B67) ToneMapper.HdrTransfer.HLG else ToneMapper.HdrTransfer.PQ,
        )
        // Cached last, for the reason ensureSws gives.
        hdrWidth = width
        hdrHeight = height
        hdrFormat = format
        hdrTrc = trc
        hdrColorspace = colorspace
        hdrRange = range
        return true
    }

    private fun fallBackFromHdr(reason: String): Boolean {
        hdrFallback = true
        System.err.println("[skinema] HDR tone-mapping unavailable ($reason); playing through the SDR path")
        return false
    }

    /** swscale to 16-bit RGBA, then the pure-Kotlin tone-map to 8-bit RGBA. */
    private fun toneMappedFrame(src: MemorySegment, width: Int, height: Int, target: ByteArray?): RgbaFrame {
        Libav.swsScale(
            hdrCtx,
            src.asSlice(LibavAbi.Frame.DATA),
            src.asSlice(LibavAbi.Frame.LINESIZE),
            0,
            height,
            hdrDstData,
            hdrDstStride,
        )
        MemorySegment.copy(hdrNative, JAVA_SHORT, 0, hdrShorts, 0, hdrShorts.size)
        val out = target?.takeIf { it.size == hdrOutHeap.size } ?: hdrOutHeap
        checkNotNull(toneMapper).toneMap(hdrShorts, out, width * height)
        return RgbaFrame(width, height, currentPtsNanos(src), out)
    }

    /**
     * Idempotent, which AutoCloseable requires and this did not honour. The
     * scaler contexts are released before the arena is touched and were not
     * cleared, so a second call freed them again: a double free that aborts
     * the JVM rather than throwing. The later frees are shielded by the arena
     * refusing a closed session, which is why only the first two ever bit --
     * and why the guard belongs here rather than on each of them.
     */
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        swsArena.close()
        hdrArena.close()
        if (swsCtx != MemorySegment.NULL) Libav.swsFreeContext(swsCtx)
        if (hdrCtx != MemorySegment.NULL) Libav.swsFreeContext(hdrCtx)
        // The free functions take T** and null the pointer; one scratch slot.
        val ptrPtr = arena.allocate(ADDRESS)
        ptrPtr.set(ADDRESS, 0, frame)
        Libav.avFrameFree(ptrPtr)
        if (swFrame != MemorySegment.NULL) {
            ptrPtr.set(ADDRESS, 0, swFrame)
            Libav.avFrameFree(ptrPtr)
        }
        ptrPtr.set(ADDRESS, 0, packet)
        Libav.avPacketFree(ptrPtr)
        ptrPtr.set(ADDRESS, 0, codecCtx)
        Libav.avcodecFreeContext(ptrPtr)
        // The codec held its own ref to the hw device (released just above);
        // release the one this decoder kept.
        if (hwDeviceCtx != MemorySegment.NULL) {
            ptrPtr.set(ADDRESS, 0, hwDeviceCtx)
            Libav.avBufferUnref(ptrPtr)
        }
        ptrPtr.set(ADDRESS, 0, fmtCtx)
        Libav.avformatCloseInput(ptrPtr)
        // After the demuxer let go of the pb, release the custom AVIO (if any).
        avioSource?.free(ptrPtr)
        arena.close()
    }

    companion object {

        private val NO_PIXELS = ByteArray(0)

        /**
         * Slack after a swscale destination. Its packed writer can spill up to
         * one 16-pixel SIMD block past a non-block-aligned width; sized for the
         * widest output (RGBA64, 8 B/px -> 128 B), so both the RGBA and RGBA64
         * targets are covered.
         */
        private const val SWS_WRITE_PADDING = 128L

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
            // libvpx is preferred for ONE reason -- the webm alpha
            // side-channel, which the native decoders drop -- and that is an
            // eight-bit yuva420p feature. Past those two formats the
            // preference only costs: libvpx decodes ten and twelve bits only
            // when it was configured with --enable-vp9-highbitdepth, the
            // shipped bundle was not, and forcing it there refused the stream
            // outright where FFmpeg's own decoder, compiled in beside it,
            // reads it. Ten-bit VP9 did not play at all.
            val format = codecpar.get(JAVA_INT, LibavAbi.CodecParameters.FORMAT)
            if (format != LibavAbi.AV_PIX_FMT_YUV420P && format != LibavAbi.AV_PIX_FMT_YUVA420P) {
                return defaultDecoder
            }
            val libvpx = Libav.avcodecFindDecoderByName(arena.allocateFrom(libvpxName))
            return if (libvpx == MemorySegment.NULL) defaultDecoder else libvpx
        }

        private class HwSetup(val pixFmt: Int, val deviceCtx: MemorySegment)

        /**
         * The platform's hw device types, best first (see [HwAccel]).
         *
         * The list is forward-looking: the prebuilt natives bundle currently
         * carries only VAAPI (Linux), D3D11VA + DXVA2 (Windows) and
         * VideoToolbox (macOS) -- NVDEC/CUDA and QSV need extra SDKs and are a
         * build follow-up (ROADMAP M11). CUDA stays listed so a consumer's own
         * bundle (custom FEATURES) or a system FFmpeg that builds it works
         * unchanged; against the stock bundle a type that was not built simply
         * fails to open and the next one is tried, or AUTO falls to software.
         * So on an NVIDIA-only Linux box with the stock bundle, AUTO decodes in
         * software (hardwareActive = false) -- the contract holds, the bundle
         * just has no driver it can use yet.
         */
        private fun preferredHwTypes(): IntArray = when (Os.current()) {
            // VAAPI before NVDEC on Linux: Intel/AMD, no proprietary driver.
            Os.LINUX -> intArrayOf(LibavAbi.AV_HWDEVICE_TYPE_VAAPI, LibavAbi.AV_HWDEVICE_TYPE_CUDA)
            Os.WINDOWS -> intArrayOf(LibavAbi.AV_HWDEVICE_TYPE_D3D11VA, LibavAbi.AV_HWDEVICE_TYPE_DXVA2)
            Os.MAC -> intArrayOf(LibavAbi.AV_HWDEVICE_TYPE_VIDEOTOOLBOX)
        }

        /**
         * The hw surface format a decoder exposes for [deviceType] through
         * the hw_device_ctx method, or AV_PIX_FMT_NONE when it offers none.
         */
        private fun decoderHwPixFmt(decoder: MemorySegment, deviceType: Int): Int {
            var i = 0
            while (true) {
                val config = Libav.avcodecGetHwConfig(decoder, i)
                if (config == MemorySegment.NULL) return LibavAbi.AV_PIX_FMT_NONE
                val sized = config.reinterpret(LibavAbi.CodecHWConfig.SIZEOF)
                val methods = sized.get(JAVA_INT, LibavAbi.CodecHWConfig.METHODS)
                val type = sized.get(JAVA_INT, LibavAbi.CodecHWConfig.DEVICE_TYPE)
                if (methods and LibavAbi.AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX != 0 && type == deviceType) {
                    return sized.get(JAVA_INT, LibavAbi.CodecHWConfig.PIX_FMT)
                }
                i++
            }
        }

        /**
         * Tries each platform device type until one both decodes this codec
         * and opens a device, then wires that device and the get_format
         * negotiation upcall into [codecCtx]. Returns the hw surface format
         * to expect on decoded frames; AV_PIX_FMT_NONE (software) when none
         * opens -- unless [hardware] is REQUIRE, which throws instead. Only
         * a successful device touches [codecCtx], so AUTO-without-a-device
         * is byte-for-byte the software path.
         */
        private fun setupHwAccel(arena: Arena, codecCtx: MemorySegment, decoder: MemorySegment, hardware: HwAccel): HwSetup {
            val ctx = codecCtx.reinterpret(LibavAbi.CodecContext.SIZEOF)
            for (type in preferredHwTypes()) {
                val hwPixFmt = decoderHwPixFmt(decoder, type)
                if (hwPixFmt == LibavAbi.AV_PIX_FMT_NONE) continue
                val deviceOut = arena.allocate(ADDRESS)
                if (Libav.avHwdeviceCtxCreate(deviceOut, type) < 0) continue
                val device = deviceOut.get(ADDRESS, 0)
                // The context owns the ref it is handed (avcodec_free_context
                // releases it); we keep the original to unref at close.
                ctx.set(ADDRESS, LibavAbi.CodecContext.HW_DEVICE_CTX, Libav.avBufferRef(device))
                ctx.set(ADDRESS, LibavAbi.CodecContext.GET_FORMAT, Libav.getFormatUpcall())
                // The surface to negotiate for, travelling with the context so
                // the upcall finds it whichever thread avcodec calls it on.
                // Written before avcodec_open2, which is where frame threading
                // clones the context for its workers. Freed with the session
                // arena, which outlives avcodec_free_context.
                val target = arena.allocate(JAVA_INT)
                target.set(JAVA_INT, 0, hwPixFmt)
                ctx.set(ADDRESS, LibavAbi.CodecContext.OPAQUE, target)
                return HwSetup(hwPixFmt, device)
            }
            if (hardware == HwAccel.REQUIRE) {
                throw LibavException("no hardware decoder available for this file (HwAccel.REQUIRE)")
            }
            return HwSetup(LibavAbi.AV_PIX_FMT_NONE, MemorySegment.NULL)
        }

        /** Opens [path] and prepares a decoder for its best video stream. */
        fun open(path: Path, hardware: HwAccel = HwAccel.OFF): VideoDecoder {
            val arena = Arena.ofConfined()
            val fmtCtx = try {
                val ctxOut = arena.allocate(ADDRESS)
                Libav.checkAv(
                    Libav.avformatOpenInput(ctxOut, arena.allocateFrom(path.toString())),
                    "avformat_open_input($path)",
                )
                ctxOut.get(ADDRESS, 0).reinterpret(LibavAbi.FormatContext.SIZEOF)
            } catch (t: Throwable) {
                arena.close()
                throw t
            }
            return openVideo(arena, fmtCtx, null, hardware, path.toString(), path.toString())
        }

        /**
         * Opens a decoder over a custom byte [source] instead of a file --
         * the segment/stream feeding seam. skinema does no I/O of its own;
         * the demuxer pulls bytes through [source]'s callbacks, so
         * --disable-network is untouched.
         */
        fun open(source: MediaSource, hardware: HwAccel = HwAccel.OFF): VideoDecoder {
            val arena = Arena.ofConfined()
            var avioSource: AvioSource? = null
            val fmtCtx = try {
                val avio = AvioSource(arena, source)
                avioSource = avio
                val ctx = Libav.avformatAllocContext()
                if (ctx == MemorySegment.NULL) throw LibavException("avformat_alloc_context returned NULL")
                val sized = ctx.reinterpret(LibavAbi.FormatContext.SIZEOF)
                sized.set(ADDRESS, LibavAbi.FormatContext.PB, avio.context)
                sized.set(
                    JAVA_INT, LibavAbi.FormatContext.FLAGS,
                    sized.get(JAVA_INT, LibavAbi.FormatContext.FLAGS) or LibavAbi.AVFMT_FLAG_CUSTOM_IO,
                )
                val ctxOut = arena.allocate(ADDRESS)
                ctxOut.set(ADDRESS, 0, sized)
                // url NULL: the input is the custom pb, not a path.
                Libav.checkAv(Libav.avformatOpenInput(ctxOut, MemorySegment.NULL), "avformat_open_input(custom source)")
                ctxOut.get(ADDRESS, 0).reinterpret(LibavAbi.FormatContext.SIZEOF)
            } catch (t: Throwable) {
                // A failed open frees the format context itself; the avio
                // context, its buffer and the source are ours to release.
                avioSource?.free(arena.allocate(ADDRESS))
                arena.close()
                throw t
            }
            return openVideo(arena, fmtCtx, avioSource, hardware, "custom source")
        }

        /** Shared tail: an opened [fmtCtx] -> a video decoder, or fail-closed. */
        private fun openVideo(
            arena: Arena,
            fmtCtx: MemorySegment,
            avioSource: AvioSource?,
            hardware: HwAccel,
            label: String,
            reopenPath: String? = null,
        ): VideoDecoder {
            var codecCtx = MemorySegment.NULL
            var hwDevice = MemorySegment.NULL
            // Declared out here so the catch can release them: every throw
            // past their allocation -- the no-dimensions guard, the metadata
            // readers, the duration arithmetic -- used to leak both.
            var packet = MemorySegment.NULL
            var frame = MemorySegment.NULL
            try {
                Libav.checkAv(Libav.avformatFindStreamInfo(fmtCtx), "avformat_find_stream_info")

                val decoderOut = arena.allocate(ADDRESS)
                val best = Libav.avFindBestStream(fmtCtx, LibavAbi.AVMEDIA_TYPE_VIDEO, decoderOut)
                // No stream, or a stream nothing here can decode: both mean
                // there is no picture to show, and the bundle carrying a
                // deliberately narrow decoder set is a supported configuration
                // -- such a file played its sound before and must keep doing
                // so. Anything else negative is a genuine failure.
                if (best == LibavAbi.AVERROR_STREAM_NOT_FOUND || best == LibavAbi.AVERROR_DECODER_NOT_FOUND) {
                    throw NoVideoStreamException("$label carries no video this build can decode")
                }
                val streamIndex = Libav.checkAv(best, "av_find_best_stream(video)")
                val stream = streamAt(fmtCtx, streamIndex)
                if (stream.get(JAVA_INT, LibavAbi.Stream.DISPOSITION) and LibavAbi.AV_DISPOSITION_ATTACHED_PIC != 0) {
                    // The only "video" is the cover art (an mp3/flac with a
                    // picture): playing it would end the player at its one
                    // frame while the sound runs on. Refuse, so the player
                    // takes the frameless path; the cover ships as bytes.
                    throw NoVideoStreamException("the only video stream of $label is an attached picture")
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
                // Wire a hardware device + the get_format upcall before open,
                // or stay on AV_PIX_FMT_NONE (software). REQUIRE turns "no
                // device" into a throw; AUTO falls through to software.
                val hw = if (hardware == HwAccel.OFF) {
                    HwSetup(LibavAbi.AV_PIX_FMT_NONE, MemorySegment.NULL)
                } else {
                    setupHwAccel(arena, codecCtx, decoder, hardware)
                }
                hwDevice = hw.deviceCtx
                Libav.checkAv(Libav.avcodecOpen2(codecCtx, decoder), "avcodec_open2")

                packet = Libav.avPacketAlloc().reinterpret(LibavAbi.Packet.SIZEOF)
                frame = Libav.avFrameAlloc().reinterpret(LibavAbi.Frame.SIZEOF)
                if (packet == MemorySegment.NULL || frame == MemorySegment.NULL) {
                    throw LibavException("av_packet_alloc/av_frame_alloc returned NULL")
                }

                val startTimeNanos = formatStartTimeNanos(fmtCtx)
                val duration = containerDurationNanos(fmtCtx, stream, timeBaseNum, timeBaseDen)
                val codedWidth = codecpar.get(JAVA_INT, LibavAbi.CodecParameters.WIDTH)
                val codedHeight = codecpar.get(JAVA_INT, LibavAbi.CodecParameters.HEIGHT)
                // A video stream still without dimensions once stream info has
                // been probed is one nothing can decode. Truncated and
                // malformed files reach here looking healthy -- the container
                // parses, the stream is found, the codec is known -- and would
                // otherwise open cleanly and then hand back no frames at all,
                // which reads as an empty video rather than a broken file.
                if (codedWidth <= 0 || codedHeight <= 0) {
                    throw LibavException("$label has a video stream with no dimensions -- truncated or corrupt")
                }
                return VideoDecoder(
                    arena, fmtCtx, codecCtx, packet, frame, streamIndex, timeBaseNum, timeBaseDen,
                    startTimeNanos,
                    duration,
                    containerTags(fmtCtx, arena),
                    containerChapters(fmtCtx, arena, startTimeNanos),
                    attachedCoverArt(fmtCtx),
                    displayRotationDegrees(codecpar),
                    enumerateSubtitleTracks(fmtCtx, arena),
                    (codedWidth to codedHeight).takeIf { codedWidth > 0 && codedHeight > 0 },
                    hw.pixFmt,
                    hwDevice,
                    avioSource,
                    reopenPath,
                )
            } catch (t: Throwable) {
                val ptrPtr = arena.allocate(ADDRESS)
                if (codecCtx != MemorySegment.NULL) {
                    ptrPtr.set(ADDRESS, 0, codecCtx)
                    Libav.avcodecFreeContext(ptrPtr)
                }
                if (packet != MemorySegment.NULL) {
                    ptrPtr.set(ADDRESS, 0, packet)
                    Libav.avPacketFree(ptrPtr)
                }
                if (frame != MemorySegment.NULL) {
                    ptrPtr.set(ADDRESS, 0, frame)
                    Libav.avFrameFree(ptrPtr)
                }
                // free_context released the codec's ref; release ours.
                if (hwDevice != MemorySegment.NULL) {
                    ptrPtr.set(ADDRESS, 0, hwDevice)
                    Libav.avBufferUnref(ptrPtr)
                }
                if (fmtCtx != MemorySegment.NULL) {
                    ptrPtr.set(ADDRESS, 0, fmtCtx)
                    Libav.avformatCloseInput(ptrPtr)
                }
                avioSource?.free(ptrPtr)
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
private const val DISPLAY_MATRIX_BYTES = 9L * Int.SIZE_BYTES

internal fun displayRotationDegrees(codecpar: MemorySegment): Int {
    val sideData = codecpar.get(ADDRESS, LibavAbi.CodecParameters.CODED_SIDE_DATA)
    val count = codecpar.get(JAVA_INT, LibavAbi.CodecParameters.NB_CODED_SIDE_DATA)
    if (sideData == MemorySegment.NULL || count == 0) return 0
    val entry = Libav.avPacketSideDataGet(sideData, count, LibavAbi.AV_PKT_DATA_DISPLAYMATRIX)
    if (entry == MemorySegment.NULL) return 0
    val sized = entry.reinterpret(LibavAbi.PacketSideData.SIZEOF)
    val matrix = sized.get(ADDRESS, LibavAbi.PacketSideData.DATA)
    if (matrix == MemorySegment.NULL) return 0
    // av_display_rotation_get takes int32_t[9] and reads all thirty-six
    // bytes unconditionally. The pointer arrives from the container with no
    // length attached and reinterpret does not give it one, so a truncated
    // or hostile entry reads past whatever FFmpeg allocated. FFmpeg guards
    // its own callers the same way (libavutil/dump.c).
    if (sized.get(JAVA_LONG, LibavAbi.PacketSideData.SIZE) < DISPLAY_MATRIX_BYTES) return 0
    val ccw = Libav.avDisplayRotationGet(matrix)
    if (ccw.isNaN()) return 0
    val quarters = Math.round(-ccw / 90.0).toInt()
    return ((quarters % 4) + 4) * 90 % 360
}

/**
 * The container start_time as a non-negative nanosecond offset, 0 when
 * unset. A nonzero start_time (TS captures, some IPTV) means pts are
 * measured from it; subtracting this normalizes the timeline to zero so
 * position runs 0..duration. The same constant shifts every stream, so
 * relative A/V offsets are preserved -- which is why it is the CONTAINER
 * start_time (the min across streams), not each stream's own.
 */
internal fun formatStartTimeNanos(fmtCtx: MemorySegment): Long {
    val startUs = fmtCtx.get(JAVA_LONG, LibavAbi.FormatContext.START_TIME)
    if (startUs == LibavAbi.AV_NOPTS_VALUE) return 0L
    return (startUs * 1_000L).coerceAtLeast(0L)
}

/**
 * Container-reported duration: the AVFormatContext value (microseconds)
 * when present, the stream's own (its time_base) as the fallback, null
 * when the container does not know. Unknowns appear as AV_NOPTS or
 * non-positive values depending on the demuxer; both read as null.
 *
 * The playable SPAN, so that the zero-based position runs 0..span. That is
 * what the value usually already is, and for one family of containers it is
 * not. FFmpeg only computes a duration of its own when the demuxer left one
 * unset, and it computes `end_time - start_time` (libavformat/demux.c,
 * update_stream_timings) -- a span by construction. A demuxer that DID set
 * one is taken verbatim and start_time is never subtracted from it, and
 * Matroska's is the last timestamp rather than the length: measured on a
 * five-second clip muxed with a ten-second offset, mkv and webm declare
 * 15 s where mp4 and mpegts declare 5.
 *
 * The tell is the per-stream duration. Where a container states one, it
 * states a length in that stream's own time base, and the format-level value
 * agrees with it; where none is stated -- matroska -- the format-level value
 * came from the container verbatim and carries the offset with it.
 *
 * The residual: a matroska file whose Duration element really is a length
 * (the spec's reading, and what mkvmerge writes) AND which also carries a
 * nonzero start_time would be understated here by that offset. It is the
 * cheaper way to be wrong. Overstating holds the end of every lap open until
 * media time reaches a mark it never will -- ten seconds of frozen picture
 * per lap, on the measurement above -- while understating ends the lap on
 * time and costs a progress bar the last moments of its travel.
 */
internal fun containerDurationNanos(
    fmtCtx: MemorySegment,
    stream: MemorySegment,
    timeBaseNum: Int,
    timeBaseDen: Int,
): Long? {
    val container = fmtCtx.get(JAVA_LONG, LibavAbi.FormatContext.DURATION)
    if (container != LibavAbi.AV_NOPTS_VALUE && container > 0) {
        val nanos = container * 1_000L
        val start = formatStartTimeNanos(fmtCtx)
        if (start > 0 && nanos > start && !anyStreamDeclaresDuration(fmtCtx)) return nanos - start
        return nanos
    }
    val own = stream.get(JAVA_LONG, LibavAbi.Stream.DURATION)
    if (own != LibavAbi.AV_NOPTS_VALUE && own > 0) return ptsToNanos(own, timeBaseNum, timeBaseDen)
    return null
}

/** Whether any stream states a length of its own -- see [containerDurationNanos]. */
private fun anyStreamDeclaresDuration(fmtCtx: MemorySegment): Boolean {
    for (i in 0 until fmtCtx.get(JAVA_INT, LibavAbi.FormatContext.NB_STREAMS)) {
        val d = streamAt(fmtCtx, i).get(JAVA_LONG, LibavAbi.Stream.DURATION)
        if (d != LibavAbi.AV_NOPTS_VALUE && d > 0) return true
    }
    return false
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

/** The container's chapter list, titles included, on the zero-based timeline. */
internal fun containerChapters(fmtCtx: MemorySegment, arena: Arena, startTimeNanos: Long): List<Chapter> {
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
            startNanos = (ptsToNanos(chapter.get(JAVA_LONG, LibavAbi.Chapter.START), num, den) - startTimeNanos)
                .coerceAtLeast(0L),
            endNanos = (ptsToNanos(chapter.get(JAVA_LONG, LibavAbi.Chapter.END), num, den) - startTimeNanos)
                .coerceAtLeast(0L),
            title = dictValue(chapter.get(ADDRESS, LibavAbi.Chapter.METADATA), titleKey),
        )
    }
    return chapters
}

/** Codec ids whose subtitle decoders emit ASS event lines (text path). */
private val TEXT_SUBTITLE_CODECS = setOf(
    LibavAbi.AV_CODEC_ID_ASS,
    LibavAbi.AV_CODEC_ID_SSA,
    LibavAbi.AV_CODEC_ID_SUBRIP,
    LibavAbi.AV_CODEC_ID_MOV_TEXT,
    LibavAbi.AV_CODEC_ID_WEBVTT,
)

/**
 * Probes a standalone subtitle file (.srt, .ass -- anything libav
 * demuxes); empty on any failure, fail closed. [ids] assigns the
 * player's selection handles (negative for externals).
 */
internal fun probeSubtitleFile(file: Path, ids: (Int) -> Int): List<SubtitleTrack> {
    val arena = Arena.ofConfined()
    try {
        val ctxOut = arena.allocate(ADDRESS)
        if (Libav.avformatOpenInput(ctxOut, arena.allocateFrom(file.toString())) < 0) return emptyList()
        val fmtCtx = ctxOut.get(ADDRESS, 0).reinterpret(LibavAbi.FormatContext.SIZEOF)
        try {
            if (Libav.avformatFindStreamInfo(fmtCtx) < 0) return emptyList()
            return enumerateSubtitleTracks(fmtCtx, arena, externalPath = file, ids = ids)
        } finally {
            val ptrPtr = arena.allocate(ADDRESS)
            ptrPtr.set(ADDRESS, 0, fmtCtx)
            Libav.avformatCloseInput(ptrPtr)
        }
    } catch (_: Throwable) {
        return emptyList()
    } finally {
        arena.close()
    }
}

/**
 * The container's subtitle streams. Embedded tracks use their stream
 * index as the id; external files get player-assigned negatives via
 * [ids]. Attachment streams (fonts) are a different codec type and
 * never appear here.
 */
internal fun enumerateSubtitleTracks(
    fmtCtx: MemorySegment,
    arena: Arena,
    externalPath: Path? = null,
    ids: (Int) -> Int = { it },
): List<SubtitleTrack> {
    val languageKey = arena.allocateFrom("language")
    val titleKey = arena.allocateFrom("title")
    val tracks = mutableListOf<SubtitleTrack>()
    for (i in 0 until fmtCtx.get(JAVA_INT, LibavAbi.FormatContext.NB_STREAMS)) {
        val stream = streamAt(fmtCtx, i)
        val codecpar = stream.get(ADDRESS, LibavAbi.Stream.CODECPAR)
            .reinterpret(LibavAbi.CodecParameters.SIZEOF)
        if (codecpar.get(JAVA_INT, LibavAbi.CodecParameters.CODEC_TYPE) != LibavAbi.AVMEDIA_TYPE_SUBTITLE) continue
        val codecId = codecpar.get(JAVA_INT, LibavAbi.CodecParameters.CODEC_ID)
        val metadata = stream.get(ADDRESS, LibavAbi.Stream.METADATA)
        val disposition = stream.get(JAVA_INT, LibavAbi.Stream.DISPOSITION)
        tracks += SubtitleTrack(
            id = ids(i),
            streamIndex = i,
            language = dictValue(metadata, languageKey),
            title = dictValue(metadata, titleKey),
            codecName = Libav.avcodecGetName(codecId).reinterpret(Long.MAX_VALUE).getString(0),
            isText = codecId in TEXT_SUBTITLE_CODECS,
            isDefault = disposition and LibavAbi.AV_DISPOSITION_DEFAULT != 0,
            isForced = disposition and LibavAbi.AV_DISPOSITION_FORCED != 0,
            externalPath = externalPath,
        )
    }
    return tracks
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
