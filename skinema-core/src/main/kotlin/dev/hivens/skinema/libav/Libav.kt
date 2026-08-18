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
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Files
import java.nio.file.Path

/** The byte stream did not decode, or a libav call refused it. */
open class LibavException(message: String) : RuntimeException(message)

/**
 * The file carries nothing to show: no video stream at all, or only an
 * attached picture. Not a failure -- a player answers it by playing the
 * sound frameless -- which is exactly why it is its own type. Read off the
 * base type instead, every OTHER way an open can fail reads as "no video"
 * too: an undecodable codec, a truncated file with no dimensions, a refused
 * hardware-decode request. Those are failures, and turning them into silent
 * audio-only playback is the opposite of failing closed.
 */
class NoVideoStreamException(message: String) : LibavException(message)

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

    /**
     * Loads an OPTIONAL library -- one a tier may legitimately not carry, like
     * libass in `core`. Taken from the natives directory when that directory
     * holds a copy, and from the system loader otherwise.
     *
     * The distinction is which of two states we are in, and they used to be
     * conflated. "The directory has no such file" is an absent capability, and
     * the host's copy is the right answer. "The file is there and will not
     * open" is a broken bundle -- substituting the host's copy there reports
     * the capability as present, so CI passes on a runner that happens to have
     * the library installed while a user without it gets nothing. The
     * acceptance gate installs the ffmpeg CLI for fixtures, which pulls libass
     * in with it, so that runner always had one.
     */
    internal fun optionalLookup(name: String): SymbolLookup {
        val resolved = resolveLibraryPath(name)
        val fromDir = resolved != name && Files.exists(Path.of(resolved))
        return SymbolLookup.libraryLookup(if (fromDir) resolved else name, Arena.global())
    }

    // Windows: the pinned av* DLLs and libass import MinGW runtime
    // libraries (zlib, bzip2, iconv, lzma, winpthread) that ride in the
    // bundle but are not themselves pinned. Preload them from the bundle by
    // exact name BEFORE the av* set loads, so the importing DLLs bind the
    // bundled copies (matched by base name) rather than a host PATH a
    // clean machine does not have -- a full-path LoadLibrary does not search
    // the bundle dir for an importer's own dependencies, so anything an av*
    // DLL links must be mapped here first. Off-bundle or absent it falls
    // through. Ass routes its own loads through resolveLibraryPath, so
    // touching it runs this first -- libass's iconv import resolves too.
    //
    // The list is a superset spanning both Windows toolchains and all three
    // tiers, since one loader serves every bundle: a name absent from the
    // bundle simply falls through. Which of these a bundle actually carries is
    // decided at build time by closing over the DLLs' real imports, so most
    // bundles carry only some of them -- libc++/libunwind ride only where
    // clang links its C++ runtime dynamically (aarch64, full tier), and the
    // compression runtimes only where they were not linked statically.
    // Listing a name that never ships costs nothing; omitting one that does
    // fails the load on a clean machine, which is why this errs wide.
    init {
        if (Os.current() == Os.WINDOWS) {
            val runtimes = listOf(
                "zlib1.dll", "libbz2-1.dll", "libiconv-2.dll", "liblzma-5.dll", "libwinpthread-1.dll",
                "libunwind.dll", "libc++.dll",
            )
            for (rt in runtimes) {
                runCatching { SymbolLookup.libraryLookup(resolveLibraryPath(rt), Arena.global()) }
            }
        }
    }

    private val lookups: Map<LibavLibrary, SymbolLookup> =
        LibavLibrary.entries.associateWith { lib -> load(lib) }

    /**
     * Opens one pinned library, or refuses with the answer rather than with
     * the symptom.
     *
     * Off a bundle the name goes to the system loader, which searches the
     * usual directories -- and a store-based distribution (NixOS, Guix) has
     * none of them: the libraries are installed, in a path nothing looks in.
     * The bare failure names a missing file and says nothing about that, so
     * the escape is named here instead (#23).
     */
    private fun load(lib: LibavLibrary): SymbolLookup {
        val path = libraryPath(lib)
        return try {
            SymbolLookup.libraryLookup(path, Arena.global())
        } catch (t: IllegalArgumentException) {
            throw UnsatisfiedLinkError(loadFailureMessage(lib, path, libavDir)).apply { initCause(t) }
        }
    }

    /** Pulled out of [load] so both branches can be read -- and tested -- without a broken machine. */
    internal fun loadFailureMessage(lib: LibavLibrary, path: String, dir: String?): String {
        val where = if (dir != null) {
            "the natives directory $dir does not carry it, or it will not open from there"
        } else {
            "the system library path holds no ${lib.baseName} of the pinned major ${lib.sonameMajor} " +
                "-- install one, or, where libraries live outside the loader's search path (NixOS, " +
                "Guix), point skinema.libav.dir or SKINEMA_LIBAV_DIR at a directory holding the whole " +
                "av* set, or name that directory in LD_LIBRARY_PATH"
        }
        return "cannot load $path: $where"
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
    private val hAvFrameMakeWritable = fn(LibavLibrary.AVUTIL, "av_frame_make_writable", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val hAvMalloc = fn(LibavLibrary.AVUTIL, "av_malloc", FunctionDescriptor.of(ADDRESS, JAVA_LONG))
    private val hAvFree = fn(LibavLibrary.AVUTIL, "av_free", FunctionDescriptor.ofVoid(ADDRESS))
    private val hAvFrameUnref = fn(LibavLibrary.AVUTIL, "av_frame_unref", FunctionDescriptor.ofVoid(ADDRESS))
    private val hAvChannelLayoutDefault = fn(LibavLibrary.AVUTIL, "av_channel_layout_default", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT))
    private val hAvHwdeviceCtxCreate = fn(LibavLibrary.AVUTIL, "av_hwdevice_ctx_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
    private val hAvHwframeTransferData = fn(LibavLibrary.AVUTIL, "av_hwframe_transfer_data", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
    private val hAvBufferRef = fn(LibavLibrary.AVUTIL, "av_buffer_ref", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val hAvBufferUnref = fn(LibavLibrary.AVUTIL, "av_buffer_unref", FunctionDescriptor.ofVoid(ADDRESS))
    private val hAvFrameCopyProps = fn(LibavLibrary.AVUTIL, "av_frame_copy_props", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvHwframeCtxAlloc = fn(LibavLibrary.AVUTIL, "av_hwframe_ctx_alloc", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val hAvHwframeCtxInit = fn(LibavLibrary.AVUTIL, "av_hwframe_ctx_init", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val hAvHwframeGetBuffer = fn(LibavLibrary.AVUTIL, "av_hwframe_get_buffer", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))

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
    private val hAvcodecGetHwConfig = fn(LibavLibrary.AVCODEC, "avcodec_get_hw_config", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT))
    private val hAvPacketSideDataGet = fn(
        LibavLibrary.AVCODEC, "av_packet_side_data_get",
        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT),
    )
    private val hAvcodecFindDecoderByName = fn(LibavLibrary.AVCODEC, "avcodec_find_decoder_by_name", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val hAvcodecParametersToContext = fn(LibavLibrary.AVCODEC, "avcodec_parameters_to_context", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvcodecOpen2 = fn(LibavLibrary.AVCODEC, "avcodec_open2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
    private val hAvcodecSendPacket = fn(LibavLibrary.AVCODEC, "avcodec_send_packet", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvcodecReceiveFrame = fn(LibavLibrary.AVCODEC, "avcodec_receive_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvcodecFindEncoderByName = fn(LibavLibrary.AVCODEC, "avcodec_find_encoder_by_name", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val hAvcodecGetSupportedConfig = fn(LibavLibrary.AVCODEC, "avcodec_get_supported_config", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS))
    private val hAvcodecSendFrame = fn(LibavLibrary.AVCODEC, "avcodec_send_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvcodecReceivePacket = fn(LibavLibrary.AVCODEC, "avcodec_receive_packet", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvcodecParametersFromContext = fn(LibavLibrary.AVCODEC, "avcodec_parameters_from_context", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
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
    private val hAvioSeek = fn(LibavLibrary.AVFORMAT, "avio_seek", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT))
    private val hAvformatFlush = fn(LibavLibrary.AVFORMAT, "avformat_flush", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val hAvformatCloseInput = fn(LibavLibrary.AVFORMAT, "avformat_close_input", FunctionDescriptor.ofVoid(ADDRESS))
    private val hAvformatAllocOutputContext2 = fn(LibavLibrary.AVFORMAT, "avformat_alloc_output_context2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    private val hAvformatNewStream = fn(LibavLibrary.AVFORMAT, "avformat_new_stream", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
    private val hAvformatWriteHeader = fn(LibavLibrary.AVFORMAT, "avformat_write_header", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvInterleavedWriteFrame = fn(LibavLibrary.AVFORMAT, "av_interleaved_write_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val hAvWriteTrailer = fn(LibavLibrary.AVFORMAT, "av_write_trailer", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val hAvformatFreeContext = fn(LibavLibrary.AVFORMAT, "avformat_free_context", FunctionDescriptor.ofVoid(ADDRESS))
    private val hAvioOpen = fn(LibavLibrary.AVFORMAT, "avio_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
    private val hAvioClosep = fn(LibavLibrary.AVFORMAT, "avio_closep", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val hAvformatAllocContext = fn(LibavLibrary.AVFORMAT, "avformat_alloc_context", FunctionDescriptor.of(ADDRESS))
    private val hAvioAllocContext = fn(LibavLibrary.AVFORMAT, "avio_alloc_context", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    private val hAvioContextFree = fn(LibavLibrary.AVFORMAT, "avio_context_free", FunctionDescriptor.ofVoid(ADDRESS))

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
                // Name the fix, not just the numbers: this fires for a stale
                // skinema-natives pin and for a system FFmpeg of the wrong
                // major, and the two have different answers.
                throw UnsatisfiedLinkError(
                    "${lib.baseName} runtime major $major does not match the pinned ${lib.sonameMajor}, " +
                        "loaded from ${libavDir ?: "the system library path"} -- " +
                        "pair this skinema version with the skinema-natives version its release notes name, " +
                        "or point skinema.libav.dir at an FFmpeg build of the pinned major",
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

    // -- hwaccel (M11 decode, M13 encode): device setup, frame transfer, get_format --

    fun avHwdeviceCtxCreate(deviceCtxOut: MemorySegment, type: Int): Int =
        hAvHwdeviceCtxCreate.invoke(deviceCtxOut, type, MemorySegment.NULL, MemorySegment.NULL, 0) as Int

    /** Named-device variant: [device] is a NUL-terminated path (a VAAPI render node) or NULL for the driver default. */
    fun avHwdeviceCtxCreate(deviceCtxOut: MemorySegment, type: Int, device: MemorySegment): Int =
        hAvHwdeviceCtxCreate.invoke(deviceCtxOut, type, device, MemorySegment.NULL, 0) as Int

    fun avHwframeTransferData(dst: MemorySegment, src: MemorySegment): Int =
        hAvHwframeTransferData.invoke(dst, src, 0) as Int

    /** Allocates an AVHWFramesContext bound to [deviceCtx]; configure its fields then [avHwframeCtxInit]. NULL on OOM. */
    fun avHwframeCtxAlloc(deviceCtx: MemorySegment): MemorySegment = hAvHwframeCtxAlloc.invoke(deviceCtx) as MemorySegment

    /** Finalizes a configured frames context, allocating its GPU surface pool. */
    fun avHwframeCtxInit(framesCtx: MemorySegment): Int = hAvHwframeCtxInit.invoke(framesCtx) as Int

    /** Pulls a blank GPU surface from [framesCtx] into [frame] -- the upload target for [avHwframeTransferData]. */
    fun avHwframeGetBuffer(framesCtx: MemorySegment, frame: MemorySegment): Int =
        hAvHwframeGetBuffer.invoke(framesCtx, frame, 0) as Int

    fun avBufferRef(buf: MemorySegment): MemorySegment = hAvBufferRef.invoke(buf) as MemorySegment
    fun avBufferUnref(bufPtrPtr: MemorySegment) { hAvBufferUnref.invoke(bufPtrPtr) }
    fun avFrameCopyProps(dst: MemorySegment, src: MemorySegment): Int = hAvFrameCopyProps.invoke(dst, src) as Int

    /** const AVCodecHWConfig* at [index]; NULL past the last config. */
    fun avcodecGetHwConfig(codec: MemorySegment, index: Int): MemorySegment =
        hAvcodecGetHwConfig.invoke(codec, index) as MemorySegment

    /**
     * The get_format hwaccel negotiation. avcodec passes the formats it can
     * emit, terminated by AV_PIX_FMT_NONE; returning a hardware-surface
     * format keeps decoding on the GPU, and falling through to the last
     * (software) entry is the graceful no-device answer. skinema's first
     * upcall carrying real logic, called synchronously inside avcodec.
     *
     * The surface to aim for is read off the context, NOT off the calling
     * thread. A frame-threaded decoder negotiates on one of its own worker
     * threads, not on the thread that opened the file, so a thread-scoped
     * target is simply absent when this runs -- and absent means the
     * software entry, which is how hardware decode came to be negotiated
     * away on every open while the device sat there ready.
     */
    @JvmStatic
    @Suppress("unused")
    private fun chooseHwFormat(ctx: MemorySegment, formats: MemorySegment): Int {
        val target = negotiatedHwFormat(ctx)
        val list = formats.reinterpret(Long.MAX_VALUE)
        var i = 0L
        var last = LibavAbi.AV_PIX_FMT_NONE
        while (true) {
            val fmt = list.getAtIndex(JAVA_INT, i)
            if (fmt == LibavAbi.AV_PIX_FMT_NONE) return last
            // Return the surface the opened device actually backs, not just the
            // first hardware format on the list: with two backends offered
            // (e.g. QSV and VAAPI on an Intel box) the wrong one yields a surface
            // the device cannot fill and the decode fails (#2). No match falls
            // through to the last (software) entry, the graceful no-device answer.
            if (target != LibavAbi.AV_PIX_FMT_NONE && fmt == target) return fmt
            last = fmt
            i++
        }
    }

    /**
     * The surface format the decoder that owns [ctx] opened a device for,
     * parked in AVCodecContext.opaque by setupHwAccel. A NULL slot is a
     * software decoder and yields AV_PIX_FMT_NONE, which leaves
     * [chooseHwFormat] returning the software entry.
     */
    private fun negotiatedHwFormat(ctx: MemorySegment): Int {
        val slot = ctx.reinterpret(LibavAbi.CodecContext.SIZEOF).get(ADDRESS, LibavAbi.CodecContext.OPAQUE)
        if (slot == MemorySegment.NULL) return LibavAbi.AV_PIX_FMT_NONE
        return slot.reinterpret(JAVA_INT.byteSize()).get(JAVA_INT, 0)
    }

    private val getFormatStub: MemorySegment = linker.upcallStub(
        MethodHandles.lookup().findStatic(
            Libav::class.java, "chooseHwFormat",
            MethodType.methodType(Integer.TYPE, MemorySegment::class.java, MemorySegment::class.java),
        ),
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
        Arena.global(),
    )

    /** The get_format upcall to install at AVCodecContext.get_format for hw decode. */
    fun getFormatUpcall(): MemorySegment = getFormatStub

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

    /**
     * The values a codec accepts for one kind of configuration (sample
     * formats, sample rates). [outConfigs] receives the list pointer, which
     * is NULL when everything is accepted, and [outNum] its length.
     */
    fun avcodecGetSupportedConfig(
        avctx: MemorySegment,
        codec: MemorySegment,
        config: Int,
        outConfigs: MemorySegment,
        outNum: MemorySegment,
    ): Int = hAvcodecGetSupportedConfig.invoke(avctx, codec, config, 0, outConfigs, outNum) as Int
    fun avSeekFrame(ctx: MemorySegment, streamIndex: Int, timestamp: Long, flags: Int): Int =
        hAvSeekFrame.invoke(ctx, streamIndex, timestamp, flags) as Int
    fun avformatCloseInput(ctxPtrPtr: MemorySegment) { hAvformatCloseInput.invoke(ctxPtrPtr) }

    /** Rewinds the byte stream itself; the escape when a demuxer cannot seek. */
    fun avioSeek(pb: MemorySegment, offset: Long, whence: Int): Long = hAvioSeek.invoke(pb, offset, whence) as Long

    /** Drops the demuxer's buffered state after the byte stream moved under it. */
    fun avformatFlush(ctx: MemorySegment): Int = hAvformatFlush.invoke(ctx) as Int

    // -- encode + mux (M12) ------------------------------------------------------

    fun avcodecFindEncoderByName(name: MemorySegment): MemorySegment = hAvcodecFindEncoderByName.invoke(name) as MemorySegment
    fun avcodecSendFrame(ctx: MemorySegment, frame: MemorySegment): Int = hAvcodecSendFrame.invoke(ctx, frame) as Int
    fun avcodecReceivePacket(ctx: MemorySegment, packet: MemorySegment): Int = hAvcodecReceivePacket.invoke(ctx, packet) as Int
    fun avcodecParametersFromContext(par: MemorySegment, ctx: MemorySegment): Int = hAvcodecParametersFromContext.invoke(par, ctx) as Int

    /** Clones the frame's buffer if the encoder still references it, so it is safe to overwrite. */
    fun avFrameMakeWritable(frame: MemorySegment): Int = hAvFrameMakeWritable.invoke(frame) as Int

    /** av_opt_set searching a context's private child options (crf, preset, ...). */
    fun avOptSet(obj: MemorySegment, name: MemorySegment, value: MemorySegment, searchFlags: Int): Int =
        hAvOptSet.invoke(obj, name, value, searchFlags) as Int

    /** Allocates an output context, inferring the muxer from [filename]'s extension. */
    fun avformatAllocOutputContext2(ctxOut: MemorySegment, filename: MemorySegment): Int =
        hAvformatAllocOutputContext2.invoke(ctxOut, MemorySegment.NULL, MemorySegment.NULL, filename) as Int

    fun avformatNewStream(fmtCtx: MemorySegment): MemorySegment = hAvformatNewStream.invoke(fmtCtx, MemorySegment.NULL) as MemorySegment
    fun avioOpen(pbOut: MemorySegment, url: MemorySegment, flags: Int): Int = hAvioOpen.invoke(pbOut, url, flags) as Int
    fun avioClosep(pbPtrPtr: MemorySegment): Int = hAvioClosep.invoke(pbPtrPtr) as Int
    fun avformatWriteHeader(ctx: MemorySegment): Int = hAvformatWriteHeader.invoke(ctx, MemorySegment.NULL) as Int
    fun avInterleavedWriteFrame(ctx: MemorySegment, packet: MemorySegment): Int = hAvInterleavedWriteFrame.invoke(ctx, packet) as Int
    fun avWriteTrailer(ctx: MemorySegment): Int = hAvWriteTrailer.invoke(ctx) as Int
    fun avformatFreeContext(ctx: MemorySegment) { hAvformatFreeContext.invoke(ctx) }

    // -- custom AVIO input (M-streaming primitive) -------------------------------

    fun avMalloc(size: Long): MemorySegment = hAvMalloc.invoke(size) as MemorySegment
    fun avFree(ptr: MemorySegment) { hAvFree.invoke(ptr) }
    fun avformatAllocContext(): MemorySegment = hAvformatAllocContext.invoke() as MemorySegment

    /** Read-only custom source: write_flag/opaque/write_cb NULL, bound read/seek upcalls. */
    fun avioAllocContext(buffer: MemorySegment, bufferSize: Int, readCb: MemorySegment, seekCb: MemorySegment): MemorySegment =
        hAvioAllocContext.invoke(buffer, bufferSize, 0, MemorySegment.NULL, readCb, MemorySegment.NULL, seekCb) as MemorySegment

    fun avioContextFree(ctxPtrPtr: MemorySegment) { hAvioContextFree.invoke(ctxPtrPtr) }
}
