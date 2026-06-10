package dev.hivens.skinema.libav

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/** The byte stream did not decode, or a libav call refused it. */
class LibavException(message: String) : RuntimeException(message)

/**
 * Hand-written downcall surface over the pinned libav* libraries -- the
 * whole binding layer, deliberately small enough to read in one sitting
 * (ROADMAP.md section 5).
 *
 * Libraries load once per process into [Arena.global] and stay mapped;
 * unload semantics are an M3 question. The init block probes every
 * library's runtime version and refuses a major that does not match
 * [LibavLibrary]'s pin -- [LibavAbi] offsets are only valid for that major.
 *
 * Calls go through [MethodHandle.invoke] (adapting, not exact) -- a few
 * calls per frame against a millisecond-scale decode makes the adaptation
 * cost irrelevant; M1 may tighten hot paths to invokeExact.
 */
object Libav {

    private val linker = Linker.nativeLinker()
    private val lookups: Map<LibavLibrary, SymbolLookup> =
        LibavLibrary.entries.associateWith { lib ->
            SymbolLookup.libraryLookup(lib.fileName(Os.current()), Arena.global())
        }

    private fun fn(lib: LibavLibrary, name: String, descriptor: FunctionDescriptor): MethodHandle {
        val symbol = lookups.getValue(lib).find(name).orElseThrow {
            UnsatisfiedLinkError("${lib.fileName(Os.current())} exports no '$name'")
        }
        return linker.downcallHandle(symbol, descriptor)
    }

    // -- avutil --------------------------------------------------------------

    private val hAvutilVersion = fn(LibavLibrary.AVUTIL, "avutil_version", FunctionDescriptor.of(JAVA_INT))
    private val hAvLogSetLevel = fn(LibavLibrary.AVUTIL, "av_log_set_level", FunctionDescriptor.ofVoid(JAVA_INT))
    private val hAvStrerror = fn(LibavLibrary.AVUTIL, "av_strerror", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG))
    private val hAvFrameAlloc = fn(LibavLibrary.AVUTIL, "av_frame_alloc", FunctionDescriptor.of(ADDRESS))
    private val hAvFrameFree = fn(LibavLibrary.AVUTIL, "av_frame_free", FunctionDescriptor.ofVoid(ADDRESS))

    // -- swresample (version probe only until audio lands) --------------------

    private val hSwresampleVersion = fn(LibavLibrary.SWRESAMPLE, "swresample_version", FunctionDescriptor.of(JAVA_INT))

    // -- swscale ---------------------------------------------------------------

    private val hSwscaleVersion = fn(LibavLibrary.SWSCALE, "swscale_version", FunctionDescriptor.of(JAVA_INT))
    private val hSwsGetContext = fn(
        LibavLibrary.SWSCALE, "sws_getContext",
        FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
    )
    private val hSwsScale = fn(
        LibavLibrary.SWSCALE, "sws_scale",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS),
    )
    private val hSwsFreeContext = fn(LibavLibrary.SWSCALE, "sws_freeContext", FunctionDescriptor.ofVoid(ADDRESS))

    // -- avcodec ---------------------------------------------------------------

    private val hAvcodecVersion = fn(LibavLibrary.AVCODEC, "avcodec_version", FunctionDescriptor.of(JAVA_INT))
    private val hAvPacketAlloc = fn(LibavLibrary.AVCODEC, "av_packet_alloc", FunctionDescriptor.of(ADDRESS))
    private val hAvPacketUnref = fn(LibavLibrary.AVCODEC, "av_packet_unref", FunctionDescriptor.ofVoid(ADDRESS))
    private val hAvPacketFree = fn(LibavLibrary.AVCODEC, "av_packet_free", FunctionDescriptor.ofVoid(ADDRESS))
    private val hAvcodecAllocContext3 = fn(LibavLibrary.AVCODEC, "avcodec_alloc_context3", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val hAvcodecParametersToContext = fn(LibavLibrary.AVCODEC, "avcodec_parameters_to_context", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvcodecOpen2 = fn(LibavLibrary.AVCODEC, "avcodec_open2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
    private val hAvcodecSendPacket = fn(LibavLibrary.AVCODEC, "avcodec_send_packet", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvcodecReceiveFrame = fn(LibavLibrary.AVCODEC, "avcodec_receive_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvcodecFreeContext = fn(LibavLibrary.AVCODEC, "avcodec_free_context", FunctionDescriptor.ofVoid(ADDRESS))

    // -- avformat ----------------------------------------------------------------

    private val hAvformatVersion = fn(LibavLibrary.AVFORMAT, "avformat_version", FunctionDescriptor.of(JAVA_INT))
    private val hAvformatOpenInput = fn(LibavLibrary.AVFORMAT, "avformat_open_input", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    private val hAvformatFindStreamInfo = fn(LibavLibrary.AVFORMAT, "avformat_find_stream_info", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvFindBestStream = fn(LibavLibrary.AVFORMAT, "av_find_best_stream", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT))
    private val hAvReadFrame = fn(LibavLibrary.AVFORMAT, "av_read_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvformatCloseInput = fn(LibavLibrary.AVFORMAT, "avformat_close_input", FunctionDescriptor.ofVoid(ADDRESS))

    /** Loaded library versions as "major.minor.micro", keyed by library. */
    val versions: Map<LibavLibrary, String>

    init {
        val raw = mapOf(
            LibavLibrary.AVUTIL to hAvutilVersion.invoke() as Int,
            LibavLibrary.SWRESAMPLE to hSwresampleVersion.invoke() as Int,
            LibavLibrary.SWSCALE to hSwscaleVersion.invoke() as Int,
            LibavLibrary.AVCODEC to hAvcodecVersion.invoke() as Int,
            LibavLibrary.AVFORMAT to hAvformatVersion.invoke() as Int,
        )
        raw.forEach { (lib, v) ->
            val major = v shr 16
            if (major != lib.sonameMajor) {
                throw UnsatisfiedLinkError(
                    "${lib.baseName} runtime major $major does not match the pinned ${lib.sonameMajor}",
                )
            }
        }
        versions = raw.mapValues { (_, v) -> "${v shr 16}.${(v shr 8) and 0xFF}.${v and 0xFF}" }
        // FFmpeg logs to stderr by default (ROADMAP trap 2); no upcall, just quiet.
        hAvLogSetLevel.invoke(LibavAbi.AV_LOG_QUIET)
    }

    fun errorText(err: Int): String = Arena.ofConfined().use { a ->
        val buf = a.allocate(256)
        hAvStrerror.invoke(err, buf, 256L)
        buf.getString(0)
    }

    internal fun checkAv(ret: Int, what: String): Int {
        if (ret < 0) throw LibavException("$what failed: ${errorText(ret)} ($ret)")
        return ret
    }

    // Thin typed wrappers -- one per native function, no logic.

    fun avFrameAlloc(): MemorySegment = hAvFrameAlloc.invoke() as MemorySegment
    fun avFrameFree(framePtrPtr: MemorySegment) { hAvFrameFree.invoke(framePtrPtr) }

    fun swsGetContext(srcW: Int, srcH: Int, srcFormat: Int, dstW: Int, dstH: Int, dstFormat: Int, flags: Int): MemorySegment =
        hSwsGetContext.invoke(srcW, srcH, srcFormat, dstW, dstH, dstFormat, flags, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL) as MemorySegment

    fun swsScale(ctx: MemorySegment, srcData: MemorySegment, srcStride: MemorySegment, srcSliceY: Int, srcSliceH: Int, dstData: MemorySegment, dstStride: MemorySegment): Int =
        hSwsScale.invoke(ctx, srcData, srcStride, srcSliceY, srcSliceH, dstData, dstStride) as Int

    fun swsFreeContext(ctx: MemorySegment) { hSwsFreeContext.invoke(ctx) }

    fun avPacketAlloc(): MemorySegment = hAvPacketAlloc.invoke() as MemorySegment
    fun avPacketUnref(packet: MemorySegment) { hAvPacketUnref.invoke(packet) }
    fun avPacketFree(packetPtrPtr: MemorySegment) { hAvPacketFree.invoke(packetPtrPtr) }

    fun avcodecAllocContext3(codec: MemorySegment): MemorySegment = hAvcodecAllocContext3.invoke(codec) as MemorySegment
    fun avcodecParametersToContext(ctx: MemorySegment, par: MemorySegment): Int = hAvcodecParametersToContext.invoke(ctx, par) as Int
    fun avcodecOpen2(ctx: MemorySegment, codec: MemorySegment): Int = hAvcodecOpen2.invoke(ctx, codec, MemorySegment.NULL) as Int
    fun avcodecSendPacket(ctx: MemorySegment, packet: MemorySegment): Int = hAvcodecSendPacket.invoke(ctx, packet) as Int
    fun avcodecReceiveFrame(ctx: MemorySegment, frame: MemorySegment): Int = hAvcodecReceiveFrame.invoke(ctx, frame) as Int
    fun avcodecFreeContext(ctxPtrPtr: MemorySegment) { hAvcodecFreeContext.invoke(ctxPtrPtr) }

    fun avformatOpenInput(ctxPtrPtr: MemorySegment, url: MemorySegment): Int =
        hAvformatOpenInput.invoke(ctxPtrPtr, url, MemorySegment.NULL, MemorySegment.NULL) as Int
    fun avformatFindStreamInfo(ctx: MemorySegment): Int = hAvformatFindStreamInfo.invoke(ctx, MemorySegment.NULL) as Int
    fun avFindBestStream(ctx: MemorySegment, mediaType: Int, decoderOut: MemorySegment): Int =
        hAvFindBestStream.invoke(ctx, mediaType, -1, -1, decoderOut, 0) as Int
    fun avReadFrame(ctx: MemorySegment, packet: MemorySegment): Int = hAvReadFrame.invoke(ctx, packet) as Int
    fun avformatCloseInput(ctxPtrPtr: MemorySegment) { hAvformatCloseInput.invoke(ctxPtrPtr) }
}
