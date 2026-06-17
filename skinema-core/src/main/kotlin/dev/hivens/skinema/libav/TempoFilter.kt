package dev.hivens.skinema.libav

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

/**
 * Pitch-preserving time-stretch over interleaved S16LE stereo PCM at a
 * fixed sample rate: an in-process avfilter graph, abuffer -> atempo ->
 * abuffersink. Push input with [process] and read the stretched bytes
 * from [output]; [flush] drains atempo's internal window at end of
 * stream, after which the graph is spent -- [reset] before feeding
 * again. A tempo change is a new instance: the pipeline re-anchors its
 * clock and re-crops the stream anyway, so the buffered state is stale
 * by construction. Confined to the constructing thread, like the
 * decoders.
 */
internal class TempoFilter(
    private val sampleRate: Int,
    val tempo: Double,
) : AutoCloseable {

    /** Stretched PCM; only the byte count the last call returned is valid. */
    var output = ByteArray(16384)
        private set

    private val arena = Arena.ofConfined()
    private val frame = Libav.avFrameAlloc().reinterpret(LibavAbi.Frame.SIZEOF)
    private val graphOut = arena.allocate(ADDRESS)
    private var graph = MemorySegment.NULL
    private var src = MemorySegment.NULL
    private var sink = MemorySegment.NULL
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
        val samples = byteCount / BYTES_PER_FRAME
        if (samples == 0) return 0
        frame.set(JAVA_INT, LibavAbi.Frame.FORMAT, LibavAbi.AV_SAMPLE_FMT_S16)
        frame.set(JAVA_INT, LibavAbi.Frame.SAMPLE_RATE, sampleRate)
        frame.set(JAVA_INT, LibavAbi.Frame.NB_SAMPLES, samples)
        frame.set(JAVA_LONG, LibavAbi.Frame.PTS, inputFramesFed)
        Libav.avChannelLayoutDefault(frame.asSlice(LibavAbi.Frame.CH_LAYOUT, LibavAbi.ChannelLayout.SIZEOF), CHANNELS)
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
            val bytes = frame.get(JAVA_INT, LibavAbi.Frame.NB_SAMPLES) * BYTES_PER_FRAME
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
            src = createFilter(
                strings, "abuffer", "in",
                "time_base=1/$sampleRate:sample_rate=$sampleRate:sample_fmt=s16:channel_layout=stereo",
            )
            val atempo = createFilter(strings, "atempo", "atempo", "tempo=$tempo")
            sink = createFilter(strings, "abuffersink", "out", null)
            Libav.checkAv(Libav.avfilterLink(src, 0, atempo, 0), "avfilter_link(in->atempo)")
            Libav.checkAv(Libav.avfilterLink(atempo, 0, sink, 0), "avfilter_link(atempo->out)")
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

    override fun close() {
        freeGraph()
        if (frame != MemorySegment.NULL) {
            graphOut.set(ADDRESS, 0, frame)
            Libav.avFrameFree(graphOut)
        }
        arena.close()
    }

    private companion object {
        const val CHANNELS = 2

        /** S16LE stereo: 2 bytes x 2 channels per sample frame. */
        const val BYTES_PER_FRAME = 4
    }
}
