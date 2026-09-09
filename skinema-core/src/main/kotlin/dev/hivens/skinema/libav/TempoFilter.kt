package dev.hivens.skinema.libav

import dev.hivens.skinema.audio.PcmEncoding
import dev.hivens.skinema.audio.PcmFormat
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pitch-preserving time-stretch over interleaved PCM in one fixed shape: an
 * in-process avfilter graph, abuffer -> atempo -> aformat -> abuffersink. Push
 * input with [process] and read the stretched bytes from [output]; [flush]
 * drains atempo's internal window at end of stream, after which the graph is
 * spent -- [reset] before feeding again. A tempo change is a new instance: the
 * pipeline re-anchors its clock and re-crops the stream anyway, so the buffered
 * state is stale by construction. Confined to the constructing thread, like the
 * decoders.
 *
 * The `aformat` before the sink is what makes the bytes out the same shape as
 * the bytes in. atempo accepts a set of sample formats rather than all of them,
 * and libavfilter answers a format it does not take by negotiating one it does
 * -- silently, and then the graph would hand back samples of another width than
 * the caller counted on. Pinning the output is also what makes the internal
 * conversion visible as a cost rather than as a surprise: at a rate other than
 * 1.0, a source wider than atempo's own formats is converted in and back out.
 */
internal class TempoFilter(
    /** The shape of the PCM in and out; see the class note on `aformat`. */
    val format: PcmFormat,
    val tempo: Double,
) : AutoCloseable {

    private val sampleRate = format.sampleRate

    /** Stretched PCM; only the byte count the last call returned is valid. */
    var output = ByteArray(16384)
        private set

    private val arena = Arena.ofConfined()
    private val frame = Libav.avFrameAlloc().reinterpret(LibavAbi.Frame.SIZEOF)
    private val graphOut = arena.allocate(ADDRESS)
    private var graph = MemorySegment.NULL
    private var src = MemorySegment.NULL
    private var sink = MemorySegment.NULL

    /** [close] is idempotent through this, the way both decoders are. */
    private val closed = AtomicBoolean(false)
    private var inputFramesFed = 0L

    init {
        if (frame == MemorySegment.NULL) throw LibavException("av_frame_alloc returned NULL")
        try {
            buildGraph()
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    /**
     * Pushes [byteCount] input bytes and pulls whatever the stretcher has
     * ready into [output]; returns the output byte count (zero while
     * atempo accumulates its analysis window).
     */
    fun process(pcm: ByteArray, byteCount: Int): Int {
        // A count that is not whole sample frames used to size the AVFrame by
        // the truncated number and then copy the full byteCount into it. The
        // buffer is allocated for the frame's samples, so the remainder --
        // up to three bytes -- landed outside it, and the reinterpret above
        // the copy defeats the bounds check that would have said so. Refused
        // rather than rounded: the input is S16LE stereo by contract, so a
        // partial frame is a caller that has lost track of its own stream.
        require(byteCount % format.bytesPerFrame == 0) {
            "${format.encoding} in ${format.channels} channels takes whole sample frames, got $byteCount bytes"
        }
        val samples = byteCount / format.bytesPerFrame
        if (samples == 0) return 0
        check(src != MemorySegment.NULL && sink != MemorySegment.NULL) {
            "the tempo graph is not built; process after a failed build or a close"
        }
        frame.set(JAVA_INT, LibavAbi.Frame.FORMAT, AudioDecoder.sampleFormatFor(format.encoding))
        frame.set(JAVA_INT, LibavAbi.Frame.SAMPLE_RATE, sampleRate)
        frame.set(JAVA_INT, LibavAbi.Frame.NB_SAMPLES, samples)
        frame.set(JAVA_LONG, LibavAbi.Frame.PTS, inputFramesFed)
        Libav.avChannelLayoutDefault(
            frame.asSlice(LibavAbi.Frame.CH_LAYOUT, LibavAbi.ChannelLayout.SIZEOF),
            format.channels,
        )
        Libav.checkAv(Libav.avFrameGetBuffer(frame, 0), "av_frame_get_buffer")
        val data = frame.get(ADDRESS, LibavAbi.Frame.DATA).reinterpret(byteCount.toLong())
        MemorySegment.copy(pcm, 0, data, JAVA_BYTE, 0, byteCount)
        inputFramesFed += samples
        // add_frame takes the buffer reference and resets the frame, so
        // the same AVFrame serves the next feed and the pulls below.
        Libav.checkAv(Libav.avBuffersrcAddFrame(src, frame), "av_buffersrc_add_frame")
        return drain()
    }

    /** End of stream: drains atempo's window into [output]. */
    fun flush(): Int {
        // Same guard as [process]: a graph that failed to build leaves these
        // NULL, and libavfilter answers a NULL context by dereferencing it --
        // a JVM crash with no stack rather than an exception.
        check(src != MemorySegment.NULL && sink != MemorySegment.NULL) {
            "the tempo graph is not built; flush after a failed build or a close"
        }
        Libav.checkAv(Libav.avBuffersrcAddFrame(src, MemorySegment.NULL), "av_buffersrc_add_frame(eof)")
        return drain()
    }

    /** Drops all buffered state (a seek, a loop wrap); same tempo. */
    fun reset() {
        freeGraph()
        buildGraph()
    }

    private fun drain(): Int {
        var total = 0
        while (true) {
            val ret = Libav.avBuffersinkGetFrame(sink, frame)
            if (ret == LibavAbi.AVERROR_EAGAIN || ret == LibavAbi.AVERROR_EOF) return total
            Libav.checkAv(ret, "av_buffersink_get_frame")
            val bytes = frame.get(JAVA_INT, LibavAbi.Frame.NB_SAMPLES) * format.bytesPerFrame
            if (output.size < total + bytes) output = output.copyOf(maxOf(output.size * 2, total + bytes))
            val data = frame.get(ADDRESS, LibavAbi.Frame.DATA).reinterpret(bytes.toLong())
            MemorySegment.copy(data, JAVA_BYTE, 0, output, total, bytes)
            total += bytes
            Libav.avFrameUnref(frame)
        }
    }

    private fun buildGraph() {
        graph = Libav.avfilterGraphAlloc()
        if (graph == MemorySegment.NULL) throw LibavException("avfilter_graph_alloc returned NULL")
        // The filter name/instance/args strings are needed only until
        // create_filter parses them into the contexts; a transient arena
        // reclaims them per build, so a reset (seek, loop wrap, scrub) does
        // not pile them up in the session arena until close.
        Arena.ofConfined().use { strings ->
            val fmtName = sampleFormatName(format.encoding)
            src = createFilter(
                strings, "abuffer", "in",
                "time_base=1/$sampleRate:sample_rate=$sampleRate:" +
                    "sample_fmt=$fmtName:channel_layout=${format.layout}",
            )
            val atempo = createFilter(strings, "atempo", "atempo", "tempo=$tempo")
            val shape = createFilter(
                strings, "aformat", "shape",
                "sample_fmts=$fmtName:channel_layouts=${format.layout}:sample_rates=$sampleRate",
            )
            sink = createFilter(strings, "abuffersink", "out", null)
            Libav.checkAv(Libav.avfilterLink(src, 0, atempo, 0), "avfilter_link(in->atempo)")
            Libav.checkAv(Libav.avfilterLink(atempo, 0, shape, 0), "avfilter_link(atempo->shape)")
            Libav.checkAv(Libav.avfilterLink(shape, 0, sink, 0), "avfilter_link(shape->out)")
            Libav.checkAv(Libav.avfilterGraphConfig(graph), "avfilter_graph_config")
        }
        inputFramesFed = 0
    }

    private fun createFilter(strings: Arena, filterName: String, instance: String, args: String?): MemorySegment {
        val filter = Libav.avfilterGetByName(strings.allocateFrom(filterName))
        if (filter == MemorySegment.NULL) throw LibavException("the avfilter build carries no '$filterName'")
        Libav.checkAv(
            Libav.avfilterGraphCreateFilter(
                graphOut, filter, strings.allocateFrom(instance),
                args?.let { strings.allocateFrom(it) } ?: MemorySegment.NULL, graph,
            ),
            "avfilter_graph_create_filter($filterName)",
        )
        return graphOut.get(ADDRESS, 0)
    }

    private fun freeGraph() {
        if (graph == MemorySegment.NULL) return
        graphOut.set(ADDRESS, 0, graph)
        Libav.avfilterGraphFree(graphOut)
        graph = MemorySegment.NULL
        src = MemorySegment.NULL
        sink = MemorySegment.NULL
    }

    /**
     * Idempotent, which AutoCloseable asks for and this did not give: the
     * frame handle is a val and stays non-NULL, so a second close reached
     * for a scratch pointer in an arena the first one had already closed.
     * Both siblings carry the same flag for the same reason -- a teardown
     * that throws is a teardown that stopped halfway.
     */
    override fun close() {
        if (closed.getAndSet(true)) return
        freeGraph()
        if (frame != MemorySegment.NULL) {
            graphOut.set(ADDRESS, 0, frame)
            Libav.avFrameFree(graphOut)
        }
        arena.close()
    }

    private companion object {
        /** How libavfilter spells a sample format in a filter argument. */
        fun sampleFormatName(encoding: PcmEncoding): String = when (encoding) {
            PcmEncoding.U8 -> "u8"
            PcmEncoding.S16LE -> "s16"
            PcmEncoding.S32LE -> "s32"
            PcmEncoding.F32LE -> "flt"
            PcmEncoding.F64LE -> "dbl"
        }
    }
}
