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
    /** Same contract as [FrameSource.durationNanos]; audio-only files need it too. */
    val durationNanos: Long?,
    /** Every audio stream the container carries, [streamIndex] included. */
    val tracks: List<AudioTrack>,
) : AutoCloseable {

    class PcmChunk internal constructor(
        /**
         * Interleaved S16LE stereo; only the first [byteCount] bytes are
         * meaningful, and the array is reused -- valid until the next
         * [nextChunk] call.
         */
        val pcm: ByteArray,
        val byteCount: Int,
        /** Presentation time of the chunk's first sample. */
        val ptsNanos: Long,
        /** Source sample rate; constant within a stream in practice. */
        val sampleRate: Int,
    )

    private var draining = false

    // swresample state, (re)built lazily from the first decoded frame and
    // on any mid-stream format change.
    private var swrCtx = MemorySegment.NULL
    private var srcFormat = Int.MIN_VALUE
    private var srcRate = 0
    private var srcChannels = 0
    private val outLayout = arena.allocate(LibavAbi.ChannelLayout.SIZEOF).also {
        Libav.avChannelLayoutDefault(it, OUT_CHANNELS)
    }
    private val outPlanes = arena.allocate(ADDRESS)
    private var outNative = MemorySegment.NULL
    private var outCapacitySamples = 0
    private var pcmHeap = ByteArray(0)

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
        val ts = nanosToPts(ptsNanos, timeBaseNum, timeBaseDen)
        Libav.checkAv(
            Libav.avSeekFrame(fmtCtx, streamIndex, ts, LibavAbi.AVSEEK_FLAG_BACKWARD),
            "av_seek_frame(audio)",
        )
        Libav.avcodecFlushBuffers(codecCtx)
        draining = false
    }

    private fun feedOnePacket() {
        if (draining) throw LibavException("audio decoder demanded input while draining")
        while (true) {
            val ret = Libav.avReadFrame(fmtCtx, packet)
            if (ret < 0) {
                draining = true
                Libav.checkAv(Libav.avcodecSendPacket(codecCtx, MemorySegment.NULL), "avcodec_send_packet(audio flush)")
                return
            }
            if (packet.get(JAVA_INT, LibavAbi.Packet.STREAM_INDEX) != streamIndex) {
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
        val nbSamples = frame.get(JAVA_INT, LibavAbi.Frame.NB_SAMPLES)
        val format = frame.get(JAVA_INT, LibavAbi.Frame.FORMAT)
        val rate = frame.get(JAVA_INT, LibavAbi.Frame.SAMPLE_RATE)
        ensureSwr(format, rate)
        ensureCapacity(nbSamples)

        // No resampling (out rate = in rate), so swresample buffers nothing
        // and out count always equals in count -- no drain pass needed.
        val converted = Libav.swrConvert(swrCtx, outPlanes, nbSamples, frame.asSlice(LibavAbi.Frame.DATA), nbSamples)
        Libav.checkAv(converted, "swr_convert")
        val bytes = converted * OUT_CHANNELS * 2
        MemorySegment.copy(outNative, JAVA_BYTE, 0, pcmHeap, 0, bytes)

        val pts = frame.get(JAVA_LONG, LibavAbi.Frame.PTS)
            .takeIf { it != LibavAbi.AV_NOPTS_VALUE }
            ?: frame.get(JAVA_LONG, LibavAbi.Frame.BEST_EFFORT_TIMESTAMP)
        val ptsNanos = if (pts == LibavAbi.AV_NOPTS_VALUE) 0L else ptsToNanos(pts, timeBaseNum, timeBaseDen)
        return PcmChunk(pcmHeap, bytes, ptsNanos, rate)
    }

    private fun ensureSwr(format: Int, rate: Int) {
        val channels = frame.get(JAVA_INT, LibavAbi.Frame.CH_LAYOUT + LibavAbi.ChannelLayout.NB_CHANNELS)
        if (swrCtx != MemorySegment.NULL && format == srcFormat && rate == srcRate && channels == srcChannels) return
        if (swrCtx != MemorySegment.NULL) {
            val ptrPtr = arena.allocate(ADDRESS)
            ptrPtr.set(ADDRESS, 0, swrCtx)
            Libav.swrFree(ptrPtr)
            swrCtx = MemorySegment.NULL
        }

        val ctxOut = arena.allocate(ADDRESS)
        Libav.checkAv(
            Libav.swrAllocSetOpts2(
                ctxOut,
                outLayout, LibavAbi.AV_SAMPLE_FMT_S16, rate,
                frame.asSlice(LibavAbi.Frame.CH_LAYOUT, LibavAbi.ChannelLayout.SIZEOF), format, rate,
            ),
            "swr_alloc_set_opts2",
        )
        swrCtx = ctxOut.get(ADDRESS, 0)
        Libav.checkAv(Libav.swrInit(swrCtx), "swr_init")
        srcFormat = format
        srcRate = rate
        srcChannels = channels
    }

    private fun ensureCapacity(nbSamples: Int) {
        if (nbSamples <= outCapacitySamples) return
        outCapacitySamples = maxOf(nbSamples * 2, 8192)
        outNative = arena.allocate(outCapacitySamples.toLong() * OUT_CHANNELS * 2)
        outPlanes.set(ADDRESS, 0, outNative)
        pcmHeap = ByteArray(outCapacitySamples * OUT_CHANNELS * 2)
    }

    override fun close() {
        val ptrPtr = arena.allocate(ADDRESS)
        if (swrCtx != MemorySegment.NULL) {
            ptrPtr.set(ADDRESS, 0, swrCtx)
            Libav.swrFree(ptrPtr)
        }
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

        const val OUT_CHANNELS = 2

        /**
         * Opens an audio stream of [path]: the explicit [streamIndex],
         * or the demuxer's best pick when null. Null when the file has
         * no audio at all; an index that exists but is not an audio
         * stream is a caller error and throws.
         */
        fun openOrNull(path: Path, streamIndex: Int? = null): AudioDecoder? {
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
                        arena.close()
                        return null
                    }
                    chosen = best
                    decoder = decoderOut.get(ADDRESS, 0)
                } else {
                    if (tracks.none { it.streamIndex == streamIndex }) {
                        throw LibavException("stream $streamIndex is not an audio track of $path")
                    }
                    chosen = streamIndex
                    val codecId = streamAt(fmtCtx, chosen)
                        .get(ADDRESS, LibavAbi.Stream.CODECPAR)
                        .reinterpret(LibavAbi.CodecParameters.SIZEOF)
                        .get(JAVA_INT, LibavAbi.CodecParameters.CODEC_ID)
                    decoder = Libav.avcodecFindDecoder(codecId)
                    if (decoder == MemorySegment.NULL) {
                        throw LibavException("no decoder for audio stream $streamIndex of $path")
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

                val packet = Libav.avPacketAlloc().reinterpret(LibavAbi.Packet.SIZEOF)
                val frame = Libav.avFrameAlloc().reinterpret(LibavAbi.Frame.SIZEOF)

                val duration = containerDurationNanos(fmtCtx, stream, timeBaseNum, timeBaseDen)
                return AudioDecoder(arena, fmtCtx, codecCtx, packet, frame, chosen, timeBaseNum, timeBaseDen, duration, tracks)
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

        private fun streamAt(fmtCtx: MemorySegment, index: Int): MemorySegment {
            val streams = fmtCtx.get(ADDRESS, LibavAbi.FormatContext.STREAMS)
                .reinterpret((index + 1L) * ADDRESS.byteSize())
            return streams.getAtIndex(ADDRESS, index.toLong()).reinterpret(LibavAbi.Stream.SIZEOF)
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

        private fun dictValue(dict: MemorySegment, key: MemorySegment): String? {
            if (dict == MemorySegment.NULL) return null
            val entry = Libav.avDictGet(dict, key)
            if (entry == MemorySegment.NULL) return null
            val value = entry.reinterpret(LibavAbi.DictEntry.SIZEOF).get(ADDRESS, LibavAbi.DictEntry.VALUE)
            return if (value == MemorySegment.NULL) null else value.reinterpret(Long.MAX_VALUE).getString(0)
        }
    }
}
