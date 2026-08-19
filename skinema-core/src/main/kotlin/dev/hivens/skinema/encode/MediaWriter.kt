package dev.hivens.skinema.encode

import dev.hivens.skinema.Debug
import dev.hivens.skinema.libav.Libav
import dev.hivens.skinema.libav.LibavAbi
import dev.hivens.skinema.libav.LibavException
import dev.hivens.skinema.libav.swsCoefficientsFor
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.file.Files
import java.nio.file.Path

/**
 * Video encode parameters. [codecName] is an FFmpeg encoder name
 * ("libx264", "libx265", "libsvtav1", "libvpx-vp9", ...); [options] are
 * its private options ("crf", "preset", ...). The container muxer is
 * inferred from the output file's extension.
 *
 * A hardware encoder ("h264_vaapi", "hevc_vaapi", ...) is detected from
 * its codec descriptor and driven on the GPU: [MediaWriter] opens a
 * hardware device, builds a surface pool, and uploads each frame before
 * encoding. [device] names the device to open (a VAAPI render node such as
 * "/dev/dri/renderD128"); null lets the driver pick its default. It is
 * ignored for a software encoder. Hardware encode is fail-closed -- if the
 * device or an upload is refused the writer throws, it does not silently
 * fall back to software.
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
    /** Hardware device to open for a GPU encoder (e.g. a VAAPI render node); null = driver default. */
    val device: String? = null,
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
 * reverse-swscaled RGBA -> the encoder's input format; audio is
 * reverse-swresampled S16 -> the encoder's planar format and chunked to
 * the encoder's frame size. The muxer interleaves both streams by dts;
 * [finish] drains the encoders and writes the trailer. Fail-closed: any
 * libav refusal throws [LibavException], and [close] still releases
 * everything.
 *
 * Software encode (M12, RGBA -> YUV420P) and hardware encode (M13, RGBA ->
 * NV12 uploaded to a GPU surface pool, e.g. VAAPI). Frames and samples are
 * pushed from the one thread that called [open].
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

    // Whether the container's index reached the file. Separate from [finished]
    // so close() can tell "nothing to do" from "nobody wrote it".
    private var trailerWritten = false

    /**
     * Encodes one RGBA8888 frame ([VideoEncodeConfig.width] x height,
     * tightly packed) presented at [ptsNanos]. Frames must arrive in
     * non-decreasing pts order.
     */
    fun writeFrame(rgba: ByteArray, ptsNanos: Long) {
        check(!closed) { "writeFrame after close()" }
        check(!finished) { "writeFrame after finish()" }
        video.send(rgba, ptsNanos)
        drain(video.codecCtx, video.streamIndex, MICROS_DEN, video.streamTbNum, video.streamTbDen, video.frameDurationMicros)
    }

    /**
     * Appends interleaved S16LE stereo PCM for the audio stream, encoding
     * whole encoder frames as they fill. Time is the running sample count
     * from the first sample; pushed in order. No-op-safe only when an
     * [AudioEncodeConfig] was given to [open] -- otherwise throws.
     */
    fun writeAudio(pcm: ByteArray) {
        check(!closed) { "writeAudio after close()" }
        check(!finished) { "writeAudio after finish()" }
        val a = audio ?: throw LibavException("this MediaWriter has no audio stream")
        require(pcm.size % BYTES_PER_AUDIO_FRAME == 0) {
            "PCM must be whole S16LE stereo frames, got ${pcm.size} bytes"
        }
        a.append(pcm)
        while (a.hasFullFrame()) {
            a.send(a.frameSize)
            drain(a.codecCtx, a.streamIndex, a.sampleRate, a.streamTbNum, a.streamTbDen, a.frameSize.toLong())
        }
    }

    /** Drains both encoders and writes the container trailer. Call once, before [close]. */
    fun finish() {
        check(!closed) { "finish after close()" }
        if (finished) return
        flushEncoder(video.codecCtx, "avcodec_send_frame(video flush)")
        drain(video.codecCtx, video.streamIndex, MICROS_DEN, video.streamTbNum, video.streamTbDen, video.frameDurationMicros)
        audio?.let { a ->
            // A short final frame, then the flush packet.
            // Whole frames first. send() encodes at most one frame's worth,
            // so a single call left everything past the first frame in the
            // buffer -- and silently, because the clamp that stopped an
            // overrun turned the excess into data nobody encodes.
            while (a.hasFullFrame()) {
                a.send(a.frameSize)
                drain(a.codecCtx, a.streamIndex, a.sampleRate, a.streamTbNum, a.streamTbDen, a.frameSize.toLong())
            }
            a.remainingSamples()?.let { samples ->
                a.send(samples)
                drain(a.codecCtx, a.streamIndex, a.sampleRate, a.streamTbNum, a.streamTbDen, a.frameSize.toLong())
            }
            flushEncoder(a.codecCtx, "avcodec_send_frame(audio flush)")
            drain(a.codecCtx, a.streamIndex, a.sampleRate, a.streamTbNum, a.streamTbDen, a.frameSize.toLong())
        }
        // One attempt, ever, and the flag records the ATTEMPT rather than the
        // outcome. av_write_trailer deinitialises the muxer whether it
        // returns success or failure, so a second call reads private data the
        // first one already freed -- and that arrives as a SIGSEGV, not as a
        // return code, so neither checkAv nor the runCatching in close() can
        // hold it. Recording the outcome instead left exactly that second
        // call on the ordinary path: a trailer refused for a full disk, and
        // then close() -- which every `use` block runs -- taking the process
        // down with it.
        if (!trailerWritten) {
            trailerWritten = true
            Libav.checkAv(Libav.avWriteTrailer(fmtCtx), "av_write_trailer")
        }
        // Still set last: a refusal above must leave finish() able to run its
        // encoder drains again, which are retryable even when the trailer is
        // not.
        finished = true
    }

    /**
     * Sends the flush frame, tolerating an encoder an earlier attempt already
     * flushed -- [finish] is retryable, and a second flush of a drained
     * encoder answers EOF rather than succeeding.
     */
    private fun flushEncoder(encCtx: MemorySegment, what: String) {
        val ret = Libav.avcodecSendFrame(encCtx, MemorySegment.NULL)
        if (ret == LibavAbi.AVERROR_EOF) return
        Libav.checkAv(ret, what)
    }

    /**
     * Pulls every ready packet, rescales its timing from the encoder's
     * time_base (1 / [codecTbDen]) to the muxer's stream time_base, stamps
     * the stream index, and interleaves it into the container.
     */
    private fun drain(
        encCtx: MemorySegment,
        streamIndex: Int,
        codecTbDen: Int,
        streamTbNum: Int,
        streamTbDen: Int,
        fallbackDuration: Long,
    ) {
        while (true) {
            val ret = Libav.avcodecReceivePacket(encCtx, packet)
            if (ret == LibavAbi.AVERROR_EAGAIN || ret == LibavAbi.AVERROR_EOF) return
            Libav.checkAv(ret, "avcodec_receive_packet")
            rescaleField(LibavAbi.Packet.PTS, codecTbDen, streamTbNum, streamTbDen)
            rescaleField(LibavAbi.Packet.DTS, codecTbDen, streamTbNum, streamTbDen)
            // libavcodec fills a packet duration for audio and leaves it at
            // zero for video. Passing that zero on made the muxer derive
            // interior durations from dts deltas and give the LAST sample none
            // -- so mp4 wrote an edit list a frame short and flagged the final
            // sample discard, and every clip this writer produced came back
            // one frame shorter than it was given, with the frame rate
            // misreported to match.
            val dur = packet.get(JAVA_LONG, LibavAbi.Packet.DURATION).takeIf { it > 0 } ?: fallbackDuration
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
        // A writer closed without finish() -- the `use` block that threw, the
        // caller who forgot -- used to leave a file with no trailer: an mp4
        // with no moov atom, which no player opens and nothing reported. Best
        // effort, because whatever aborted the write may refuse this too; a
        // file missing its undrained tail still beats one missing its index.
        if (!trailerWritten) {
            runCatching { Libav.checkAv(Libav.avWriteTrailer(fmtCtx), "av_write_trailer(close)") }
                .onFailure { Debug.trace("av_write_trailer on close", it) }
        }
        val ptrPtr = arena.allocate(ADDRESS)
        video.free(ptrPtr)
        audio?.free(ptrPtr)
        ptrPtr.set(ADDRESS, 0, packet)
        Libav.avPacketFree(ptrPtr)
        if (needsFileIo) {
            ptrPtr.set(ADDRESS, 0, fmtCtx.get(ADDRESS, LibavAbi.FormatContext.PB))
            Libav.avioClosep(ptrPtr)
            // avio_closep nulls the scratch it was handed, not the field it
            // was read from. avformat_free_context below dispatches into the
            // muxer's own deinit, which is entitled to look at pb -- none of
            // the muxers here do today, and none of them should be handed a
            // freed pointer on the strength of that.
            fmtCtx.set(ADDRESS, LibavAbi.FormatContext.PB, MemorySegment.NULL)
        }
        Libav.avformatFreeContext(fmtCtx)
        arena.close()
    }

    /**
     * One video stream: RGBA -> [dstFormat] -> the encoder. Software encode
     * sends the reverse-swscaled frame straight in ([dstFormat] YUV420P).
     * Hardware encode reverse-swscales to NV12, then uploads each frame to a
     * fresh surface from [hwFramesCtx] (the GPU pool) before encoding; the
     * encoder's input pixel format is the surface format, not [dstFormat].
     * [hwFramesCtx]/[hwFrame]/[hwDeviceRef] are NULL for software encode.
     */
    private class VideoTrack(
        private val arena: Arena,
        val codecCtx: MemorySegment,
        private val frame: MemorySegment,
        val streamIndex: Int,
        val streamTbNum: Int,
        val streamTbDen: Int,
        private val width: Int,
        private val height: Int,
        /** One frame at the configured rate, in the codec's microsecond base. */
        val frameDurationMicros: Long,
        private val dstFormat: Int,
        private val hwFramesCtx: MemorySegment,
        private val hwFrame: MemorySegment,
        private val hwDeviceRef: MemorySegment,
    ) {
        private var swsCtx = MemorySegment.NULL
        private var srcData = MemorySegment.NULL
        private var srcStride = MemorySegment.NULL
        private var srcNative = MemorySegment.NULL

        fun send(rgba: ByteArray, ptsNanos: Long) {
            require(rgba.size == width * height * 4) { "frame must be ${width * height * 4} RGBA bytes, got ${rgba.size}" }
            if (swsCtx == MemorySegment.NULL) {
                // Built into a local and published at the end. Assigning the
                // field first meant a throw in between -- the colourspace call
                // below -- left a context standing whose source buffers had
                // never been allocated, and the next frame skipped this block
                // and handed swscale a null. The decode side had the same
                // shape and the same fix.
                val ctx = Libav.swsGetContext(
                    width, height, LibavAbi.AV_PIX_FMT_RGBA,
                    width, height, dstFormat, LibavAbi.SWS_BILINEAR,
                )
                if (ctx == MemorySegment.NULL) throw LibavException("sws_getContext(RGBA->$dstFormat) refused ${width}x$height")
                // The other half of the tag written on the encoder: the
                // conversion has to USE the matrix the file will claim.
                // RGBA is full range in, limited range out.
                val coefficients = Libav.swsGetCoefficients(swsCoefficientsFor(LibavAbi.AVCOL_SPC_UNSPECIFIED, width, height))
                Libav.checkAv(
                    Libav.swsSetColorspaceDetails(ctx, coefficients, 1, coefficients, 0, 0, SWS_UNIT, SWS_UNIT),
                    "sws_setColorspaceDetails(encode)",
                )
                // Padded: swscale reads whole SIMD blocks, so it can read past
                // the last row for a width that is not block-aligned; the slack
                // keeps that read inside the allocation.
                srcNative = arena.allocate(width.toLong() * height * 4 + SWS_READ_PADDING)
                srcData = arena.allocate(ADDRESS, 8).also { it.setAtIndex(ADDRESS, 0, srcNative) }
                srcStride = arena.allocate(JAVA_INT, 8).also { it.setAtIndex(JAVA_INT, 0, width * 4) }
                swsCtx = ctx
            }
            Libav.checkAv(Libav.avFrameMakeWritable(frame), "av_frame_make_writable(video)")
            MemorySegment.copy(rgba, 0, srcNative, JAVA_BYTE, 0, rgba.size)
            Libav.swsScale(swsCtx, srcData, srcStride, 0, height, frame.asSlice(LibavAbi.Frame.DATA), frame.asSlice(LibavAbi.Frame.LINESIZE))
            // Frame stamps are nanoseconds; the codec time_base is microseconds.
            val pts = ptsNanos / NANOS_PER_MICRO
            if (hwFramesCtx == MemorySegment.NULL) {
                frame.set(JAVA_LONG, LibavAbi.Frame.PTS, pts)
                Libav.checkAv(Libav.avcodecSendFrame(codecCtx, frame), "avcodec_send_frame(video)")
            } else {
                // Draw a fresh GPU surface from the pool, upload the staging
                // NV12 frame into it, and encode that. The unref returns the
                // previous surface to the pool; the transfer is synchronous,
                // so the staging frame is free to overwrite next call.
                Libav.avFrameUnref(hwFrame)
                Libav.checkAv(Libav.avHwframeGetBuffer(hwFramesCtx, hwFrame), "av_hwframe_get_buffer(encode)")
                Libav.checkAv(Libav.avHwframeTransferData(hwFrame, frame), "av_hwframe_transfer_data(upload)")
                hwFrame.set(JAVA_LONG, LibavAbi.Frame.PTS, pts)
                Libav.checkAv(Libav.avcodecSendFrame(codecCtx, hwFrame), "avcodec_send_frame(video hw)")
            }
        }

        fun free(ptrPtr: MemorySegment) {
            if (swsCtx != MemorySegment.NULL) Libav.swsFreeContext(swsCtx)
            ptrPtr.set(ADDRESS, 0, frame)
            Libav.avFrameFree(ptrPtr)
            if (hwFrame != MemorySegment.NULL) {
                ptrPtr.set(ADDRESS, 0, hwFrame)
                Libav.avFrameFree(ptrPtr)
            }
            ptrPtr.set(ADDRESS, 0, codecCtx)
            Libav.avcodecFreeContext(ptrPtr)
            // Our refs on the frames pool and device, after the codec dropped its own.
            if (hwFramesCtx != MemorySegment.NULL) {
                ptrPtr.set(ADDRESS, 0, hwFramesCtx)
                Libav.avBufferUnref(ptrPtr)
            }
            if (hwDeviceRef != MemorySegment.NULL) {
                ptrPtr.set(ADDRESS, 0, hwDeviceRef)
                Libav.avBufferUnref(ptrPtr)
            }
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

        /**
         * Encodes up to [frameSize] frames off the front of the buffer.
         *
         * Clamped rather than trusted: the staging buffer and the encoder's
         * frame are both allocated for exactly [frameSize], and the count
         * reaching here is whatever the caller's buffer held -- which after a
         * drain that threw mid-loop is more than one frame's worth. The
         * overrun was stopped only by the bounds check on a DIFFERENT buffer
         * happening to come first, which is an accident rather than an
         * invariant.
         */
        fun send(requested: Int) {
            val samples = minOf(requested, frameSize)
            Libav.checkAv(Libav.avFrameMakeWritable(frame), "av_frame_make_writable(audio)")
            val bytes = samples * BYTES_PER_AUDIO_FRAME
            MemorySegment.copy(pcm, 0, inNative, JAVA_BYTE, 0, bytes)
            val converted = Libav.swrConvert(swr, frame.asSlice(LibavAbi.Frame.DATA), samples, inPlanes, samples)
            Libav.checkAv(converted, "swr_convert(audio encode)")
            frame.set(JAVA_INT, LibavAbi.Frame.NB_SAMPLES, samples)
            frame.set(JAVA_LONG, LibavAbi.Frame.PTS, samplesEncoded)
            Libav.checkAv(Libav.avcodecSendFrame(codecCtx, frame), "avcodec_send_frame(audio)")
            // After the send, with the buffer, so a throw leaves both where
            // they were. Moved first, a caller that retried re-encoded the
            // same samples a frame late -- duplicated sound and a gap.
            samplesEncoded += samples
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
        private const val NANOS_PER_MICRO = 1_000L

        // Slack after the swscale RGBA source: its reader takes whole 16-pixel
        // SIMD blocks, so it can read one block past a non-block-aligned width.
        private const val SWS_READ_PADDING = 128L

        /** 1.0 in swscale's 16.16 fixed point (brightness/contrast/saturation). */
        private const val SWS_UNIT = 1 shl 16

        /**
         * The colour tag for footage of this geometry, on the same rule the
         * decode side falls back to when a stream declares nothing -- so a
         * player that reads the tag and one that guesses from the size reach
         * the same answer.
         */
        private fun colourTag(width: Int, height: Int): String =
            if (swsCoefficientsFor(LibavAbi.AVCOL_SPC_UNSPECIFIED, width, height) == LibavAbi.SWS_CS_ITU709) {
                "bt709"
            } else {
                "smpte170m"
            }

        /** S16LE stereo: 2 bytes x 2 channels per sample frame. */
        private const val BYTES_PER_AUDIO_FRAME = 4
        private const val OUT_CHANNELS = 2

        /** Encoders that report a variable frame size (0) get this fixed chunk. */
        private const val DEFAULT_AUDIO_FRAME_SIZE = 1024

        /**
         * Surfaces pre-allocated for a hardware encoder's fixed input pool --
         * enough for the reorder/async depth at default settings. The VAAPI
         * pool does not grow, so undersizing it stalls av_hwframe_get_buffer.
         */
        private const val HW_FRAME_POOL_SIZE = 20

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
            // Hardware encode: the opened device, our ref on the surface pool
            // (the codec holds its own), and the reusable GPU upload frame.
            var hwDeviceRef = MemorySegment.NULL
            var hwFramesRef = MemorySegment.NULL
            var hwFrame = MemorySegment.NULL
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

                // A hardware encoder takes its surface format (e.g. VAAPI) and
                // is fed NV12 uploads through a GPU frames pool built here. A
                // software one is asked what it takes: YUV420P covers the
                // common ones and led here as a constant, but an encoder that
                // wants 4:2:2, 4:4:4 or planar RGB -- prores, dnxhd,
                // libx264rgb -- was refused by avcodec_open2 with a bare
                // errno. The audio side already picks its sample format this
                // way; this is the branch that fix did not reach.
                val hw = detectHwEncode(vEncoder)
                val swFrameFormat = if (hw == null) {
                    pickPixelFormat(
                        video.codecName,
                        supportedInts(arena, vEncoder, LibavAbi.AV_CODEC_CONFIG_PIX_FORMAT),
                    )
                } else {
                    LibavAbi.AV_PIX_FMT_NV12
                }
                if (hw == null) {
                    vc.set(JAVA_INT, LibavAbi.CodecContext.PIX_FMT, swFrameFormat)
                } else {
                    val deviceOut = arena.allocate(ADDRESS)
                    val deviceArg = video.device?.let { arena.allocateFrom(it) } ?: MemorySegment.NULL
                    Libav.checkAv(Libav.avHwdeviceCtxCreate(deviceOut, hw.deviceType, deviceArg), "av_hwdevice_ctx_create(${video.codecName})")
                    hwDeviceRef = deviceOut.get(ADDRESS, 0)
                    hwFramesRef = Libav.avHwframeCtxAlloc(hwDeviceRef)
                    if (hwFramesRef == MemorySegment.NULL) throw LibavException("av_hwframe_ctx_alloc returned NULL")
                    val fctx = hwFramesRef.reinterpret(LibavAbi.BufferRef.SIZEOF)
                        .get(ADDRESS, LibavAbi.BufferRef.DATA).reinterpret(LibavAbi.HwFramesContext.SIZEOF)
                    fctx.set(JAVA_INT, LibavAbi.HwFramesContext.FORMAT, hw.pixFmt)
                    fctx.set(JAVA_INT, LibavAbi.HwFramesContext.SW_FORMAT, swFrameFormat)
                    fctx.set(JAVA_INT, LibavAbi.HwFramesContext.WIDTH, video.width)
                    fctx.set(JAVA_INT, LibavAbi.HwFramesContext.HEIGHT, video.height)
                    fctx.set(JAVA_INT, LibavAbi.HwFramesContext.INITIAL_POOL_SIZE, HW_FRAME_POOL_SIZE)
                    Libav.checkAv(Libav.avHwframeCtxInit(hwFramesRef), "av_hwframe_ctx_init(${video.codecName})")
                    vc.set(JAVA_INT, LibavAbi.CodecContext.PIX_FMT, hw.pixFmt)
                    vc.set(ADDRESS, LibavAbi.CodecContext.HW_FRAMES_CTX, Libav.avBufferRef(hwFramesRef))
                }
                vc.set(JAVA_INT, LibavAbi.CodecContext.TIME_BASE, 1)
                vc.set(JAVA_INT, LibavAbi.CodecContext.TIME_BASE + 4, MICROS_DEN)
                vc.set(JAVA_INT, LibavAbi.CodecContext.FRAMERATE, video.fps)
                vc.set(JAVA_INT, LibavAbi.CodecContext.FRAMERATE + 4, 1)
                vc.set(JAVA_INT, LibavAbi.CodecContext.GOP_SIZE, video.fps * 2)
                // Say which matrix the pixels were converted with. Nothing
                // did, and swscale converts RGB to YUV with its BT.601
                // default -- so every HD file this writer produced came back
                // wrong, because a stream that declares nothing is read as
                // BT.709 at HD geometry by this decoder and by every other.
                // Measured on a 1280x720 solid colour: 18 of 255 off per
                // channel, against 1 at 64x64 where the two conventions
                // happen to agree. Set through the option names rather than
                // struct fields: these are public AVOptions, and an offset
                // would be one more layout assumption. Before applyOptions,
                // so a caller can still say otherwise.
                val matrix = colourTag(video.width, video.height)
                for ((key, value) in listOf(
                    "colorspace" to matrix, "color_primaries" to matrix, "color_trc" to matrix,
                    // swscale writes limited range below, so declare limited.
                    "color_range" to "tv",
                )) {
                    Libav.checkAv(
                        Libav.avOptSet(vCtx, arena.allocateFrom(key), arena.allocateFrom(value), 0),
                        "av_opt_set($key=$value)",
                    )
                }
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
                    // Every encoder takes its own input format, and planar
                    // float is only the most common one -- libopus takes s16
                    // or interleaved float, flac s16 or s32, alac the planar
                    // integers. Written in as a constant, avcodec_open2
                    // refused all three with a bare EINVAL, two of them named
                    // in this class's own documentation as examples.
                    val rates = supportedInts(arena, aEncoder, LibavAbi.AV_CODEC_CONFIG_SAMPLE_RATE)
                    if (rates != null && audio.sampleRate !in rates) {
                        throw LibavException(
                            "'${audio.codecName}' does not encode at ${audio.sampleRate} Hz " +
                                "(it takes ${rates.joinToString()})",
                        )
                    }
                    val sampleFmt = pickSampleFormat(
                        audio.codecName,
                        supportedInts(arena, aEncoder, LibavAbi.AV_CODEC_CONFIG_SAMPLE_FORMAT),
                    )
                    ac.set(JAVA_INT, LibavAbi.CodecContext.SAMPLE_RATE, audio.sampleRate)
                    ac.set(JAVA_INT, LibavAbi.CodecContext.SAMPLE_FMT, sampleFmt)
                    Libav.avChannelLayoutDefault(ac.asSlice(LibavAbi.CodecContext.CH_LAYOUT, LibavAbi.ChannelLayout.SIZEOF), OUT_CHANNELS)
                    // Pin the codec time_base to 1/sample_rate explicitly: the
                    // audio frame's pts is its running sample count and the
                    // packet rescale on drain assumes this base. The native AAC
                    // encoder defaults to it, but libopus does not necessarily,
                    // so do not trust the default.
                    ac.set(JAVA_INT, LibavAbi.CodecContext.TIME_BASE, 1)
                    ac.set(JAVA_INT, LibavAbi.CodecContext.TIME_BASE + 4, audio.sampleRate)
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
                            swrOut, outLayout, sampleFmt, audio.sampleRate,
                            inLayout, LibavAbi.AV_SAMPLE_FMT_S16, audio.sampleRate,
                        ),
                        "swr_alloc_set_opts2(encode)",
                    )
                    swr = swrOut.get(ADDRESS, 0)
                    Libav.checkAv(Libav.swrInit(swr), "swr_init(encode)")

                    aFrame = Libav.avFrameAlloc().reinterpret(LibavAbi.Frame.SIZEOF)
                    if (aFrame == MemorySegment.NULL) throw LibavException("av_frame_alloc(audio) returned NULL")
                    aFrame.set(JAVA_INT, LibavAbi.Frame.FORMAT, sampleFmt)
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
                vFrame.set(JAVA_INT, LibavAbi.Frame.FORMAT, swFrameFormat)
                vFrame.set(JAVA_INT, LibavAbi.Frame.WIDTH, video.width)
                vFrame.set(JAVA_INT, LibavAbi.Frame.HEIGHT, video.height)
                Libav.checkAv(Libav.avFrameGetBuffer(vFrame, 0), "av_frame_get_buffer(video)")
                if (hw != null) {
                    hwFrame = Libav.avFrameAlloc().reinterpret(LibavAbi.Frame.SIZEOF)
                    if (hwFrame == MemorySegment.NULL) throw LibavException("av_frame_alloc(hw surface) returned NULL")
                }

                val videoTrack = VideoTrack(
                    arena, vCtx, vFrame, vStreamIndex,
                    vStream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE), vStream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE + 4),
                    video.width, video.height, MICROS_DEN.toLong() / video.fps,
                    swFrameFormat, hwFramesRef, hwFrame, hwDeviceRef,
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
                for (f in listOf(vFrame, aFrame, hwFrame)) if (f != MemorySegment.NULL) {
                    ptrPtr.set(ADDRESS, 0, f); Libav.avFrameFree(ptrPtr)
                }
                // After the codec dropped its frames ref above, release ours and the device.
                for (buf in listOf(hwFramesRef, hwDeviceRef)) if (buf != MemorySegment.NULL) {
                    ptrPtr.set(ADDRESS, 0, buf); Libav.avBufferUnref(ptrPtr)
                }
                if (swr != MemorySegment.NULL) { ptrPtr.set(ADDRESS, 0, swr); Libav.swrFree(ptrPtr) }
                if (packet != MemorySegment.NULL) { ptrPtr.set(ADDRESS, 0, packet); Libav.avPacketFree(ptrPtr) }
                if (openedIo) {
                    ptrPtr.set(ADDRESS, 0, fmtCtx.get(ADDRESS, LibavAbi.FormatContext.PB))
                    Libav.avioClosep(ptrPtr)
                    // The same reason close() nulls it: avformat_free_context
                    // below dispatches into the muxer's deinit once the header
                    // has been written, and that must not be handed a freed
                    // pointer. One of the two teardown paths documented the
                    // rule and the other did not follow it.
                    fmtCtx.set(ADDRESS, LibavAbi.FormatContext.PB, MemorySegment.NULL)
                    // avio_open truncates on the way in, so a refusal after it
                    // -- a codec the inferred container will not carry, above
                    // all -- had already destroyed whatever was at that path
                    // and then left the wreck behind. The open promises to
                    // leave nothing; that has to include the file.
                    runCatching { Files.deleteIfExists(path) }
                }
                if (fmtCtx != MemorySegment.NULL) Libav.avformatFreeContext(fmtCtx)
                arena.close()
                throw t
            }
        }

        /**
         * The int values [codec] advertises for [config], or null when it
         * accepts anything of that kind (which is what a NULL list means).
         */
        private fun supportedInts(arena: Arena, codec: MemorySegment, config: Int): IntArray? {
            val listOut = arena.allocate(ADDRESS)
            val countOut = arena.allocate(JAVA_INT)
            Libav.checkAv(
                Libav.avcodecGetSupportedConfig(MemorySegment.NULL, codec, config, listOut, countOut),
                "avcodec_get_supported_config",
            )
            val list = listOut.get(ADDRESS, 0)
            if (list == MemorySegment.NULL) return null
            val count = countOut.get(JAVA_INT, 0)
            if (count <= 0) return IntArray(0)
            val sized = list.reinterpret(count.toLong() * JAVA_INT.byteSize())
            return IntArray(count) { sized.getAtIndex(JAVA_INT, it.toLong()) }
        }

        /**
         * The pixel format to hand a software encoder.
         *
         * YUV420P leads because it is what every encoder that already worked
         * here takes, so nothing that encodes today changes format; the rest
         * runs outward from it through the layouts an RGBA source reaches
         * without losing more than the subsampling already does. A NULL list
         * means the encoder accepts anything, which is the same answer.
         */
        private fun pickPixelFormat(codecName: String, supported: IntArray?): Int {
            if (supported == null) return LibavAbi.AV_PIX_FMT_YUV420P
            for (candidate in PIXEL_FORMAT_PREFERENCE) if (candidate in supported) return candidate
            // Something exotic (a palette, a bit depth swscale will refuse):
            // hand it over anyway and let sws_getContext name what it cannot
            // do, rather than refusing here on a guess about what it means.
            return supported.firstOrNull()
                ?: throw LibavException("'$codecName' advertises no input pixel format")
        }

        private val PIXEL_FORMAT_PREFERENCE = intArrayOf(
            LibavAbi.AV_PIX_FMT_YUV420P,
            LibavAbi.AV_PIX_FMT_YUV422P,
            LibavAbi.AV_PIX_FMT_YUV444P,
            LibavAbi.AV_PIX_FMT_GBRP,
            LibavAbi.AV_PIX_FMT_RGB24,
        )

        /**
         * The sample format to hand the encoder. Planar float leads because
         * it is what the encoders that already worked here take, so nothing
         * that plays today changes format; the rest of the order runs from
         * the shapes an S16 input reaches without requantizing outward.
         */
        private fun pickSampleFormat(codecName: String, supported: IntArray?): Int {
            if (supported == null) return LibavAbi.AV_SAMPLE_FMT_FLTP
            for (candidate in SAMPLE_FORMAT_PREFERENCE) if (candidate in supported) return candidate
            return supported.firstOrNull()
                ?: throw LibavException("'$codecName' advertises no input sample format")
        }

        private val SAMPLE_FORMAT_PREFERENCE = intArrayOf(
            LibavAbi.AV_SAMPLE_FMT_FLTP,
            LibavAbi.AV_SAMPLE_FMT_FLT,
            LibavAbi.AV_SAMPLE_FMT_S16,
            LibavAbi.AV_SAMPLE_FMT_S16P,
            LibavAbi.AV_SAMPLE_FMT_S32,
            LibavAbi.AV_SAMPLE_FMT_S32P,
        )

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

        /** A hardware encoder's surface format and the device type to open for it. */
        private class HwEncode(val pixFmt: Int, val deviceType: Int)

        /**
         * The hw-surface config a hardware encoder draws its frames from, or
         * null for a software encoder. Walks the encoder's hw configs for the
         * first that accepts an AVHWFramesContext -- the same detection for
         * VAAPI, QSV or an NVENC cuda pool, so a new backend needs no code
         * here, only its encoder enabled in the natives build.
         */
        private fun detectHwEncode(encoder: MemorySegment): HwEncode? {
            var i = 0
            while (true) {
                val cfg = Libav.avcodecGetHwConfig(encoder, i)
                if (cfg == MemorySegment.NULL) return null
                val sized = cfg.reinterpret(LibavAbi.CodecHWConfig.SIZEOF)
                val methods = sized.get(JAVA_INT, LibavAbi.CodecHWConfig.METHODS)
                if (methods and LibavAbi.AV_CODEC_HW_CONFIG_METHOD_HW_FRAMES_CTX != 0) {
                    return HwEncode(
                        sized.get(JAVA_INT, LibavAbi.CodecHWConfig.PIX_FMT),
                        sized.get(JAVA_INT, LibavAbi.CodecHWConfig.DEVICE_TYPE),
                    )
                }
                i++
            }
        }
    }
}
