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
 * Audio encode parameters. [codecName] is an FFmpeg encoder name ("aac",
 * "libopus", "flac", ...). Input is interleaved S16LE STEREO at
 * [sampleRate] -- the [dev.hivens.skinema.libav.AudioDecoder] output shape
 * -- encoded at the same rate (no resampling, only an S16 -> the encoder's
 * sample format conversion). [options] are the encoder's private options.
 */
class AudioEncodeConfig(
    val codecName: String,
    val sampleRate: Int,
    /** Target bitrate in bits/s; 0 leaves the encoder on its own default. */
    val bitRate: Long = 0,
    val options: Map<String, String> = emptyMap(),
)

/**
 * Encodes pushed RGBA8888 frames (and optional S16LE stereo PCM) to a
 * muxed file -- the inverse of the decode pipeline, the same FFM
 * discipline (one confined [Arena] owned by the calling thread). Video is
 * reverse-swscaled RGBA -> YUV420P; audio is reverse-swresampled S16 ->
 * the encoder's planar format and chunked to the encoder's frame size. The
 * muxer interleaves both streams by dts; [finish] drains the encoders and
 * writes the trailer. Fail-closed: any libav refusal throws
 * [LibavException], and [close] still releases everything.
 *
 * Software encode (M12); hardware encoders are a later milestone. Frames
 * and samples are pushed from the one thread that called [open].
 */
class MediaWriter private constructor(
    private val arena: Arena,
    private val fmtCtx: MemorySegment,
    private val packet: MemorySegment,
    private val video: VideoTrack,
    private val audio: AudioTrack?,
    private val needsFileIo: Boolean,
) : AutoCloseable {

    private var finished = false
    private var closed = false

    /**
     * Encodes one RGBA8888 frame ([VideoEncodeConfig.width] x height,
     * tightly packed) presented at [ptsNanos]. Frames must arrive in
     * non-decreasing pts order.
     */
    fun writeFrame(rgba: ByteArray, ptsNanos: Long) {
        check(!finished) { "writeFrame after finish()" }
        video.send(rgba, ptsNanos)
        drain(video.codecCtx, video.streamIndex, MICROS_DEN, video.streamTbNum, video.streamTbDen)
    }

    /**
     * Appends interleaved S16LE stereo PCM for the audio stream, encoding
     * whole encoder frames as they fill. Time is the running sample count
     * from the first sample; pushed in order. No-op-safe only when an
     * [AudioEncodeConfig] was given to [open] -- otherwise throws.
     */
    fun writeAudio(pcm: ByteArray) {
        check(!finished) { "writeAudio after finish()" }
        val a = audio ?: throw LibavException("this MediaWriter has no audio stream")
        a.append(pcm)
        while (a.hasFullFrame()) {
            a.send(a.frameSize)
            drain(a.codecCtx, a.streamIndex, a.sampleRate, a.streamTbNum, a.streamTbDen)
        }
    }

    /** Drains both encoders and writes the container trailer. Call once, before [close]. */
    fun finish() {
        if (finished) return
        finished = true
        Libav.checkAv(Libav.avcodecSendFrame(video.codecCtx, MemorySegment.NULL), "avcodec_send_frame(video flush)")
        drain(video.codecCtx, video.streamIndex, MICROS_DEN, video.streamTbNum, video.streamTbDen)
        audio?.let { a ->
            // A short final frame, then the flush packet.
            a.remainingSamples()?.let { samples ->
                a.send(samples)
                drain(a.codecCtx, a.streamIndex, a.sampleRate, a.streamTbNum, a.streamTbDen)
            }
            Libav.checkAv(Libav.avcodecSendFrame(a.codecCtx, MemorySegment.NULL), "avcodec_send_frame(audio flush)")
            drain(a.codecCtx, a.streamIndex, a.sampleRate, a.streamTbNum, a.streamTbDen)
        }
        Libav.checkAv(Libav.avWriteTrailer(fmtCtx), "av_write_trailer")
    }

    /**
     * Pulls every ready packet, rescales its timing from the encoder's
     * time_base (1 / [codecTbDen]) to the muxer's stream time_base, stamps
     * the stream index, and interleaves it into the container.
     */
    private fun drain(encCtx: MemorySegment, streamIndex: Int, codecTbDen: Int, streamTbNum: Int, streamTbDen: Int) {
        while (true) {
            val ret = Libav.avcodecReceivePacket(encCtx, packet)
            if (ret == LibavAbi.AVERROR_EAGAIN || ret == LibavAbi.AVERROR_EOF) return
            Libav.checkAv(ret, "avcodec_receive_packet")
            rescaleField(LibavAbi.Packet.PTS, codecTbDen, streamTbNum, streamTbDen)
            rescaleField(LibavAbi.Packet.DTS, codecTbDen, streamTbNum, streamTbDen)
            val dur = packet.get(JAVA_LONG, LibavAbi.Packet.DURATION)
            packet.set(JAVA_LONG, LibavAbi.Packet.DURATION, dur * streamTbDen / (codecTbDen.toLong() * streamTbNum))
            packet.set(JAVA_INT, LibavAbi.Packet.STREAM_INDEX, streamIndex)
            val written = Libav.avInterleavedWriteFrame(fmtCtx, packet)
            Libav.avPacketUnref(packet)
            Libav.checkAv(written, "av_interleaved_write_frame")
        }
    }

    // new = old * (1/codecTbDen) / (streamTbNum/streamTbDen). av_rescale_q
    // would pass AVRational by value, which the bindings avoid.
    private fun rescaleField(offset: Long, codecTbDen: Int, streamTbNum: Int, streamTbDen: Int) {
        val v = packet.get(JAVA_LONG, offset)
        if (v == LibavAbi.AV_NOPTS_VALUE) return
        packet.set(JAVA_LONG, offset, v * streamTbDen / (codecTbDen.toLong() * streamTbNum))
    }

    override fun close() {
        if (closed) return
        closed = true
        val ptrPtr = arena.allocate(ADDRESS)
        video.free(ptrPtr)
        audio?.free(ptrPtr)
        ptrPtr.set(ADDRESS, 0, packet)
        Libav.avPacketFree(ptrPtr)
        if (needsFileIo) {
            ptrPtr.set(ADDRESS, 0, fmtCtx.get(ADDRESS, LibavAbi.FormatContext.PB))
            Libav.avioClosep(ptrPtr)
        }
        Libav.avformatFreeContext(fmtCtx)
        arena.close()
    }

    /** One video stream: RGBA -> YUV420P -> the encoder. */
    private class VideoTrack(
        private val arena: Arena,
        val codecCtx: MemorySegment,
        private val frame: MemorySegment,
        val streamIndex: Int,
        val streamTbNum: Int,
        val streamTbDen: Int,
        private val width: Int,
        private val height: Int,
    ) {
        private var swsCtx = MemorySegment.NULL
        private var srcData = MemorySegment.NULL
        private var srcStride = MemorySegment.NULL
        private var srcNative = MemorySegment.NULL

        fun send(rgba: ByteArray, ptsNanos: Long) {
            require(rgba.size == width * height * 4) { "frame must be ${width * height * 4} RGBA bytes, got ${rgba.size}" }
            if (swsCtx == MemorySegment.NULL) {
                swsCtx = Libav.swsGetContext(
                    width, height, LibavAbi.AV_PIX_FMT_RGBA,
                    width, height, LibavAbi.AV_PIX_FMT_YUV420P, LibavAbi.SWS_BILINEAR,
                )
                if (swsCtx == MemorySegment.NULL) throw LibavException("sws_getContext(RGBA->YUV420P) refused ${width}x$height")
                srcNative = arena.allocate(width.toLong() * height * 4)
                srcData = arena.allocate(ADDRESS, 8).also { it.setAtIndex(ADDRESS, 0, srcNative) }
                srcStride = arena.allocate(JAVA_INT, 8).also { it.setAtIndex(JAVA_INT, 0, width * 4) }
            }
            Libav.checkAv(Libav.avFrameMakeWritable(frame), "av_frame_make_writable(video)")
            MemorySegment.copy(rgba, 0, srcNative, JAVA_BYTE, 0, rgba.size)
            Libav.swsScale(swsCtx, srcData, srcStride, 0, height, frame.asSlice(LibavAbi.Frame.DATA), frame.asSlice(LibavAbi.Frame.LINESIZE))
            frame.set(JAVA_LONG, LibavAbi.Frame.PTS, ptsNanos / MICROS_DEN_L)
            Libav.checkAv(Libav.avcodecSendFrame(codecCtx, frame), "avcodec_send_frame(video)")
        }

        fun free(ptrPtr: MemorySegment) {
            if (swsCtx != MemorySegment.NULL) Libav.swsFreeContext(swsCtx)
            ptrPtr.set(ADDRESS, 0, frame)
            Libav.avFrameFree(ptrPtr)
            ptrPtr.set(ADDRESS, 0, codecCtx)
            Libav.avcodecFreeContext(ptrPtr)
        }
    }

    /** One audio stream: S16LE stereo -> the encoder's planar format, chunked to [frameSize]. */
    private class AudioTrack(
        arena: Arena,
        val codecCtx: MemorySegment,
        private val frame: MemorySegment,
        private val swr: MemorySegment,
        val streamIndex: Int,
        val streamTbNum: Int,
        val streamTbDen: Int,
        val frameSize: Int,
        val sampleRate: Int,
    ) {
        private val inNative = arena.allocate(frameSize.toLong() * BYTES_PER_AUDIO_FRAME)
        private val inPlanes = arena.allocate(ADDRESS, 1).also { it.setAtIndex(ADDRESS, 0, inNative) }
        private var pcm = ByteArray(0)
        private var pcmLen = 0
        private var samplesEncoded = 0L

        fun append(data: ByteArray) {
            if (pcm.size < pcmLen + data.size) pcm = pcm.copyOf(maxOf(pcm.size * 2, pcmLen + data.size))
            System.arraycopy(data, 0, pcm, pcmLen, data.size)
            pcmLen += data.size
        }

        fun hasFullFrame(): Boolean = pcmLen >= frameSize * BYTES_PER_AUDIO_FRAME

        /** Samples left over at the end (a short final frame), or null when none. */
        fun remainingSamples(): Int? = (pcmLen / BYTES_PER_AUDIO_FRAME).takeIf { it > 0 }

        /** Encodes [samples] (<= [frameSize]) frames off the front of the buffer. */
        fun send(samples: Int) {
            Libav.checkAv(Libav.avFrameMakeWritable(frame), "av_frame_make_writable(audio)")
            val bytes = samples * BYTES_PER_AUDIO_FRAME
            MemorySegment.copy(pcm, 0, inNative, JAVA_BYTE, 0, bytes)
            val converted = Libav.swrConvert(swr, frame.asSlice(LibavAbi.Frame.DATA), samples, inPlanes, samples)
            Libav.checkAv(converted, "swr_convert(audio encode)")
            frame.set(JAVA_INT, LibavAbi.Frame.NB_SAMPLES, samples)
            frame.set(JAVA_LONG, LibavAbi.Frame.PTS, samplesEncoded)
            samplesEncoded += samples
            Libav.checkAv(Libav.avcodecSendFrame(codecCtx, frame), "avcodec_send_frame(audio)")
            System.arraycopy(pcm, bytes, pcm, 0, pcmLen - bytes)
            pcmLen -= bytes
        }

        fun free(ptrPtr: MemorySegment) {
            ptrPtr.set(ADDRESS, 0, swr)
            Libav.swrFree(ptrPtr)
            ptrPtr.set(ADDRESS, 0, frame)
            Libav.avFrameFree(ptrPtr)
            ptrPtr.set(ADDRESS, 0, codecCtx)
            Libav.avcodecFreeContext(ptrPtr)
        }
    }

    companion object {

        // The video codec time_base: 1/1_000_000, microseconds (VFR-friendly).
        private const val MICROS_DEN = 1_000_000
        private const val MICROS_DEN_L = 1_000_000L

        /** S16LE stereo: 2 bytes x 2 channels per sample frame. */
        private const val BYTES_PER_AUDIO_FRAME = 4
        private const val OUT_CHANNELS = 2

        /** Encoders that report a variable frame size (0) get this fixed chunk. */
        private const val DEFAULT_AUDIO_FRAME_SIZE = 1024

        /**
         * Opens [path] for [video] (and optional [audio]): infers the muxer
         * from the extension, sets up the named encoder(s), and writes the
         * container header. The writer then accepts frames/samples until
         * [finish]. Throws -- leaving nothing allocated -- when a muxer,
         * encoder or any setup step is refused.
         */
        fun open(path: Path, video: VideoEncodeConfig, audio: AudioEncodeConfig? = null): MediaWriter {
            require(video.width > 0 && video.height > 0) { "width/height must be positive" }
            require(video.width % 2 == 0 && video.height % 2 == 0) { "YUV420P needs even dimensions" }
            require(video.fps > 0) { "fps must be positive" }
            val arena = Arena.ofConfined()
            var fmtCtx = MemorySegment.NULL
            var vCtx = MemorySegment.NULL
            var aCtx = MemorySegment.NULL
            var vFrame = MemorySegment.NULL
            var aFrame = MemorySegment.NULL
            var swr = MemorySegment.NULL
            var packet = MemorySegment.NULL
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
                val globalHeader = oFlags and LibavAbi.AVFMT_GLOBALHEADER != 0

                // -- video stream --
                val vEncoder = Libav.avcodecFindEncoderByName(arena.allocateFrom(video.codecName))
                if (vEncoder == MemorySegment.NULL) throw LibavException("no encoder named '${video.codecName}'")
                vCtx = Libav.avcodecAllocContext3(vEncoder)
                if (vCtx == MemorySegment.NULL) throw LibavException("avcodec_alloc_context3(video) returned NULL")
                val vc = vCtx.reinterpret(LibavAbi.CodecContext.SIZEOF)
                vc.set(JAVA_INT, LibavAbi.CodecContext.WIDTH, video.width)
                vc.set(JAVA_INT, LibavAbi.CodecContext.HEIGHT, video.height)
                vc.set(JAVA_INT, LibavAbi.CodecContext.PIX_FMT, LibavAbi.AV_PIX_FMT_YUV420P)
                vc.set(JAVA_INT, LibavAbi.CodecContext.TIME_BASE, 1)
                vc.set(JAVA_INT, LibavAbi.CodecContext.TIME_BASE + 4, MICROS_DEN)
                vc.set(JAVA_INT, LibavAbi.CodecContext.FRAMERATE, video.fps)
                vc.set(JAVA_INT, LibavAbi.CodecContext.FRAMERATE + 4, 1)
                vc.set(JAVA_INT, LibavAbi.CodecContext.GOP_SIZE, video.fps * 2)
                if (video.bitRate > 0) vc.set(JAVA_LONG, LibavAbi.CodecContext.BIT_RATE, video.bitRate)
                if (globalHeader) vc.set(JAVA_INT, LibavAbi.CodecContext.FLAGS, vc.get(JAVA_INT, LibavAbi.CodecContext.FLAGS) or LibavAbi.AV_CODEC_FLAG_GLOBAL_HEADER)
                applyOptions(arena, vCtx, video.options)
                Libav.checkAv(Libav.avcodecOpen2(vCtx, vEncoder), "avcodec_open2(video)")
                val vStream = newStream(fmtCtx, vCtx, MICROS_DEN)
                val vStreamIndex = vStream.get(JAVA_INT, LibavAbi.Stream.INDEX)

                // -- audio stream (optional) --
                var aStream = MemorySegment.NULL
                var aFrameSize = 0
                if (audio != null) {
                    val aEncoder = Libav.avcodecFindEncoderByName(arena.allocateFrom(audio.codecName))
                    if (aEncoder == MemorySegment.NULL) throw LibavException("no encoder named '${audio.codecName}'")
                    aCtx = Libav.avcodecAllocContext3(aEncoder)
                    if (aCtx == MemorySegment.NULL) throw LibavException("avcodec_alloc_context3(audio) returned NULL")
                    val ac = aCtx.reinterpret(LibavAbi.CodecContext.SIZEOF)
                    ac.set(JAVA_INT, LibavAbi.CodecContext.SAMPLE_RATE, audio.sampleRate)
                    ac.set(JAVA_INT, LibavAbi.CodecContext.SAMPLE_FMT, LibavAbi.AV_SAMPLE_FMT_FLTP)
                    Libav.avChannelLayoutDefault(ac.asSlice(LibavAbi.CodecContext.CH_LAYOUT, LibavAbi.ChannelLayout.SIZEOF), OUT_CHANNELS)
                    if (audio.bitRate > 0) ac.set(JAVA_LONG, LibavAbi.CodecContext.BIT_RATE, audio.bitRate)
                    if (globalHeader) ac.set(JAVA_INT, LibavAbi.CodecContext.FLAGS, ac.get(JAVA_INT, LibavAbi.CodecContext.FLAGS) or LibavAbi.AV_CODEC_FLAG_GLOBAL_HEADER)
                    applyOptions(arena, aCtx, audio.options)
                    Libav.checkAv(Libav.avcodecOpen2(aCtx, aEncoder), "avcodec_open2(audio)")
                    aFrameSize = ac.get(JAVA_INT, LibavAbi.CodecContext.FRAME_SIZE).takeIf { it > 0 } ?: DEFAULT_AUDIO_FRAME_SIZE
                    aStream = newStream(fmtCtx, aCtx, audio.sampleRate)

                    // S16 interleaved stereo -> FLTP stereo, same rate.
                    val outLayout = arena.allocate(LibavAbi.ChannelLayout.SIZEOF).also { Libav.avChannelLayoutDefault(it, OUT_CHANNELS) }
                    val inLayout = arena.allocate(LibavAbi.ChannelLayout.SIZEOF).also { Libav.avChannelLayoutDefault(it, OUT_CHANNELS) }
                    val swrOut = arena.allocate(ADDRESS)
                    Libav.checkAv(
                        Libav.swrAllocSetOpts2(
                            swrOut, outLayout, LibavAbi.AV_SAMPLE_FMT_FLTP, audio.sampleRate,
                            inLayout, LibavAbi.AV_SAMPLE_FMT_S16, audio.sampleRate,
                        ),
                        "swr_alloc_set_opts2(encode)",
                    )
                    swr = swrOut.get(ADDRESS, 0)
                    Libav.checkAv(Libav.swrInit(swr), "swr_init(encode)")

                    aFrame = Libav.avFrameAlloc().reinterpret(LibavAbi.Frame.SIZEOF)
                    if (aFrame == MemorySegment.NULL) throw LibavException("av_frame_alloc(audio) returned NULL")
                    aFrame.set(JAVA_INT, LibavAbi.Frame.FORMAT, LibavAbi.AV_SAMPLE_FMT_FLTP)
                    aFrame.set(JAVA_INT, LibavAbi.Frame.NB_SAMPLES, aFrameSize)
                    aFrame.set(JAVA_INT, LibavAbi.Frame.SAMPLE_RATE, audio.sampleRate)
                    Libav.avChannelLayoutDefault(aFrame.asSlice(LibavAbi.Frame.CH_LAYOUT, LibavAbi.ChannelLayout.SIZEOF), OUT_CHANNELS)
                    Libav.checkAv(Libav.avFrameGetBuffer(aFrame, 0), "av_frame_get_buffer(audio)")
                }

                // -- IO + header (after all streams exist) --
                val needsFileIo = oFlags and LibavAbi.AVFMT_NOFILE == 0
                if (needsFileIo) {
                    val pbOut = arena.allocate(ADDRESS)
                    Libav.checkAv(Libav.avioOpen(pbOut, arena.allocateFrom(path.toString()), LibavAbi.AVIO_FLAG_WRITE), "avio_open($path)")
                    fmtCtx.set(ADDRESS, LibavAbi.FormatContext.PB, pbOut.get(ADDRESS, 0))
                    openedIo = true
                }
                Libav.checkAv(Libav.avformatWriteHeader(fmtCtx), "avformat_write_header")

                vFrame = Libav.avFrameAlloc().reinterpret(LibavAbi.Frame.SIZEOF)
                packet = Libav.avPacketAlloc().reinterpret(LibavAbi.Packet.SIZEOF)
                if (vFrame == MemorySegment.NULL || packet == MemorySegment.NULL) throw LibavException("av_frame_alloc/av_packet_alloc returned NULL")
                vFrame.set(JAVA_INT, LibavAbi.Frame.FORMAT, LibavAbi.AV_PIX_FMT_YUV420P)
                vFrame.set(JAVA_INT, LibavAbi.Frame.WIDTH, video.width)
                vFrame.set(JAVA_INT, LibavAbi.Frame.HEIGHT, video.height)
                Libav.checkAv(Libav.avFrameGetBuffer(vFrame, 0), "av_frame_get_buffer(video)")

                val videoTrack = VideoTrack(
                    arena, vCtx, vFrame, vStreamIndex,
                    vStream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE), vStream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE + 4),
                    video.width, video.height,
                )
                val audioTrack = if (audio == null) null else AudioTrack(
                    arena, aCtx, aFrame, swr, aStream.get(JAVA_INT, LibavAbi.Stream.INDEX),
                    aStream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE), aStream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE + 4),
                    aFrameSize, audio.sampleRate,
                )
                return MediaWriter(arena, fmtCtx, packet, videoTrack, audioTrack, needsFileIo)
            } catch (t: Throwable) {
                val ptrPtr = arena.allocate(ADDRESS)
                for (ctx in listOf(vCtx, aCtx)) if (ctx != MemorySegment.NULL) {
                    ptrPtr.set(ADDRESS, 0, ctx); Libav.avcodecFreeContext(ptrPtr)
                }
                for (f in listOf(vFrame, aFrame)) if (f != MemorySegment.NULL) {
                    ptrPtr.set(ADDRESS, 0, f); Libav.avFrameFree(ptrPtr)
                }
                if (swr != MemorySegment.NULL) { ptrPtr.set(ADDRESS, 0, swr); Libav.swrFree(ptrPtr) }
                if (packet != MemorySegment.NULL) { ptrPtr.set(ADDRESS, 0, packet); Libav.avPacketFree(ptrPtr) }
                if (openedIo) { ptrPtr.set(ADDRESS, 0, fmtCtx.get(ADDRESS, LibavAbi.FormatContext.PB)); Libav.avioClosep(ptrPtr) }
                if (fmtCtx != MemorySegment.NULL) Libav.avformatFreeContext(fmtCtx)
                arena.close()
                throw t
            }
        }

        private fun newStream(fmtCtx: MemorySegment, codecCtx: MemorySegment, tbDen: Int): MemorySegment {
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
            // The muxer's time_base hint; it may override at write_header,
            // which is why the tracks read it back afterwards.
            sized.set(JAVA_INT, LibavAbi.Stream.TIME_BASE, 1)
            sized.set(JAVA_INT, LibavAbi.Stream.TIME_BASE + 4, tbDen)
            return sized
        }

        private fun applyOptions(arena: Arena, codecCtx: MemorySegment, options: Map<String, String>) {
            for ((k, v) in options) {
                Libav.checkAv(
                    Libav.avOptSet(codecCtx, arena.allocateFrom(k), arena.allocateFrom(v), LibavAbi.AV_OPT_SEARCH_CHILDREN),
                    "av_opt_set($k=$v)",
                )
            }
        }
    }
}
