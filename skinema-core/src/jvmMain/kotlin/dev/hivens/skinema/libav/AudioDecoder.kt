package dev.hivens.skinema.libav

import dev.hivens.skinema.audio.PcmEncoding
import dev.hivens.skinema.audio.PcmFormat
import dev.hivens.skinema.core.nanosToPts
import dev.hivens.skinema.core.ptsToNanos
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The audio half of a file: demux + decode + swresample to interleaved
 * S16LE stereo at the source sample rate, pull-style via [nextChunk].
 * Mirrors [VideoDecoder]'s shape and confinement contract: the opening
 * thread owns the session, and a file without an audio stream is a normal
 * condition ([openOrNull] returns null), not an error.
 */
class AudioDecoder private constructor(
    private val arena: Arena,
    private val fmtCtx: MemorySegment,
    private val codecCtx: MemorySegment,
    private val packet: MemorySegment,
    private val frame: MemorySegment,
    /** The stream actually opened -- the best-stream pick or the request. */
    val streamIndex: Int,
    private val timeBaseNum: Int,
    private val timeBaseDen: Int,
    private val startTimeNanos: Long,
    /** Same contract as [FrameSource.durationNanos]; audio-only files need it too. */
    val durationNanos: Long?,
    /** Every audio stream the container carries, [streamIndex] included. */
    val tracks: List<AudioTrack>,
    /** Format-level tags; the frameless player serves them from here. */
    val tags: Map<String, String>,
    /** Container chapters, same contract as the video side's. */
    val chapters: List<Chapter>,
    /** Encoded cover-art bytes; the frameless player's picture. */
    val coverArt: ByteArray?,
    // The custom byte source backing this decoder (freed at close, after the
    // format context); null for a file-Path decoder.
    private val avioSource: AvioSource? = null,
) : AutoCloseable {

    class PcmChunk internal constructor(
        /**
         * Interleaved PCM in [format]; only the first [byteCount] bytes are
         * meaningful, and the array is reused -- valid until the next
         * [nextChunk] call.
         */
        val pcm: ByteArray,
        val byteCount: Int,
        /** Presentation time of the chunk's first sample. */
        val ptsNanos: Long,
        /**
         * The shape of the bytes above. Until a caller asks for something else
         * through [convertLastAs], this is the file's own shape: the decoder
         * narrows nothing on its own.
         */
        val format: PcmFormat,
    ) {
        /** Shorthand: the rate is the one number every caller needs. */
        val sampleRate: Int get() = format.sampleRate
    }

    private var draining = false

    // swresample state, (re)built lazily from the first decoded frame and
    // on any mid-stream format change.
    private var swrCtx = MemorySegment.NULL
    private var srcFormat = Int.MIN_VALUE
    private var srcRate = 0

    /**
     * The layout the current graph was built for, kept because the frame's own
     * is reused from one call to the next. Compared rather than counted: two
     * layouts can name the same number of channels and map them to different
     * speakers -- 5.1 against 5.1(side) is the ordinary pair -- and a graph
     * built for one resamples the other with the wrong channels in the wrong
     * places, quietly. Zero-filled by the arena, which is
     * AV_CHANNEL_ORDER_UNSPEC over no channels and so matches nothing.
     */
    private val srcLayout = arena.allocate(LibavAbi.ChannelLayout.SIZEOF)

    /** Graphs built; a test observable, so a rebuild per frame cannot hide. */
    internal var swrBuilds = 0
        private set
    private val outLayout = arena.allocate(LibavAbi.ChannelLayout.SIZEOF)
    private val outPlanes = arena.allocate(ADDRESS)
    private var outNative = MemorySegment.NULL
    private var outCapacitySamples = 0
    private var pcmHeap = ByteArray(0)

    // Scratch for av_channel_layout_describe. Sixty-four bytes covers every
    // canonical name libav prints, and the call reports what it wanted anyway.
    private val layoutName = arena.allocate(64)

    /**
     * What [nextChunk] converts into, or null for the file's own shape.
     *
     * Null is the default because narrowing is a decision, and the decoder is
     * not the side that gets to make it: a player negotiates with its output
     * device, a transcoder states what its writer takes. Both do so through
     * [convertLastAs], which is also how the first chunk of a file gets a
     * second chance once the device has answered.
     */
    private var outFormat: PcmFormat? = null

    /** The shape the last decoded frame came in, before anything narrowed it. */
    private var lastSourceFormat: PcmFormat? = null

    /** The output shape the current graph was built for; see [ensureSwr]. */
    private var builtFor: PcmFormat? = null

    /** Decodes and converts the next chunk; null at end of stream. */
    fun nextChunk(): PcmChunk? {
        while (true) {
            when (val ret = Libav.avcodecReceiveFrame(codecCtx, frame)) {
                0 -> return convertCurrentFrame()
                LibavAbi.AVERROR_EAGAIN -> feedOnePacket()
                LibavAbi.AVERROR_EOF -> return null
                else -> Libav.checkAv(ret, "avcodec_receive_frame(audio)")
            }
        }
    }

    /** Same contract as [VideoDecoder.seekTo]; also reopens a drained stream. */
    fun seekTo(ptsNanos: Long) {
        // Re-apply the container start_time the timeline was normalized
        // against (see formatStartTimeNanos) before seeking the demuxer.
        val ts = nanosToPts(ptsNanos + startTimeNanos, timeBaseNum, timeBaseDen)
        val seeked = Libav.avSeekFrame(fmtCtx, streamIndex, ts, LibavAbi.AVSEEK_FLAG_BACKWARD)
        avioSource?.throwIfFailed() // a source error inside the seek upcall, as itself
        Libav.checkAv(seeked, "av_seek_frame(audio)")
        Libav.avcodecFlushBuffers(codecCtx)
        draining = false
    }

    private fun feedOnePacket() {
        if (draining) throw LibavException("audio decoder demanded input while draining")
        while (true) {
            val ret = Libav.avReadFrame(fmtCtx, packet)
            if (ret < 0) {
                // A real EOF, or the AvioSource caught a MediaSource exception
                // and signalled EOF to get off the native stack; resurface it
                // so a streaming failure fails closed rather than ending quietly.
                avioSource?.throwIfFailed()
                draining = true
                Libav.checkAv(Libav.avcodecSendPacket(codecCtx, MemorySegment.NULL), "avcodec_send_packet(audio flush)")
                return
            }
            if (!decodablePacket(packet, streamIndex)) {
                Libav.avPacketUnref(packet)
                continue
            }
            val sent = Libav.avcodecSendPacket(codecCtx, packet)
            Libav.avPacketUnref(packet)
            Libav.checkAv(sent, "avcodec_send_packet(audio)")
            return
        }
    }

    private fun convertCurrentFrame(): PcmChunk {
        val source = sourceFormatOfFrame()
        lastSourceFormat = source
        return convertHeldFrame(outFormat ?: source)
    }

    /**
     * Converts the frame [nextChunk] last returned into [format] instead, and
     * makes it the shape of every chunk after it.
     *
     * The first chunk of a file is decoded before anything knows what the
     * output device will take, because the file's own shape is only knowable
     * from a frame. So the caller reads that first chunk, negotiates with its
     * device, and hands the frame back here when the answer is narrower than
     * what it got. The frame is still the one that was decoded, so nothing is
     * re-read and no sample is lost that the narrowing did not cost.
     */
    fun convertLastAs(format: PcmFormat): PcmChunk {
        checkNotNull(lastSourceFormat) { "no frame has been decoded to convert" }
        outFormat = format
        return convertHeldFrame(format)
    }

    /** The shape the file itself has, from the frame in hand. */
    private fun sourceFormatOfFrame(): PcmFormat {
        val layout = frame.asSlice(LibavAbi.Frame.CH_LAYOUT, LibavAbi.ChannelLayout.SIZEOF)
        val encoding = encodingFor(frame.get(JAVA_INT, LibavAbi.Frame.FORMAT))
        val width = encoding.bytesPerSample * 8
        // Set by the decoder rather than by the container, so it is read here
        // rather than at the open: a frame has been through by now. Through a
        // reinterpret because nothing on this side had ever read a field off
        // the context before, so it is held as the bare pointer libav returned.
        val raw = codecCtx.reinterpret(LibavAbi.CodecContext.SIZEOF)
            .get(JAVA_INT, LibavAbi.CodecContext.BITS_PER_RAW_SAMPLE)
        return PcmFormat(
            sampleRate = frame.get(JAVA_INT, LibavAbi.Frame.SAMPLE_RATE),
            channels = layout.get(JAVA_INT, LibavAbi.ChannelLayout.NB_CHANNELS),
            layout = describeLayout(layout),
            encoding = encoding,
            significantBits = if (raw in 1..width) raw else width,
        )
    }

    private fun convertHeldFrame(target: PcmFormat): PcmChunk {
        val nbSamples = frame.get(JAVA_INT, LibavAbi.Frame.NB_SAMPLES)
        val format = frame.get(JAVA_INT, LibavAbi.Frame.FORMAT)
        val rate = frame.get(JAVA_INT, LibavAbi.Frame.SAMPLE_RATE)
        // The rate is the stream's, never the caller's: a mid-stream rate
        // change rebuilds the graph on both sides rather than resampling to
        // whatever the target was built for, and the chunk says what it is.
        val shape = if (target.sampleRate == rate) target else target.copy(sampleRate = rate)
        ensureSwr(format, rate, shape)
        ensureCapacity(nbSamples, shape)

        // No resampling (out rate = in rate), so swresample buffers nothing
        // and out count always equals in count -- no drain pass needed.
        // extended_data, not data: they are the same pointer for eight
        // channels or fewer, and only extended_data is complete beyond that.
        val inPlanes = frame.get(ADDRESS, LibavAbi.Frame.EXTENDED_DATA)
        val converted = Libav.swrConvert(swrCtx, outPlanes, nbSamples, inPlanes, nbSamples)
        Libav.checkAv(converted, "swr_convert")
        val bytes = converted * shape.bytesPerFrame
        MemorySegment.copy(outNative, JAVA_BYTE, 0, pcmHeap, 0, bytes)

        val pts = frame.get(JAVA_LONG, LibavAbi.Frame.PTS)
            .takeIf { it != LibavAbi.AV_NOPTS_VALUE }
            ?: frame.get(JAVA_LONG, LibavAbi.Frame.BEST_EFFORT_TIMESTAMP)
        // Same zero-origin as the video side: subtract the same container
        // start_time so audio and video stay aligned (see formatStartTimeNanos).
        val ptsNanos = if (pts == LibavAbi.AV_NOPTS_VALUE) {
            0L
        } else {
            (ptsToNanos(pts, timeBaseNum, timeBaseDen) - startTimeNanos).coerceAtLeast(0L)
        }
        return PcmChunk(pcmHeap, bytes, ptsNanos, shape)
    }

    private fun ensureSwr(format: Int, rate: Int, target: PcmFormat) {
        val layout = frame.asSlice(LibavAbi.Frame.CH_LAYOUT, LibavAbi.ChannelLayout.SIZEOF)
        if (swrCtx != MemorySegment.NULL && format == srcFormat && rate == srcRate &&
            target == builtFor && Libav.avChannelLayoutCompare(srcLayout, layout) == 0
        ) {
            return
        }
        if (swrCtx != MemorySegment.NULL) {
            val ptrPtr = arena.allocate(ADDRESS)
            ptrPtr.set(ADDRESS, 0, swrCtx)
            Libav.swrFree(ptrPtr)
            swrCtx = MemorySegment.NULL
        }

        // Same count means the source's own order, copied rather than rebuilt:
        // a default layout for six channels is 5.1, and a file that carries
        // 5.1(side) would have its surrounds moved by a rebuild that only
        // counted them. A different count is a fold, and there the default for
        // the target is the only order anything downstream can assume.
        Libav.avChannelLayoutUninit(outLayout)
        if (target.channels == layout.get(JAVA_INT, LibavAbi.ChannelLayout.NB_CHANNELS)) {
            Libav.checkAv(Libav.avChannelLayoutCopy(outLayout, layout), "av_channel_layout_copy(out)")
        } else {
            Libav.avChannelLayoutDefault(outLayout, target.channels)
        }

        val ctxOut = arena.allocate(ADDRESS)
        Libav.checkAv(
            Libav.swrAllocSetOpts2(
                ctxOut,
                outLayout, sampleFormatFor(target.encoding), rate,
                layout, format, rate,
            ),
            "swr_alloc_set_opts2",
        )
        swrCtx = ctxOut.get(ADDRESS, 0)
        // Dither, because swresample rounds without it and rounding a float or
        // a 24-bit source into sixteen bits is the one conversion this path
        // still makes. It costs nothing where nothing narrows: swresample
        // applies it only when the destination is the narrower of the two. The
        // result is deliberately not checked -- a build that does not know the
        // option converts without dither, which is what it did before.
        Arena.ofConfined().use { strings ->
            Libav.avOptSet(swrCtx, strings.allocateFrom("dither_method"), strings.allocateFrom("triangular"))
        }
        Libav.checkAv(Libav.swrInit(swrCtx), "swr_init")
        srcFormat = format
        srcRate = rate
        builtFor = target
        Libav.checkAv(Libav.avChannelLayoutCopy(srcLayout, layout), "av_channel_layout_copy")
        swrBuilds++
    }

    private fun ensureCapacity(nbSamples: Int, target: PcmFormat) {
        val frameBytes = target.bytesPerFrame
        if (nbSamples <= outCapacitySamples && pcmHeap.size >= outCapacitySamples * frameBytes) return
        outCapacitySamples = maxOf(maxOf(nbSamples * 2, 8192), outCapacitySamples)
        outNative = arena.allocate(outCapacitySamples.toLong() * frameBytes)
        outPlanes.set(ADDRESS, 0, outNative)
        pcmHeap = ByteArray(outCapacitySamples * frameBytes)
    }

    /** The canonical name of a layout, or a plain count when libav has none. */
    private fun describeLayout(layout: MemorySegment): String {
        val wanted = Libav.avChannelLayoutDescribe(layout, layoutName, layoutName.byteSize())
        if (wanted <= 0) return "${layout.get(JAVA_INT, LibavAbi.ChannelLayout.NB_CHANNELS)} channels"
        return layoutName.getString(0)
    }

    // The arena is closed below, and allocating from a closed one throws --
    // so a second close() used to fail where its sibling decoder's is
    // explicitly idempotent, which AutoCloseable requires. It went unnoticed
    // because every call site inside the pipeline wraps it in runCatching.
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val ptrPtr = arena.allocate(ADDRESS)
        if (swrCtx != MemorySegment.NULL) {
            ptrPtr.set(ADDRESS, 0, swrCtx)
            Libav.swrFree(ptrPtr)
        }
        // Frees what a custom channel order allocated inside the copy; the
        // arena owns the structs themselves.
        Libav.avChannelLayoutUninit(srcLayout)
        Libav.avChannelLayoutUninit(outLayout)
        ptrPtr.set(ADDRESS, 0, frame)
        Libav.avFrameFree(ptrPtr)
        ptrPtr.set(ADDRESS, 0, packet)
        Libav.avPacketFree(ptrPtr)
        ptrPtr.set(ADDRESS, 0, codecCtx)
        Libav.avcodecFreeContext(ptrPtr)
        ptrPtr.set(ADDRESS, 0, fmtCtx)
        Libav.avformatCloseInput(ptrPtr)
        avioSource?.free(ptrPtr)
        arena.close()
    }

    companion object {

        /**
         * The interleaved encoding a sample format maps to. Planar and packed
         * both land on the same member, because the seam is interleaved and
         * swresample does that half whatever else it is asked for.
         *
         * Anything unrecognised takes the widest carrier rather than the
         * narrowest: the only member left is 64-bit integer, which no decoder
         * in this build emits, and asking for width loses nothing that a sink's
         * own refusal will not walk back down.
         */
        internal fun encodingFor(sampleFmt: Int): PcmEncoding = when (sampleFmt) {
            LibavAbi.AV_SAMPLE_FMT_U8, LibavAbi.AV_SAMPLE_FMT_U8P -> PcmEncoding.U8
            LibavAbi.AV_SAMPLE_FMT_S16, LibavAbi.AV_SAMPLE_FMT_S16P -> PcmEncoding.S16LE
            LibavAbi.AV_SAMPLE_FMT_S32, LibavAbi.AV_SAMPLE_FMT_S32P -> PcmEncoding.S32LE
            LibavAbi.AV_SAMPLE_FMT_FLT, LibavAbi.AV_SAMPLE_FMT_FLTP -> PcmEncoding.F32LE
            LibavAbi.AV_SAMPLE_FMT_DBL, LibavAbi.AV_SAMPLE_FMT_DBLP -> PcmEncoding.F64LE
            else -> PcmEncoding.F64LE
        }

        /** The packed sample format swresample is asked to produce. */
        internal fun sampleFormatFor(encoding: PcmEncoding): Int = when (encoding) {
            PcmEncoding.U8 -> LibavAbi.AV_SAMPLE_FMT_U8
            PcmEncoding.S16LE -> LibavAbi.AV_SAMPLE_FMT_S16
            PcmEncoding.S32LE -> LibavAbi.AV_SAMPLE_FMT_S32
            PcmEncoding.F32LE -> LibavAbi.AV_SAMPLE_FMT_FLT
            PcmEncoding.F64LE -> LibavAbi.AV_SAMPLE_FMT_DBL
        }

        /**
         * Opens an audio stream of [path]: the explicit [streamIndex],
         * or the demuxer's best pick when null. Null when the file has
         * no audio at all; an index that exists but is not an audio
         * stream is a caller error and throws.
         */
        fun openOrNull(path: Path, streamIndex: Int? = null): AudioDecoder? {
            val arena = Arena.ofConfined()
            val opened = openInput(arena, path)
            return openAudio(arena, opened.fmtCtx, opened.avioSource, streamIndex, path.toString())
        }

        /**
         * Opens an audio stream over a custom byte [source] instead of a
         * file -- the streaming seam for audio-only streams (a music radio
         * feed). Same null-on-no-audio contract; skinema does no I/O of its
         * own, the demuxer pulls bytes through [source].
         */
        fun openOrNull(source: MediaSource, streamIndex: Int? = null): AudioDecoder? {
            val arena = Arena.ofConfined()
            val opened = openInput(arena, source)
            return openAudio(arena, opened.fmtCtx, opened.avioSource, streamIndex, "custom source")
        }

        /** Shared tail: an opened [fmtCtx] -> an audio decoder, null when there is no audio. */
        private fun openAudio(
            arena: Arena,
            fmtCtx: MemorySegment,
            avioSource: AvioSource?,
            streamIndex: Int?,
            label: String,
        ): AudioDecoder? {
            var codecCtx = MemorySegment.NULL
            // Out here so the catch can release them; the same leak the video
            // side carried on every throw past their allocation.
            var packet = MemorySegment.NULL
            var frame = MemorySegment.NULL
            try {
                Libav.checkAv(Libav.avformatFindStreamInfo(fmtCtx), "avformat_find_stream_info")

                val tracks = enumerateTracks(fmtCtx, arena)
                val chosen: Int
                val decoder: MemorySegment
                if (streamIndex == null) {
                    val decoderOut = arena.allocate(ADDRESS)
                    val best = Libav.avFindBestStream(fmtCtx, LibavAbi.AVMEDIA_TYPE_AUDIO, decoderOut)
                    if (best < 0) {
                        // No audio stream (or no decoder for it): a silent
                        // file, not a failure.
                        val ptrPtr = arena.allocate(ADDRESS)
                        ptrPtr.set(ADDRESS, 0, fmtCtx)
                        Libav.avformatCloseInput(ptrPtr)
                        avioSource?.free(ptrPtr)
                        arena.close()
                        return null
                    }
                    chosen = best
                    decoder = decoderOut.get(ADDRESS, 0)
                } else {
                    if (tracks.none { it.streamIndex == streamIndex }) {
                        throw LibavException("stream $streamIndex is not an audio track of $label")
                    }
                    chosen = streamIndex
                    val codecId = streamAt(fmtCtx, chosen)
                        .get(ADDRESS, LibavAbi.Stream.CODECPAR)
                        .reinterpret(LibavAbi.CodecParameters.SIZEOF)
                        .get(JAVA_INT, LibavAbi.CodecParameters.CODEC_ID)
                    decoder = Libav.avcodecFindDecoder(codecId)
                    if (decoder == MemorySegment.NULL) {
                        throw LibavException("no decoder for audio stream $streamIndex of $label")
                    }
                }

                val stream = streamAt(fmtCtx, chosen)
                val timeBaseNum = stream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE)
                val timeBaseDen = stream.get(JAVA_INT, LibavAbi.Stream.TIME_BASE + 4)
                val codecpar = stream.get(ADDRESS, LibavAbi.Stream.CODECPAR)
                    .reinterpret(LibavAbi.CodecParameters.SIZEOF)

                codecCtx = Libav.avcodecAllocContext3(decoder)
                if (codecCtx == MemorySegment.NULL) throw LibavException("avcodec_alloc_context3(audio) returned NULL")
                Libav.checkAv(Libav.avcodecParametersToContext(codecCtx, codecpar), "avcodec_parameters_to_context(audio)")
                Libav.checkAv(Libav.avcodecOpen2(codecCtx, decoder), "avcodec_open2(audio)")

                packet = Libav.avPacketAlloc().reinterpret(LibavAbi.Packet.SIZEOF)
                frame = Libav.avFrameAlloc().reinterpret(LibavAbi.Frame.SIZEOF)
                if (packet == MemorySegment.NULL || frame == MemorySegment.NULL) {
                    throw LibavException("av_packet_alloc/av_frame_alloc(audio) returned NULL")
                }

                val startTimeNanos = formatStartTimeNanos(fmtCtx)
                val duration = containerDurationNanos(fmtCtx, stream, timeBaseNum, timeBaseDen)
                return AudioDecoder(
                    arena, fmtCtx, codecCtx, packet, frame, chosen, timeBaseNum, timeBaseDen,
                    startTimeNanos,
                    duration, tracks,
                    containerTags(fmtCtx, arena),
                    containerChapters(fmtCtx, arena, startTimeNanos),
                    attachedCoverArt(fmtCtx),
                    avioSource,
                )
            } catch (t: Throwable) {
                val ptrPtr = arena.allocate(ADDRESS)
                if (packet != MemorySegment.NULL) {
                    ptrPtr.set(ADDRESS, 0, packet)
                    Libav.avPacketFree(ptrPtr)
                }
                if (frame != MemorySegment.NULL) {
                    ptrPtr.set(ADDRESS, 0, frame)
                    Libav.avFrameFree(ptrPtr)
                }
                if (codecCtx != MemorySegment.NULL) {
                    ptrPtr.set(ADDRESS, 0, codecCtx)
                    Libav.avcodecFreeContext(ptrPtr)
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

        private fun enumerateTracks(fmtCtx: MemorySegment, arena: Arena): List<AudioTrack> {
            val languageKey = arena.allocateFrom("language")
            val titleKey = arena.allocateFrom("title")
            val tracks = mutableListOf<AudioTrack>()
            for (i in 0 until fmtCtx.get(JAVA_INT, LibavAbi.FormatContext.NB_STREAMS)) {
                val stream = streamAt(fmtCtx, i)
                val codecpar = stream.get(ADDRESS, LibavAbi.Stream.CODECPAR)
                    .reinterpret(LibavAbi.CodecParameters.SIZEOF)
                if (codecpar.get(JAVA_INT, LibavAbi.CodecParameters.CODEC_TYPE) != LibavAbi.AVMEDIA_TYPE_AUDIO) continue
                val metadata = stream.get(ADDRESS, LibavAbi.Stream.METADATA)
                tracks += AudioTrack(
                    streamIndex = i,
                    language = dictValue(metadata, languageKey),
                    title = dictValue(metadata, titleKey),
                    channels = codecpar.get(JAVA_INT, LibavAbi.CodecParameters.CH_LAYOUT + LibavAbi.ChannelLayout.NB_CHANNELS),
                    sampleRate = codecpar.get(JAVA_INT, LibavAbi.CodecParameters.SAMPLE_RATE),
                    isDefault = stream.get(JAVA_INT, LibavAbi.Stream.DISPOSITION) and LibavAbi.AV_DISPOSITION_DEFAULT != 0,
                )
            }
            return tracks
        }
    }
}
