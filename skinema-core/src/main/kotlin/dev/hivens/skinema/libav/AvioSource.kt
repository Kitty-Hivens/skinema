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

    // A throwable unwinding out of an upcall while native FFmpeg frames are on
    // the stack terminates the VM -- and a MediaSource (the streaming seam) is
    // exactly where a network read raises IOException as a normal failure. The
    // upcalls catch it, stash it here and stop the demuxer; the decode thread
    // calls [throwIfFailed] to resurface it as a clean failure (fail closed).
    @Volatile
    private var pendingError: Throwable? = null

    private val freed = java.util.concurrent.atomic.AtomicBoolean(false)

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
        try {
            val want = if (bufSize < scratch.size) bufSize else scratch.size
            val n = source.read(scratch, 0, want)
            if (n <= 0) return LibavAbi.AVERROR_EOF
            // [buf] is FFmpeg's and is exactly [bufSize] long, while the copy
            // below reinterprets it to whatever count comes back -- which
            // turns off the bounds check. A source that answers with more
            // than it was asked for therefore writes past the end of an
            // FFmpeg allocation, silently, and the damage surfaces later as a
            // crash with no connection to the source that caused it. The
            // interface says "up to length"; one that says otherwise fails
            // this session closed, through the same channel a read that
            // raised would take.
            if (n > want) throw LibavException("MediaSource.read returned $n bytes for a request of $want")
            MemorySegment.copy(scratch, 0, buf.reinterpret(n.toLong()), JAVA_BYTE, 0, n)
            position += n
            return n
        } catch (t: Throwable) {
            pendingError = t
            return LibavAbi.AVERROR_EOF
        }
    }

    /**
     * seek: AVSEEK_SIZE reports the total size; SET/CUR/END resolve to an
     * absolute offset handed to the source; -1 when the source is not
     * seekable (the demuxer then reads linearly).
     */
    fun seekPacket(@Suppress("UNUSED_PARAMETER") opaque: MemorySegment, offset: Long, whence: Int): Long {
        try {
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
        } catch (t: Throwable) {
            pendingError = t
            return -1
        }
    }

    /**
     * Rethrows, once, a throwable the source raised inside an upcall. The
     * decode thread calls this after a demuxer read so a stashed failure
     * surfaces as a decode error (-> Failed) rather than a silent EOF.
     */
    fun throwIfFailed() {
        pendingError?.let { pendingError = null; throw it }
    }

    /**
     * Frees the avio context and its (possibly reallocated) buffer, then
     * closes the source. Call AFTER the format context is closed -- the
     * demuxer must no longer reference the pb. avio_context_free does NOT
     * free the buffer, so the current one is released first.
     */
    fun free(ptrPtr: MemorySegment) {
        // Once. Every caller today is exclusive of the others, and the day one
        // is not, a second pass would av_free a released buffer and free a
        // released context -- which aborts the JVM rather than raising, the
        // one failure a caller cannot contain. Both close() paths in this
        // package guard themselves the same way.
        if (!freed.compareAndSet(false, true)) return
        val current = context.reinterpret(LibavAbi.AvioContext.BUFFER + ADDRESS.byteSize())
            .get(ADDRESS, LibavAbi.AvioContext.BUFFER)
        if (current != MemorySegment.NULL) Libav.avFree(current)
        ptrPtr.set(ADDRESS, 0, context)
        Libav.avioContextFree(ptrPtr)
        runCatching { source.close() }
    }
}
