package dev.hivens.skinema.ass

import dev.hivens.skinema.Debug
import dev.hivens.skinema.libav.Libav
import dev.hivens.skinema.libav.Os
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Hand-written downcalls to libass -- the ASS/SSA renderer behind text
 * subtitles. Same discipline as [Libav]: exact soname, the directory
 * override, an offline oracle (tools/ass-oracle.c) for the one struct
 * read directly (ASS_Image).
 *
 * Strictly optional, like the webp pair: when libass is absent
 * [available] is false, text subtitle tracks refuse selection, and
 * bitmap tracks (which never touch libass) keep working.
 *
 * libass prints every message up to INFO on stderr by default, and
 * ass_set_message_cb ignores a NULL callback -- silencing it takes a
 * no-op UPCALL, skinema's first and only one. The stub never touches
 * its arguments (the va_list parameter binds as a plain pointer on all
 * supported ABIs and is never dereferenced), so there is no marshalling
 * hazard; it lives in [Arena.global] for the process lifetime.
 */
object Ass {

    // libtool naming, like the webp pair: Windows DLLs keep the prefix.
    private fun fileName(base: String, major: Int): String = when (Os.current()) {
        Os.LINUX -> "lib$base.so.$major"
        Os.MAC -> "lib$base.$major.dylib"
        Os.WINDOWS -> "lib$base-$major.dll"
    }

    private class Bindings {
        private val linker = Linker.nativeLinker()

        private fun lookupFile(name: String): SymbolLookup {
            val overridden = Libav.resolveLibraryPath(name)
            return runCatching { SymbolLookup.libraryLookup(overridden, Arena.global()) }
                .recoverCatching { failure ->
                    if (overridden == name) throw failure
                    SymbolLookup.libraryLookup(name, Arena.global())
                }
                .getOrThrow()
        }

        private fun lookup(base: String, major: Int): SymbolLookup = lookupFile(fileName(base, major))

        init {
            // On Windows the freetype/harfbuzz static fold is impossible
            // (MinGW libtool will not put a static archive into a DLL),
            // so they ship as their own DLLs and must be preloaded -- in
            // dependency order, freetype before harfbuzz. Linux/macOS
            // fold them into libass, so there is nothing to preload (and
            // a stray system copy must NOT be pulled in) -- Windows only.
            if (Os.current() == Os.WINDOWS) {
                runCatching { lookup("freetype", 6) }
                // libtool and meson name it libharfbuzz-0.dll; cmake -- the
                // windows-arm64 build, which omits the subset DLL meson cannot
                // link there -- drops the soname suffix, so fall back to the
                // bare name. The preload must match what libass imports, or the
                // bundle's harfbuzz is not in memory when libass resolves it.
                runCatching { lookup("harfbuzz", 0) }.recoverCatching { lookupFile("libharfbuzz.dll") }
            }
            // libass links fribidi (shared in the bundle: it is LGPL and
            // must not be folded into the libass binary); preload it so
            // the bundled copy resolves the dependency by soname.
            runCatching { lookup("fribidi", 0) }
        }

        private val ass = lookup("ass", 9)

        private fun fn(name: String, descriptor: FunctionDescriptor): MethodHandle {
            val symbol = ass.find(name).orElseThrow { UnsatisfiedLinkError("libass exports no '$name'") }
            return linker.downcallHandle(symbol, descriptor)
        }

        val version = fn("ass_library_version", FunctionDescriptor.of(JAVA_INT))
        val libraryInit = fn("ass_library_init", FunctionDescriptor.of(ADDRESS))
        val libraryDone = fn("ass_library_done", FunctionDescriptor.ofVoid(ADDRESS))
        val setMessageCb = fn("ass_set_message_cb", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS))
        val setExtractFonts = fn("ass_set_extract_fonts", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT))
        val addFont = fn("ass_add_font", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT))
        val rendererInit = fn("ass_renderer_init", FunctionDescriptor.of(ADDRESS, ADDRESS))
        val rendererDone = fn("ass_renderer_done", FunctionDescriptor.ofVoid(ADDRESS))
        val setFrameSize = fn("ass_set_frame_size", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT))
        val setStorageSize = fn("ass_set_storage_size", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT))
        val setFonts = fn(
            "ass_set_fonts",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT),
        )
        val newTrack = fn("ass_new_track", FunctionDescriptor.of(ADDRESS, ADDRESS))
        val freeTrack = fn("ass_free_track", FunctionDescriptor.ofVoid(ADDRESS))
        val processCodecPrivate = fn("ass_process_codec_private", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT))
        val processChunk = fn(
            "ass_process_chunk",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG),
        )
        val flushEvents = fn("ass_flush_events", FunctionDescriptor.ofVoid(ADDRESS))
        val renderFrame = fn(
            "ass_render_frame",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS),
        )

        // The silencing stub: MethodHandles.empty IS the no-op -- no
        // reflection, nothing to keep alive but the stub itself.
        val silentStub: MemorySegment = linker.upcallStub(
            MethodHandles.empty(
                MethodType.methodType(
                    Void.TYPE,
                    Int::class.javaPrimitiveType,
                    MemorySegment::class.java,
                    MemorySegment::class.java,
                    MemorySegment::class.java,
                ),
            ),
            FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
            Arena.global(),
        )

        init {
            val v = version.invoke() as Int
            if (v < AssAbi.VERSION_FLOOR) {
                throw UnsatisfiedLinkError(
                    "libass ${Integer.toHexString(v)} is older than the floor ${Integer.toHexString(AssAbi.VERSION_FLOOR)}",
                )
            }
        }
    }

    private val bindings: Bindings? =
        runCatching { Bindings() }.onFailure { Debug.trace("libass bindings unavailable", it) }.getOrNull()

    /** True when libass loaded; false refuses text tracks, bitmap ones work on. */
    val available: Boolean get() = bindings != null

    private fun b(): Bindings = checkNotNull(bindings) { "libass is not available" }

    /** A fresh library handle, already silenced. */
    fun libraryInit(): MemorySegment {
        val lib = b().libraryInit.invoke() as MemorySegment
        if (lib != MemorySegment.NULL) {
            b().setMessageCb.invoke(lib, b().silentStub, MemorySegment.NULL)
        }
        return lib
    }

    fun libraryDone(library: MemorySegment) { b().libraryDone.invoke(library) }
    fun setExtractFonts(library: MemorySegment, extract: Boolean) {
        b().setExtractFonts.invoke(library, if (extract) 1 else 0)
    }

    fun addFont(library: MemorySegment, name: MemorySegment, data: MemorySegment, size: Int) {
        b().addFont.invoke(library, name, data, size)
    }

    fun rendererInit(library: MemorySegment): MemorySegment = b().rendererInit.invoke(library) as MemorySegment
    fun rendererDone(renderer: MemorySegment) { b().rendererDone.invoke(renderer) }
    fun setFrameSize(renderer: MemorySegment, width: Int, height: Int) {
        b().setFrameSize.invoke(renderer, width, height)
    }

    fun setStorageSize(renderer: MemorySegment, width: Int, height: Int) {
        b().setStorageSize.invoke(renderer, width, height)
    }

    /** System font providers, lazily (update=0 -- no cache scan here). */
    fun setFonts(renderer: MemorySegment, defaultFamily: MemorySegment) {
        b().setFonts.invoke(
            renderer, MemorySegment.NULL, defaultFamily,
            AssAbi.FONTPROVIDER_AUTODETECT, MemorySegment.NULL, 0,
        )
    }

    fun newTrack(library: MemorySegment): MemorySegment = b().newTrack.invoke(library) as MemorySegment
    fun freeTrack(track: MemorySegment) { b().freeTrack.invoke(track) }
    fun processCodecPrivate(track: MemorySegment, data: MemorySegment, size: Int) {
        b().processCodecPrivate.invoke(track, data, size)
    }

    fun processChunk(track: MemorySegment, data: MemorySegment, size: Int, timecodeMs: Long, durationMs: Long) {
        b().processChunk.invoke(track, data, size, timecodeMs, durationMs)
    }

    fun flushEvents(track: MemorySegment) { b().flushEvents.invoke(track) }

    fun renderFrame(renderer: MemorySegment, track: MemorySegment, nowMs: Long, detectChangeOut: MemorySegment): MemorySegment =
        b().renderFrame.invoke(renderer, track, nowMs, detectChangeOut) as MemorySegment

    /**
     * Copies an ASS_Image chain out of native memory. Each bitmap is
     * reinterpreted at exactly stride * (h - 1) + w -- the guaranteed
     * allocation; the last row may be unpadded and an overread is UB.
     */
    internal fun parseImages(head: MemorySegment): List<AssPatch> {
        val patches = mutableListOf<AssPatch>()
        var node = head
        while (node != MemorySegment.NULL) {
            val image = node.reinterpret(AssAbi.Image.SIZEOF)
            val w = image.get(JAVA_INT, AssAbi.Image.W)
            val h = image.get(JAVA_INT, AssAbi.Image.H)
            val stride = image.get(JAVA_INT, AssAbi.Image.STRIDE)
            if (w > 0 && h > 0) {
                val bytes = stride.toLong() * (h - 1) + w
                val alpha = ByteArray(bytes.toInt())
                MemorySegment.copy(
                    image.get(ADDRESS, AssAbi.Image.BITMAP).reinterpret(bytes), JAVA_BYTE, 0,
                    alpha, 0, alpha.size,
                )
                patches += AssPatch(
                    width = w,
                    height = h,
                    stride = stride,
                    alpha = alpha,
                    color = image.get(JAVA_INT, AssAbi.Image.COLOR),
                    dstX = image.get(JAVA_INT, AssAbi.Image.DST_X),
                    dstY = image.get(JAVA_INT, AssAbi.Image.DST_Y),
                )
            }
            node = image.get(ADDRESS, AssAbi.Image.NEXT)
        }
        return patches
    }
}

/**
 * One rendered libass image, copied to the heap: a coverage bitmap plus
 * one RGBA color whose low byte is INVERTED alpha (0 = opaque). Rows are
 * [stride] apart; the array holds exactly stride * (height - 1) + width
 * bytes, and bytes past [width] in a row are uninitialized garbage.
 */
internal class AssPatch(
    val width: Int,
    val height: Int,
    val stride: Int,
    val alpha: ByteArray,
    val color: Int,
    val dstX: Int,
    val dstY: Int,
)
