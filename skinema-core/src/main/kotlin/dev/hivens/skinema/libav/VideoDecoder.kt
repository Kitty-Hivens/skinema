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
) : FrameSource {

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

        val bytes = width.toLong() * height * 4
        rgbaNative = arena.allocate(bytes)
        rgbaHeap = ByteArray(bytes.toInt())
        // sws_scale takes plane arrays; RGBA is single-plane, slots 1..7 NULL/0.
        dstData = arena.allocate(ADDRESS, 8)
        dstData.setAtIndex(ADDRESS, 0, rgbaNative)
        dstStride = arena.allocate(JAVA_INT, 8)
        dstStride.setAtIndex(JAVA_INT, 0, width * 4)
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
                val streams = fmtCtx.get(ADDRESS, LibavAbi.FormatContext.STREAMS)
                    .reinterpret((streamIndex + 1L) * ADDRESS.byteSize())
                val stream = streams.getAtIndex(ADDRESS, streamIndex.toLong())
                    .reinterpret(LibavAbi.Stream.SIZEOF)
                val timeBaseNum = stream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE)
                val timeBaseDen = stream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE + 4)
                val codecpar = stream.get(ADDRESS, LibavAbi.Stream.CODECPAR)
                    .reinterpret(LibavAbi.CodecParameters.SIZEOF)

                val decoder = pickDecoder(arena, codecpar, decoderOut.get(ADDRESS, 0))
                codecCtx = Libav.avcodecAllocContext3(decoder)
                if (codecCtx == MemorySegment.NULL) throw LibavException("avcodec_alloc_context3 returned NULL")
                Libav.checkAv(Libav.avcodecParametersToContext(codecCtx, codecpar), "avcodec_parameters_to_context")
                Libav.checkAv(Libav.avcodecOpen2(codecCtx, decoder), "avcodec_open2")

                val packet = Libav.avPacketAlloc().reinterpret(LibavAbi.Packet.SIZEOF)
                val frame = Libav.avFrameAlloc().reinterpret(LibavAbi.Frame.SIZEOF)
                if (packet == MemorySegment.NULL || frame == MemorySegment.NULL) {
                    throw LibavException("av_packet_alloc/av_frame_alloc returned NULL")
                }

                return VideoDecoder(arena, fmtCtx, codecCtx, packet, frame, streamIndex, timeBaseNum, timeBaseDen)
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
