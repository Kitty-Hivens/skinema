package dev.hivens.skinema.encode

import dev.hivens.skinema.libav.Libav
import dev.hivens.skinema.libav.LibavAbi
import dev.hivens.skinema.libav.LibavException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.file.Path

/**
 * Video encode parameters. [codecName] is an FFmpeg encoder name
 * ("libx264", "libx265", "libsvtav1", "libvpx-vp9", ...); [options] are
 * its private options ("crf", "preset", ...). The container muxer is
 * inferred from the output file's extension.
 */
class VideoEncodeConfig(
    val codecName: String,
    val width: Int,
    val height: Int,
    /** Nominal frame rate -- the encoder's rate-control hint, not a hard cadence. */
    val fps: Int,
    /** Target bitrate in bits/s; 0 leaves the encoder on its own quality default (crf). */
    val bitRate: Long = 0,
    val options: Map<String, String> = emptyMap(),
)

/**
 * Encodes pushed RGBA8888 frames to a muxed video file -- the inverse of
 * the decode pipeline, the same FFM discipline (one confined [Arena] owned
 * by the calling thread). Each frame is reverse-swscaled RGBA -> YUV420P,
 * encoded, and interleaved into the container; [finish] drains the encoder
 * and writes the trailer. Fail-closed: any libav refusal throws
 * [LibavException], and [close] still releases everything.
 *
 * One software-encoded video stream (M12). Audio and hardware encoders are
 * later milestones. Frames must be pushed in non-decreasing pts order from
 * the one thread that called [open].
 */
class MediaWriter private constructor(
    private val arena: Arena,
    private val fmtCtx: MemorySegment,
    private val codecCtx: MemorySegment,
    private val packet: MemorySegment,
    private val frame: MemorySegment,
    private val width: Int,
    private val height: Int,
    private val streamIndex: Int,
    // The codec time_base is microseconds; the muxer picks the stream
    // time_base at write_header, read back here for the per-packet rescale.
    private val streamTbNum: Int,
    private val streamTbDen: Int,
    private val needsFileIo: Boolean,
) : AutoCloseable {

    private var swsCtx = MemorySegment.NULL
    private var srcData = MemorySegment.NULL
    private var srcStride = MemorySegment.NULL
    private var srcNative = MemorySegment.NULL
    private var finished = false
    private var closed = false

    /**
     * Encodes one RGBA8888 frame ([width] x [height], tightly packed,
     * stride = width*4) presented at [ptsNanos]. Frames must arrive in
     * non-decreasing pts order.
     */
    fun writeFrame(rgba: ByteArray, ptsNanos: Long) {
        check(!finished) { "writeFrame after finish()" }
        require(rgba.size == width * height * 4) { "frame must be ${width * height * 4} RGBA bytes, got ${rgba.size}" }
        ensureSws()
        // Clone-on-write if the encoder still holds the previous frame
        // (B-frame reordering), then fill the YUV planes from the RGBA.
        Libav.checkAv(Libav.avFrameMakeWritable(frame), "av_frame_make_writable")
        MemorySegment.copy(rgba, 0, srcNative, JAVA_BYTE, 0, rgba.size)
        Libav.swsScale(
            swsCtx, srcData, srcStride, 0, height,
            frame.asSlice(LibavAbi.Frame.DATA), frame.asSlice(LibavAbi.Frame.LINESIZE),
        )
        frame.set(JAVA_LONG, LibavAbi.Frame.PTS, ptsNanos / 1_000L)
        Libav.checkAv(Libav.avcodecSendFrame(codecCtx, frame), "avcodec_send_frame")
        drainPackets()
    }

    /** Drains the encoder and writes the container trailer. Call once, before [close]. */
    fun finish() {
        if (finished) return
        finished = true
        Libav.checkAv(Libav.avcodecSendFrame(codecCtx, MemorySegment.NULL), "avcodec_send_frame(flush)")
        drainPackets()
        Libav.checkAv(Libav.avWriteTrailer(fmtCtx), "av_write_trailer")
    }

    private fun drainPackets() {
        while (true) {
            val ret = Libav.avcodecReceivePacket(codecCtx, packet)
            if (ret == LibavAbi.AVERROR_EAGAIN || ret == LibavAbi.AVERROR_EOF) return
            Libav.checkAv(ret, "avcodec_receive_packet")
            rescalePacket()
            packet.set(JAVA_INT, LibavAbi.Packet.STREAM_INDEX, streamIndex)
            val written = Libav.avInterleavedWriteFrame(fmtCtx, packet)
            Libav.avPacketUnref(packet)
            Libav.checkAv(written, "av_interleaved_write_frame")
        }
    }

    // Encoder packets carry pts/dts/duration in the codec's microsecond
    // time_base; the muxer expects the stream's. Rescaled by hand --
    // av_rescale_q would pass AVRational by value, which the bindings avoid.
    private fun rescalePacket() {
        rescaleField(LibavAbi.Packet.PTS)
        rescaleField(LibavAbi.Packet.DTS)
        val dur = packet.get(JAVA_LONG, LibavAbi.Packet.DURATION)
        packet.set(JAVA_LONG, LibavAbi.Packet.DURATION, dur * streamTbDen / (MICROS_DEN * streamTbNum))
    }

    private fun rescaleField(offset: Long) {
        val v = packet.get(JAVA_LONG, offset)
        if (v == LibavAbi.AV_NOPTS_VALUE) return
        packet.set(JAVA_LONG, offset, v * streamTbDen / (MICROS_DEN * streamTbNum))
    }

    private fun ensureSws() {
        if (swsCtx != MemorySegment.NULL) return
        swsCtx = Libav.swsGetContext(
            width, height, LibavAbi.AV_PIX_FMT_RGBA,
            width, height, LibavAbi.AV_PIX_FMT_YUV420P, LibavAbi.SWS_BILINEAR,
        )
        if (swsCtx == MemorySegment.NULL) throw LibavException("sws_getContext(RGBA->YUV420P) refused ${width}x$height")
        srcNative = arena.allocate(width.toLong() * height * 4)
        // sws_scale takes plane arrays; RGBA is single-plane, slots 1..7 stay NULL/0.
        srcData = arena.allocate(ADDRESS, 8)
        srcData.setAtIndex(ADDRESS, 0, srcNative)
        srcStride = arena.allocate(JAVA_INT, 8)
        srcStride.setAtIndex(JAVA_INT, 0, width * 4)
    }

    override fun close() {
        if (closed) return
        closed = true
        if (swsCtx != MemorySegment.NULL) Libav.swsFreeContext(swsCtx)
        val ptrPtr = arena.allocate(ADDRESS)
        ptrPtr.set(ADDRESS, 0, frame)
        Libav.avFrameFree(ptrPtr)
        ptrPtr.set(ADDRESS, 0, packet)
        Libav.avPacketFree(ptrPtr)
        ptrPtr.set(ADDRESS, 0, codecCtx)
        Libav.avcodecFreeContext(ptrPtr)
        if (needsFileIo) {
            ptrPtr.set(ADDRESS, 0, fmtCtx.get(ADDRESS, LibavAbi.FormatContext.PB))
            Libav.avioClosep(ptrPtr)
        }
        Libav.avformatFreeContext(fmtCtx)
        arena.close()
    }

    companion object {

        // The codec time_base denominator: 1/1_000_000, microseconds. A
        // muxer-agnostic, VFR-friendly unit; pts come in as ptsNanos/1000.
        private const val MICROS_DEN = 1_000_000L

        /**
         * Opens [path] for [config]: infers the muxer from the extension,
         * sets up the named encoder, and writes the container header. The
         * returned writer accepts frames until [finish]. Throws (and leaves
         * nothing allocated) when the muxer, encoder or any setup step is
         * refused.
         */
        fun open(path: Path, config: VideoEncodeConfig): MediaWriter {
            require(config.width > 0 && config.height > 0) { "width/height must be positive" }
            require(config.width % 2 == 0 && config.height % 2 == 0) { "YUV420P needs even dimensions" }
            require(config.fps > 0) { "fps must be positive" }
            val arena = Arena.ofConfined()
            var fmtCtx = MemorySegment.NULL
            var codecCtx = MemorySegment.NULL
            var openedIo = false
            try {
                val ctxOut = arena.allocate(ADDRESS)
                Libav.checkAv(
                    Libav.avformatAllocOutputContext2(ctxOut, arena.allocateFrom(path.toString())),
                    "avformat_alloc_output_context2($path)",
                )
                fmtCtx = ctxOut.get(ADDRESS, 0).reinterpret(LibavAbi.FormatContext.SIZEOF)
                val oformat = fmtCtx.get(ADDRESS, LibavAbi.FormatContext.OFORMAT).reinterpret(LibavAbi.OutputFormat.SIZEOF)
                val oFlags = oformat.get(JAVA_INT, LibavAbi.OutputFormat.FLAGS)

                val encoder = Libav.avcodecFindEncoderByName(arena.allocateFrom(config.codecName))
                if (encoder == MemorySegment.NULL) throw LibavException("no encoder named '${config.codecName}'")
                codecCtx = Libav.avcodecAllocContext3(encoder)
                if (codecCtx == MemorySegment.NULL) throw LibavException("avcodec_alloc_context3(encoder) returned NULL")
                val ctx = codecCtx.reinterpret(LibavAbi.CodecContext.SIZEOF)
                ctx.set(JAVA_INT, LibavAbi.CodecContext.WIDTH, config.width)
                ctx.set(JAVA_INT, LibavAbi.CodecContext.HEIGHT, config.height)
                ctx.set(JAVA_INT, LibavAbi.CodecContext.PIX_FMT, LibavAbi.AV_PIX_FMT_YUV420P)
                // Microsecond time_base (VFR-friendly); framerate hints rate control.
                ctx.set(JAVA_INT, LibavAbi.CodecContext.TIME_BASE, 1)
                ctx.set(JAVA_INT, LibavAbi.CodecContext.TIME_BASE + 4, MICROS_DEN.toInt())
                ctx.set(JAVA_INT, LibavAbi.CodecContext.FRAMERATE, config.fps)
                ctx.set(JAVA_INT, LibavAbi.CodecContext.FRAMERATE + 4, 1)
                ctx.set(JAVA_INT, LibavAbi.CodecContext.GOP_SIZE, config.fps * 2)
                if (config.bitRate > 0) ctx.set(JAVA_LONG, LibavAbi.CodecContext.BIT_RATE, config.bitRate)
                // mp4/mov/mkv want codec extradata in the container header, not in-band.
                if (oFlags and LibavAbi.AVFMT_GLOBALHEADER != 0) {
                    val cur = ctx.get(JAVA_INT, LibavAbi.CodecContext.FLAGS)
                    ctx.set(JAVA_INT, LibavAbi.CodecContext.FLAGS, cur or LibavAbi.AV_CODEC_FLAG_GLOBAL_HEADER)
                }
                for ((k, v) in config.options) {
                    Libav.checkAv(
                        Libav.avOptSet(codecCtx, arena.allocateFrom(k), arena.allocateFrom(v), LibavAbi.AV_OPT_SEARCH_CHILDREN),
                        "av_opt_set($k=$v)",
                    )
                }
                Libav.checkAv(Libav.avcodecOpen2(codecCtx, encoder), "avcodec_open2(encoder)")

                val stream = Libav.avformatNewStream(fmtCtx)
                if (stream == MemorySegment.NULL) throw LibavException("avformat_new_stream returned NULL")
                val sized = stream.reinterpret(LibavAbi.Stream.SIZEOF)
                Libav.checkAv(
                    Libav.avcodecParametersFromContext(
                        sized.get(ADDRESS, LibavAbi.Stream.CODECPAR).reinterpret(LibavAbi.CodecParameters.SIZEOF),
                        codecCtx,
                    ),
                    "avcodec_parameters_from_context",
                )
                sized.set(JAVA_INT, LibavAbi.Stream.TIME_BASE, 1)
                sized.set(JAVA_INT, LibavAbi.Stream.TIME_BASE + 4, MICROS_DEN.toInt())
                val streamIndex = sized.get(JAVA_INT, LibavAbi.Stream.INDEX)

                val needsFileIo = oFlags and LibavAbi.AVFMT_NOFILE == 0
                if (needsFileIo) {
                    val pbOut = arena.allocate(ADDRESS)
                    Libav.checkAv(
                        Libav.avioOpen(pbOut, arena.allocateFrom(path.toString()), LibavAbi.AVIO_FLAG_WRITE),
                        "avio_open($path)",
                    )
                    fmtCtx.set(ADDRESS, LibavAbi.FormatContext.PB, pbOut.get(ADDRESS, 0))
                    openedIo = true
                }
                Libav.checkAv(Libav.avformatWriteHeader(fmtCtx), "avformat_write_header")
                // The muxer may have replaced the stream time_base; read it back.
                val stNum = sized.get(JAVA_INT, LibavAbi.Stream.TIME_BASE)
                val stDen = sized.get(JAVA_INT, LibavAbi.Stream.TIME_BASE + 4)

                val packet = Libav.avPacketAlloc().reinterpret(LibavAbi.Packet.SIZEOF)
                val frame = Libav.avFrameAlloc().reinterpret(LibavAbi.Frame.SIZEOF)
                if (packet == MemorySegment.NULL || frame == MemorySegment.NULL) {
                    throw LibavException("av_packet_alloc/av_frame_alloc returned NULL")
                }
                frame.set(JAVA_INT, LibavAbi.Frame.FORMAT, LibavAbi.AV_PIX_FMT_YUV420P)
                frame.set(JAVA_INT, LibavAbi.Frame.WIDTH, config.width)
                frame.set(JAVA_INT, LibavAbi.Frame.HEIGHT, config.height)
                Libav.checkAv(Libav.avFrameGetBuffer(frame, 0), "av_frame_get_buffer(encode)")

                return MediaWriter(
                    arena, fmtCtx, codecCtx, packet, frame,
                    config.width, config.height, streamIndex, stNum, stDen, needsFileIo,
                )
            } catch (t: Throwable) {
                val ptrPtr = arena.allocate(ADDRESS)
                if (codecCtx != MemorySegment.NULL) {
                    ptrPtr.set(ADDRESS, 0, codecCtx)
                    Libav.avcodecFreeContext(ptrPtr)
                }
                if (openedIo) {
                    ptrPtr.set(ADDRESS, 0, fmtCtx.get(ADDRESS, LibavAbi.FormatContext.PB))
                    Libav.avioClosep(ptrPtr)
                }
                if (fmtCtx != MemorySegment.NULL) Libav.avformatFreeContext(fmtCtx)
                arena.close()
                throw t
            }
        }
    }
}
