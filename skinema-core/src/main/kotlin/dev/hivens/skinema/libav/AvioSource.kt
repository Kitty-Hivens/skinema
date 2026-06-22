package dev.hivens.skinema.libav

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Wraps a [MediaSource] in a custom AVIOContext so the demuxer pulls bytes
 * from it instead of a file. read/seek are FFM upcalls bound to this
 * instance (no opaque registry); they run on the decode thread, called
 * synchronously by the demuxer. The avio bounce buffer is av_malloc'd
 * (FFmpeg may realloc it) and freed at [free] together with the context.
 *
 * The stubs live in the decoder's confined [Arena], so they outlive exactly
 * the decode session. skinema does no I/O of its own here -- every byte
 * comes from the consumer's [MediaSource].
 */
internal class AvioSource(arena: Arena, private val source: MediaSource) {

    private val scratch = ByteArray(LibavAbi.AVIO_BUFFER_SIZE)
    private val linker = Linker.nativeLinker()

    // Mirror of the source's byte position, so a SEEK_CUR can resolve to an
    // absolute offset without the source having to track it.
    private var position = 0L

    private val readStub: MemorySegment = linker.upcallStub(
        MethodHandles.lookup().bind(
            this, "readPacket",
            MethodType.methodType(Integer.TYPE, MemorySegment::class.java, MemorySegment::class.java, Integer.TYPE),
        ),
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT),
        arena,
    )

    private val seekStub: MemorySegment = linker.upcallStub(
        MethodHandles.lookup().bind(
            this, "seekPacket",
            MethodType.methodType(java.lang.Long.TYPE, MemorySegment::class.java, java.lang.Long.TYPE, Integer.TYPE),
        ),
        FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT),
        arena,
    )

    private val buffer = Libav.avMalloc(LibavAbi.AVIO_BUFFER_SIZE.toLong())

    /** The AVIOContext* to install on a format context's pb (with CUSTOM_IO). */
    val context: MemorySegment

    init {
        if (buffer == MemorySegment.NULL) throw LibavException("av_malloc(avio buffer) returned NULL")
        val ctx = Libav.avioAllocContext(buffer, LibavAbi.AVIO_BUFFER_SIZE, readStub, seekStub)
        if (ctx == MemorySegment.NULL) {
            Libav.avFree(buffer)
            throw LibavException("avio_alloc_context returned NULL")
        }
        context = ctx
    }

    /**
     * read_packet: fills [buf] (up to [bufSize]) from the source. Returns the
     * byte count, or AVERROR_EOF at end of stream. opaque is unused -- the
     * stub is bound to this instance.
     */
    fun readPacket(@Suppress("UNUSED_PARAMETER") opaque: MemorySegment, buf: MemorySegment, bufSize: Int): Int {
        val want = if (bufSize < scratch.size) bufSize else scratch.size
        val n = source.read(scratch, 0, want)
        if (n <= 0) return LibavAbi.AVERROR_EOF
        MemorySegment.copy(scratch, 0, buf.reinterpret(n.toLong()), JAVA_BYTE, 0, n)
        position += n
        return n
    }

    /**
     * seek: AVSEEK_SIZE reports the total size; SET/CUR/END resolve to an
     * absolute offset handed to the source; -1 when the source is not
     * seekable (the demuxer then reads linearly).
     */
    fun seekPacket(@Suppress("UNUSED_PARAMETER") opaque: MemorySegment, offset: Long, whence: Int): Long {
        if (whence and LibavAbi.AVSEEK_SIZE != 0) return source.size()
        val target = when (whence and 0xFFFF) {
            LibavAbi.SEEK_SET -> offset
            LibavAbi.SEEK_CUR -> position + offset
            LibavAbi.SEEK_END -> {
                val total = source.size()
                if (total < 0) return -1
                total + offset
            }
            else -> return -1
        }
        val landed = source.seek(target)
        if (landed >= 0) position = landed
        return landed
    }

    /**
     * Frees the avio context and its (possibly reallocated) buffer, then
     * closes the source. Call AFTER the format context is closed -- the
     * demuxer must no longer reference the pb. avio_context_free does NOT
     * free the buffer, so the current one is released first.
     */
    fun free(ptrPtr: MemorySegment) {
        val current = context.reinterpret(LibavAbi.AvioContext.BUFFER + ADDRESS.byteSize())
            .get(ADDRESS, LibavAbi.AvioContext.BUFFER)
        if (current != MemorySegment.NULL) Libav.avFree(current)
        ptrPtr.set(ADDRESS, 0, context)
        Libav.avioContextFree(ptrPtr)
        runCatching { source.close() }
    }
}
