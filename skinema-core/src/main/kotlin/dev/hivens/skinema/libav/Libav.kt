package dev.hivens.skinema.libav

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.nio.file.Path

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

    // Where the pinned libraries come from, in precedence order: the
    // skinema.libav.dir system property, the SKINEMA_LIBAV_DIR environment
    // variable, a natives bundle on the classpath ([NativeBundle]), and
    // finally the system loader's search path (dev mode on a matching
    // system FFmpeg).
    private val libavDir: String? =
        (System.getProperty("skinema.libav.dir") ?: System.getenv("SKINEMA_LIBAV_DIR"))
            ?.takeIf { it.isNotBlank() }
            ?: NativeBundle.deployIfBundled()?.toString()

    private fun libraryPath(lib: LibavLibrary): String = resolveLibraryPath(lib.fileName(Os.current()))

    /** Resolves [name] against the natives directory override, shared with the webp bindings. */
    internal fun resolveLibraryPath(name: String): String =
        if (libavDir != null) Path.of(libavDir, name).toAbsolutePath().toString() else name

    // Windows: the pinned av* DLLs and libass import MinGW runtime
    // libraries (zlib, bzip2, iconv, winpthread) that ride in the bundle
    // but are not themselves pinned. Preload them from the bundle by
    // exact name BEFORE the av* set loads, so the importing DLLs bind the
    // bundled copies (matched by base name) rather than a host PATH a
    // clean machine does not have. Off-bundle or absent it falls through.
    // Ass routes its own loads through resolveLibraryPath, so touching it
    // runs this first -- libass's iconv import resolves too.
    init {
        if (Os.current() == Os.WINDOWS) {
            for (rt in listOf("zlib1.dll", "libbz2-1.dll", "libiconv-2.dll", "libwinpthread-1.dll")) {
                runCatching { SymbolLookup.libraryLookup(resolveLibraryPath(rt), Arena.global()) }
            }
        }
    }

    private val lookups: Map<LibavLibrary, SymbolLookup> =
        LibavLibrary.entries.associateWith { lib ->
            SymbolLookup.libraryLookup(libraryPath(lib), Arena.global())
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
    private val hAvOptSet = fn(LibavLibrary.AVUTIL, "av_opt_set", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT))
    private val hAvDictGet = fn(LibavLibrary.AVUTIL, "av_dict_get", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT))
    private val hAvDisplayRotationGet = fn(LibavLibrary.AVUTIL, "av_display_rotation_get", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS))
    private val hAvStrerror = fn(LibavLibrary.AVUTIL, "av_strerror", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG))
    private val hAvFrameAlloc = fn(LibavLibrary.AVUTIL, "av_frame_alloc", FunctionDescriptor.of(ADDRESS))
    private val hAvFrameFree = fn(LibavLibrary.AVUTIL, "av_frame_free", FunctionDescriptor.ofVoid(ADDRESS))
    private val hAvFrameGetBuffer = fn(LibavLibrary.AVUTIL, "av_frame_get_buffer", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
    private val hAvFrameUnref = fn(LibavLibrary.AVUTIL, "av_frame_unref", FunctionDescriptor.ofVoid(ADDRESS))
    private val hAvChannelLayoutDefault = fn(LibavLibrary.AVUTIL, "av_channel_layout_default", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT))

    // -- swresample --------------------------------------------------------------

    private val hSwresampleVersion = fn(LibavLibrary.SWRESAMPLE, "swresample_version", FunctionDescriptor.of(JAVA_INT))
    private val hSwrAllocSetOpts2 = fn(
        LibavLibrary.SWRESAMPLE, "swr_alloc_set_opts2",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS),
    )
    private val hSwrInit = fn(LibavLibrary.SWRESAMPLE, "swr_init", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val hSwrConvert = fn(
        LibavLibrary.SWRESAMPLE, "swr_convert",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT),
    )
    private val hSwrFree = fn(LibavLibrary.SWRESAMPLE, "swr_free", FunctionDescriptor.ofVoid(ADDRESS))

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
    private val hSwsGetCoefficients = fn(LibavLibrary.SWSCALE, "sws_getCoefficients", FunctionDescriptor.of(ADDRESS, JAVA_INT))
    private val hSwsSetColorspaceDetails = fn(
        LibavLibrary.SWSCALE, "sws_setColorspaceDetails",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
    )

    // -- avcodec ---------------------------------------------------------------

    private val hAvcodecVersion = fn(LibavLibrary.AVCODEC, "avcodec_version", FunctionDescriptor.of(JAVA_INT))
    private val hAvPacketAlloc = fn(LibavLibrary.AVCODEC, "av_packet_alloc", FunctionDescriptor.of(ADDRESS))
    private val hAvPacketUnref = fn(LibavLibrary.AVCODEC, "av_packet_unref", FunctionDescriptor.ofVoid(ADDRESS))
    private val hAvPacketFree = fn(LibavLibrary.AVCODEC, "av_packet_free", FunctionDescriptor.ofVoid(ADDRESS))
    private val hAvcodecAllocContext3 = fn(LibavLibrary.AVCODEC, "avcodec_alloc_context3", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val hAvcodecFindDecoder = fn(LibavLibrary.AVCODEC, "avcodec_find_decoder", FunctionDescriptor.of(ADDRESS, JAVA_INT))
    private val hAvcodecGetName = fn(LibavLibrary.AVCODEC, "avcodec_get_name", FunctionDescriptor.of(ADDRESS, JAVA_INT))
    private val hAvPacketSideDataGet = fn(
        LibavLibrary.AVCODEC, "av_packet_side_data_get",
        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT),
    )
    private val hAvcodecFindDecoderByName = fn(LibavLibrary.AVCODEC, "avcodec_find_decoder_by_name", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val hAvcodecParametersToContext = fn(LibavLibrary.AVCODEC, "avcodec_parameters_to_context", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvcodecOpen2 = fn(LibavLibrary.AVCODEC, "avcodec_open2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
    private val hAvcodecSendPacket = fn(LibavLibrary.AVCODEC, "avcodec_send_packet", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvcodecReceiveFrame = fn(LibavLibrary.AVCODEC, "avcodec_receive_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvcodecFreeContext = fn(LibavLibrary.AVCODEC, "avcodec_free_context", FunctionDescriptor.ofVoid(ADDRESS))
    private val hAvcodecFlushBuffers = fn(LibavLibrary.AVCODEC, "avcodec_flush_buffers", FunctionDescriptor.ofVoid(ADDRESS))
    private val hAvcodecDecodeSubtitle2 = fn(
        LibavLibrary.AVCODEC, "avcodec_decode_subtitle2",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
    )
    private val hAvsubtitleFree = fn(LibavLibrary.AVCODEC, "avsubtitle_free", FunctionDescriptor.ofVoid(ADDRESS))

    // -- avfilter ----------------------------------------------------------------

    private val hAvfilterVersion = fn(LibavLibrary.AVFILTER, "avfilter_version", FunctionDescriptor.of(JAVA_INT))
    private val hAvfilterGetByName = fn(LibavLibrary.AVFILTER, "avfilter_get_by_name", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val hAvfilterGraphAlloc = fn(LibavLibrary.AVFILTER, "avfilter_graph_alloc", FunctionDescriptor.of(ADDRESS))
    private val hAvfilterGraphCreateFilter = fn(
        LibavLibrary.AVFILTER, "avfilter_graph_create_filter",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
    )
    private val hAvfilterLink = fn(
        LibavLibrary.AVFILTER, "avfilter_link",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT),
    )
    private val hAvfilterGraphConfig = fn(LibavLibrary.AVFILTER, "avfilter_graph_config", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvfilterGraphFree = fn(LibavLibrary.AVFILTER, "avfilter_graph_free", FunctionDescriptor.ofVoid(ADDRESS))
    private val hAvBuffersrcAddFrame = fn(LibavLibrary.AVFILTER, "av_buffersrc_add_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvBuffersinkGetFrame = fn(LibavLibrary.AVFILTER, "av_buffersink_get_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))

    // -- avformat ----------------------------------------------------------------

    private val hAvformatVersion = fn(LibavLibrary.AVFORMAT, "avformat_version", FunctionDescriptor.of(JAVA_INT))
    private val hAvformatOpenInput = fn(LibavLibrary.AVFORMAT, "avformat_open_input", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    private val hAvformatFindStreamInfo = fn(LibavLibrary.AVFORMAT, "avformat_find_stream_info", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvFindBestStream = fn(LibavLibrary.AVFORMAT, "av_find_best_stream", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT))
    private val hAvReadFrame = fn(LibavLibrary.AVFORMAT, "av_read_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvSeekFrame = fn(LibavLibrary.AVFORMAT, "av_seek_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_INT))
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
            LibavLibrary.AVFILTER to hAvfilterVersion.invoke() as Int,
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

    fun avOptSet(obj: MemorySegment, name: MemorySegment, value: MemorySegment): Int =
        hAvOptSet.invoke(obj, name, value, 0) as Int

    fun avDictGet(dict: MemorySegment, key: MemorySegment): MemorySegment =
        hAvDictGet.invoke(dict, key, MemorySegment.NULL, 0) as MemorySegment

    /** Iteration form: pass the previous entry and AV_DICT_IGNORE_SUFFIX. */
    fun avDictGet(dict: MemorySegment, key: MemorySegment, prev: MemorySegment, flags: Int): MemorySegment =
        hAvDictGet.invoke(dict, key, prev, flags) as MemorySegment

    /** Degrees the display matrix rotates the frame counterclockwise. */
    fun avDisplayRotationGet(matrix: MemorySegment): Double = hAvDisplayRotationGet.invoke(matrix) as Double

    fun avFrameAlloc(): MemorySegment = hAvFrameAlloc.invoke() as MemorySegment
    fun avFrameFree(framePtrPtr: MemorySegment) { hAvFrameFree.invoke(framePtrPtr) }
    fun avFrameGetBuffer(frame: MemorySegment, align: Int): Int = hAvFrameGetBuffer.invoke(frame, align) as Int
    fun avFrameUnref(frame: MemorySegment) { hAvFrameUnref.invoke(frame) }
    fun avChannelLayoutDefault(layout: MemorySegment, channels: Int) { hAvChannelLayoutDefault.invoke(layout, channels) }

    fun swrAllocSetOpts2(
        ctxOut: MemorySegment,
        outLayout: MemorySegment, outFormat: Int, outRate: Int,
        inLayout: MemorySegment, inFormat: Int, inRate: Int,
    ): Int = hSwrAllocSetOpts2.invoke(ctxOut, outLayout, outFormat, outRate, inLayout, inFormat, inRate, 0, MemorySegment.NULL) as Int

    fun swrInit(ctx: MemorySegment): Int = hSwrInit.invoke(ctx) as Int
    fun swrConvert(ctx: MemorySegment, outData: MemorySegment, outCount: Int, inData: MemorySegment, inCount: Int): Int =
        hSwrConvert.invoke(ctx, outData, outCount, inData, inCount) as Int
    fun swrFree(ctxPtrPtr: MemorySegment) { hSwrFree.invoke(ctxPtrPtr) }

    fun swsGetContext(srcW: Int, srcH: Int, srcFormat: Int, dstW: Int, dstH: Int, dstFormat: Int, flags: Int): MemorySegment =
        hSwsGetContext.invoke(srcW, srcH, srcFormat, dstW, dstH, dstFormat, flags, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL) as MemorySegment

    fun swsScale(ctx: MemorySegment, srcData: MemorySegment, srcStride: MemorySegment, srcSliceY: Int, srcSliceH: Int, dstData: MemorySegment, dstStride: MemorySegment): Int =
        hSwsScale.invoke(ctx, srcData, srcStride, srcSliceY, srcSliceH, dstData, dstStride) as Int

    fun swsFreeContext(ctx: MemorySegment) { hSwsFreeContext.invoke(ctx) }

    /** A static int[4] owned by swscale -- pass it along, never read or free it. */
    fun swsGetCoefficients(colorspace: Int): MemorySegment = hSwsGetCoefficients.invoke(colorspace) as MemorySegment

    fun swsSetColorspaceDetails(
        ctx: MemorySegment,
        invTable: MemorySegment, srcRange: Int,
        table: MemorySegment, dstRange: Int,
        brightness: Int, contrast: Int, saturation: Int,
    ): Int = hSwsSetColorspaceDetails.invoke(ctx, invTable, srcRange, table, dstRange, brightness, contrast, saturation) as Int

    fun avPacketAlloc(): MemorySegment = hAvPacketAlloc.invoke() as MemorySegment
    fun avPacketUnref(packet: MemorySegment) { hAvPacketUnref.invoke(packet) }
    fun avPacketFree(packetPtrPtr: MemorySegment) { hAvPacketFree.invoke(packetPtrPtr) }

    fun avcodecAllocContext3(codec: MemorySegment): MemorySegment = hAvcodecAllocContext3.invoke(codec) as MemorySegment
    fun avcodecFindDecoder(codecId: Int): MemorySegment = hAvcodecFindDecoder.invoke(codecId) as MemorySegment

    /** A static string owned by avcodec; never "unknown_codec"-null. */
    fun avcodecGetName(codecId: Int): MemorySegment = hAvcodecGetName.invoke(codecId) as MemorySegment
    fun avPacketSideDataGet(sideData: MemorySegment, count: Int, type: Int): MemorySegment =
        hAvPacketSideDataGet.invoke(sideData, count, type) as MemorySegment
    fun avcodecFindDecoderByName(name: MemorySegment): MemorySegment = hAvcodecFindDecoderByName.invoke(name) as MemorySegment
    fun avcodecParametersToContext(ctx: MemorySegment, par: MemorySegment): Int = hAvcodecParametersToContext.invoke(ctx, par) as Int
    fun avcodecOpen2(ctx: MemorySegment, codec: MemorySegment): Int = hAvcodecOpen2.invoke(ctx, codec, MemorySegment.NULL) as Int
    fun avcodecSendPacket(ctx: MemorySegment, packet: MemorySegment): Int = hAvcodecSendPacket.invoke(ctx, packet) as Int
    fun avcodecReceiveFrame(ctx: MemorySegment, frame: MemorySegment): Int = hAvcodecReceiveFrame.invoke(ctx, frame) as Int
    fun avcodecFreeContext(ctxPtrPtr: MemorySegment) { hAvcodecFreeContext.invoke(ctxPtrPtr) }
    fun avcodecFlushBuffers(ctx: MemorySegment) { hAvcodecFlushBuffers.invoke(ctx) }
    fun avcodecDecodeSubtitle2(ctx: MemorySegment, sub: MemorySegment, gotOut: MemorySegment, packet: MemorySegment): Int =
        hAvcodecDecodeSubtitle2.invoke(ctx, sub, gotOut, packet) as Int
    fun avsubtitleFree(sub: MemorySegment) { hAvsubtitleFree.invoke(sub) }

    fun avfilterGetByName(name: MemorySegment): MemorySegment = hAvfilterGetByName.invoke(name) as MemorySegment
    fun avfilterGraphAlloc(): MemorySegment = hAvfilterGraphAlloc.invoke() as MemorySegment
    fun avfilterGraphCreateFilter(ctxOut: MemorySegment, filter: MemorySegment, name: MemorySegment, args: MemorySegment, graph: MemorySegment): Int =
        hAvfilterGraphCreateFilter.invoke(ctxOut, filter, name, args, MemorySegment.NULL, graph) as Int
    fun avfilterLink(src: MemorySegment, srcPad: Int, dst: MemorySegment, dstPad: Int): Int =
        hAvfilterLink.invoke(src, srcPad, dst, dstPad) as Int
    fun avfilterGraphConfig(graph: MemorySegment): Int = hAvfilterGraphConfig.invoke(graph, MemorySegment.NULL) as Int
    fun avfilterGraphFree(graphPtrPtr: MemorySegment) { hAvfilterGraphFree.invoke(graphPtrPtr) }

    /** A NULL [frame] signals end of stream to the graph. */
    fun avBuffersrcAddFrame(ctx: MemorySegment, frame: MemorySegment): Int = hAvBuffersrcAddFrame.invoke(ctx, frame) as Int
    fun avBuffersinkGetFrame(ctx: MemorySegment, frame: MemorySegment): Int = hAvBuffersinkGetFrame.invoke(ctx, frame) as Int

    fun avformatOpenInput(ctxPtrPtr: MemorySegment, url: MemorySegment): Int =
        hAvformatOpenInput.invoke(ctxPtrPtr, url, MemorySegment.NULL, MemorySegment.NULL) as Int
    fun avformatFindStreamInfo(ctx: MemorySegment): Int = hAvformatFindStreamInfo.invoke(ctx, MemorySegment.NULL) as Int
    fun avFindBestStream(ctx: MemorySegment, mediaType: Int, decoderOut: MemorySegment): Int =
        hAvFindBestStream.invoke(ctx, mediaType, -1, -1, decoderOut, 0) as Int
    fun avReadFrame(ctx: MemorySegment, packet: MemorySegment): Int = hAvReadFrame.invoke(ctx, packet) as Int
    fun avSeekFrame(ctx: MemorySegment, streamIndex: Int, timestamp: Long, flags: Int): Int =
        hAvSeekFrame.invoke(ctx, streamIndex, timestamp, flags) as Int
    fun avformatCloseInput(ctxPtrPtr: MemorySegment) { hAvformatCloseInput.invoke(ctxPtrPtr) }
}
